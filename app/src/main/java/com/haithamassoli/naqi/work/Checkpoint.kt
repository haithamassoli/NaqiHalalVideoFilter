package com.haithamassoli.naqi.work

import com.haithamassoli.naqi.audio.AudioDecoder
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.render.RenderSegment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 2's on-disk job state (`long-film-plan.md`): the segment plan, and the checkpoints that let a
 * killed job resume within one segment's worth of work instead of losing everything.
 *
 * **The checkpoint unit is one COMPLETED segment.** Mid-segment state — the gate firings so far, the live
 * `FaceTracker` map — is deliberately never persisted; an interruption costs the segment in flight and
 * nothing more. Every file is written to `<name>.tmp` and renamed, so a file existing under its final name
 * *means* it is complete. That is the whole atomicity story: no manifest to keep in sync with the files it
 * describes, and no way for a checkpoint to reference a half-written segment.
 *
 * Files inside [JobStore.dir]:
 * - `an-NNN.json` — segment NNN's analysis: its gate firings and its face-track EDL.
 * - `seg-NNN.mp4` — segment NNN's rendered video (video-only; see [RenderSegment]).
 * - `audio.pcm` / `audio.json` — the separated-audio scratch and how far it got.
 */
internal object Checkpoint {

    /**
     * Segment length. The per-export fixed cost (Transformer + encoder init) measured ~0.5–0.7 s on the
     * S23 spike, so at 5 min a 155-min film pays ~19 s of overhead across 31 segments — irrelevant next to
     * the ~83 min the passes take. What 5 min really buys is the interruption cost: analyze runs at about
     * 0.45x realtime, so one lost segment is ~2.3 min of work.
     *
     * ponytail: one fixed length for every device. The plan wanted it derived from the measured fixed
     * cost; at 0.6 s per export that derivation lands anywhere between 1 and 10 min with no practical
     * difference, so it stays a constant until a slower device makes the fixed cost matter.
     */
    const val SEGMENT_MS = 5L * 60 * 1000

    /**
     * The segment plan, or **empty for "run the whole timeline in one pass"** — the M1/M2 route that is
     * already device-verified and stays byte-for-byte unchanged for ordinary clips.
     *
     * Segmenting is gated on the same 30-minute threshold that already makes the app warn the user this is
     * a long job ([Eta.CONFIRM_THRESHOLD_MS]), so there is exactly one notion of "long" in the product.
     * [forcedSegmentMs] is the debug override: any positive value segments regardless of duration, which is
     * the only way to exercise multi-segment concat and kill/resume on a clip short enough to iterate on.
     */
    fun plan(durationMs: Long, forcedSegmentMs: Long = 0L): List<RenderSegment> {
        val segmentMs = if (forcedSegmentMs > 0) forcedSegmentMs else SEGMENT_MS
        if (durationMs <= 0) return emptyList()
        if (forcedSegmentMs <= 0 && durationMs < Eta.CONFIRM_THRESHOLD_MS) return emptyList()
        if (durationMs <= segmentMs) return emptyList()
        val count = ((durationMs + segmentMs - 1) / segmentMs).toInt()
        return (0 until count).map { i ->
            RenderSegment(i, i * segmentMs, minOf((i + 1) * segmentMs, durationMs))
        }
    }

    // ---- per-segment analysis ----

    /** What one segment's analysis pass produced. Times are absolute source ms, never segment-relative. */
    class Analysis(val firingsMs: List<Long>, val edl: Edl)

    fun analysisFile(dir: File, index: Int): File = File(dir, "an-%03d.json".format(index))

    /** Null when this segment has not been analyzed yet (or its write never completed). */
    fun readAnalysis(dir: File, index: Int): Analysis? = runCatching {
        val root = JSONObject(analysisFile(dir, index).readText())
        val fj = root.getJSONArray("firingsMs")
        Analysis(
            firingsMs = (0 until fj.length()).map { fj.getLong(it) },
            edl = Edl.fromJson(root.getJSONObject("edl").toString()),
        )
    }.getOrNull()

    fun writeAnalysis(dir: File, index: Int, firingsMs: List<Long>, edl: Edl) {
        val root = JSONObject()
            .put("firingsMs", JSONArray().also { a -> firingsMs.forEach { a.put(it) } })
            .put("edl", JSONObject(edl.toJson()))
        atomicWrite(analysisFile(dir, index), root.toString())
    }

    // ---- per-segment render ----

    fun segmentFile(dir: File, index: Int): File = File(dir, "seg-%03d.mp4".format(index))

    /** True once this segment's export finished and was renamed into place. */
    fun isRendered(dir: File, index: Int): Boolean = segmentFile(dir, index).length() > 0

    // ---- audio ----

    /**
     * How far the separator got, plus the whole-track scalars a resumed run must NOT recompute: [mean] and
     * [std] normalize on feed and are inverted on emit, so re-deriving them from a re-decode that differs
     * by one frame would step the level in the middle of the film; [totalFrames] fixes the chunk grid.
     */
    class AudioProgress(
        val framesEmitted: Long,
        val stats: AudioDecoder.Stats,
    )

    private fun audioFile(dir: File) = File(dir, "audio.json")

    fun readAudio(dir: File): AudioProgress? = runCatching {
        val o = JSONObject(audioFile(dir).readText())
        AudioProgress(
            framesEmitted = o.getLong("framesEmitted"),
            // getDouble().toFloat() round-trips a Float exactly, so the scalars survive verbatim.
            stats = AudioDecoder.Stats(
                frames = o.getLong("frames"),
                mean = o.getDouble("mean").toFloat(),
                std = o.getDouble("std").toFloat(),
                firstPtsUs = o.getLong("firstPtsUs"),
            ),
        )
    }.getOrNull()

    fun writeAudio(dir: File, framesEmitted: Long, stats: AudioDecoder.Stats) {
        atomicWrite(
            audioFile(dir),
            JSONObject()
                .put("framesEmitted", framesEmitted)
                .put("frames", stats.frames)
                .put("mean", stats.mean.toDouble())
                .put("std", stats.std.toDouble())
                .put("firstPtsUs", stats.firstPtsUs)
                .toString(),
        )
    }

    /**
     * Write via a temp + rename, so the final name only ever exists over complete content. Rename within
     * one directory is atomic on every filesystem Android uses.
     */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // renameTo won't overwrite on some filesystems; a checkpoint rewrite is the only case.
            target.delete()
            check(tmp.renameTo(target)) { "could not rename ${tmp.name} to ${target.name}" }
        }
    }
}
