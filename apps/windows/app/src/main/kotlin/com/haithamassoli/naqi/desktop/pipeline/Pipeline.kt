package com.haithamassoli.naqi.desktop.pipeline

import com.haithamassoli.naqi.analysis.NsfwGate
import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.desktop.audio.MusicRemoval
import com.haithamassoli.naqi.desktop.media.AudioOut
import com.haithamassoli.naqi.desktop.media.Probe
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.desktop.media.Renderer
import com.haithamassoli.naqi.desktop.media.VideoDecoder
import com.haithamassoli.naqi.desktop.vision.Censor
import com.haithamassoli.naqi.desktop.vision.FaceAnalyzer
import com.haithamassoli.naqi.desktop.vision.NsfwClassifier
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.edl.promoteFacesToFullFrame
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

// The integration of media, vision and audio, after Android's FilterWorker (its unsegmented shapes). Contract
// in apps/windows/SEAMS.md.

/**
 * One job. [ops] is core's FilterOps, the same type Android queues, so options mean the same on both. Body
 * modes (`bodyBlur`, `bodyPrecise`) are not on Windows yet and are ignored; the UI never sets them.
 */
data class JobOptions(val input: File, val output: File, val ops: FilterOps)

/** What a running job is doing, for the UI's label (Android's stage_* strings). */
enum class Stage { ANALYZE, MUSIC, RENDER }

object Pipeline {
    /**
     * Pass 1: `VideoDecoder.sample` at 10 fps, every frame to [FaceAnalyzer] when faces are censored and every
     * second one (5 fps) to [NsfwClassifier] when the gate is on. Then the gate's hysteresis over the whole
     * timeline, plus every censored face span promoted to the whole frame under `wholeFrameBlur`.
     */
    suspend fun analyze(input: File, meta: VideoMeta, ops: FilterOps, onProgress: (Int) -> Unit): Edl {
        val faces = if (ops.censorFaces) FaceAnalyzer(ops.censorWho) else null
        val nsfw = if (ops.censorNsfw) NsfwClassifier() else null
        try {
            val firings = ArrayList<Long>()
            var n = 0
            var lastPct = -1
            VideoDecoder.sample(input, fps = 10f, maxDim = 640) { frame ->
                currentCoroutineContext().ensureActive()
                faces?.onFrame(frame)
                if (nsfw != null && n % 2 == 0 && NsfwGate.fires(nsfw.classify(frame), ops.strictness)) {
                    firings += frame.ptsMs
                }
                n++
                if (meta.durationMs > 0) {
                    val pct = (frame.ptsMs * 100 / meta.durationMs).toInt().coerceIn(0, 99)
                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                }
            }
            val tracks = faces?.finish().orEmpty()
            // Android's correctness item 7.2: an unknown duration must not clamp every interval to [0, 0].
            val gate = NsfwGate.intervals(firings, if (meta.durationMs > 0) meta.durationMs else Long.MAX_VALUE)
            val intervals = if (ops.wholeFrameBlur) promoteFacesToFullFrame(gate, tracks) else gate
            onProgress(100)
            return Edl(intervals, tracks)
        } finally {
            faces?.close()
            nsfw?.close()
        }
    }

    /**
     * Pass 2: [Renderer.render] with the separated audio in place of the source's when there is some, and an
     * edit that censors each frame per the EDL. The EDL is upright and the renderer's frames are stored, so
     * the face rects are mapped `toStoredSpace` (whole-frame spans need no mapping). An empty EDL is a stream
     * copy: nothing is re-encoded that nothing changes.
     */
    suspend fun render(job: JobOptions, meta: VideoMeta, edl: Edl, music: File?, onProgress: (Int) -> Unit) {
        val ops = job.ops
        val edit: ((RgbFrame) -> Unit)? = if (edl.censorIntervalsMs.isEmpty() && edl.faceTracks.isEmpty()) {
            null
        } else { frame ->
            Censor.apply(
                frame,
                edl.regionsAt(frame.ptsMs).map { it.toStoredSpace(meta.rotationDegrees) },
                edl.fullFrameAt(frame.ptsMs),
                ops.blurAmount,
                ops.grayscale,
                ops.solidColor,
            )
        }
        Renderer.render(job.input, job.output, if (music != null) AudioOut.Replace(music) else AudioOut.Keep, edit, onProgress)
    }

    /** The whole job: probe, analyze, remove music, render. Cancel the coroutine to cancel the job. */
    suspend fun run(job: JobOptions, onProgress: (Stage, Int) -> Unit) {
        val ops = job.ops
        // The renderer refuses this too, but only after music removal has spent minutes on it.
        require(job.input.canonicalFile != job.output.canonicalFile) { "The output is the input file; the original is never overwritten" }
        val meta = Probe.probe(job.input)
        val edl = if (ops.censorFaces || ops.censorNsfw) {
            analyze(job.input, meta, ops) { onProgress(Stage.ANALYZE, it) }
        } else {
            Edl(emptyList(), emptyList())
        }
        var music: File? = null
        try {
            if (ops.removeMusic) {
                // Next to the output, like the renderer's own temp file, so all of a job's scratch is on the
                // disk it writes to and goes away with it.
                val dir = job.output.absoluteFile.parentFile ?: throw IOException("no directory for ${job.output}")
                val m4a = File.createTempFile(".${job.output.name}.", ".music.m4a", dir)
                music = m4a
                // No audio stream: nothing to separate, and the render below then has no audio to keep.
                if (!MusicRemoval.run(job.input, ops.keepStems, m4a, meta.durationMs) { onProgress(Stage.MUSIC, it) }) {
                    m4a.delete()
                    music = null
                }
            }
            render(job, meta, edl, music) { onProgress(Stage.RENDER, it) }
        } finally {
            music?.delete()
        }
    }
}
