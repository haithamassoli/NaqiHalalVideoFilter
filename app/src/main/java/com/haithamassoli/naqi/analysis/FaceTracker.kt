package com.haithamassoli.naqi.analysis

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.haithamassoli.naqi.edl.FaceTrackEdl

/**
 * M1 pass-1 face tracking. The caller feeds sampled frames at 10 fps — [detect] starts ML Kit
 * (bundled, fast, tracking ON) and [onFaces] consumes what it found — grouping detections into
 * [FaceTrack]s and turning each into a [FaceTrackEdl] as it ends; [finish] only drains whatever is
 * still live.
 *
 * **Every detected face is censored** (plan-v2 §5.4, "V4"). The NudeNet gender vote is gone: it was
 * AGPL-3.0 inside a closed-source APK, it held ~500 MB of crop bitmaps through the pass, and it did
 * not do what it claimed — `m0-spikes.md:35` records `FACE_FEMALE` firing 0.69–0.83 on male portraits
 * while `FACE_MALE` stayed ≤0.07, so the vote already censored approximately every face. Tracking ids
 * therefore no longer decide WHETHER to blur, only how samples group into spans.
 *
 * Coordinates: ML Kit `boundingBox` is pixels in the UPRIGHT frame (ML Kit rotates the sampler's
 * unrotated buffer itself), so [onFaces] normalizes by the upright width/height the sampler reports —
 * see [FrameSampler.uprightSize]. Every [NRect] here lives in that one space ([Contracts]).
 * Timestamps are MILLIseconds throughout.
 *
 * **Retention (`long-film-plan.md` Phase 1).** Tracks leave as soon as they are over — unseen for
 * [EVICT_AFTER_MS] ⇒ emit the EDL span, drop it from the live map — so a 155-min film holds a handful
 * of live tracks instead of the 3 362 a never-evicting map reached.
 *
 * Not thread-safe: one frame's [detect]→[onFaces] must complete before the next frame's starts, and
 * neither may overlap [closeDetector] — the caller drives frames sequentially on one background
 * dispatcher and blocks there on `Tasks.await` (which is why no coroutines-play-services dep is
 * needed).
 */
class FaceTracker {

    /** Live tracks keyed by tracking id — only those still being seen. */
    private val tracks = LinkedHashMap<Int, FaceTrack>()

    /** EDLs of tracks already evicted, in eviction order. */
    private val emittedEdls = mutableListOf<FaceTrackEdl>()

    /** Lazily created so it survives across frames (tracking ids persist only within one detector). */
    private var detector: FaceDetector? = null

    /**
     * Ids for detections ML Kit gave no tracking id to, counting DOWN from -1 so they can never
     * collide with a real ML Kit id (which are positive and monotonically increasing).
     */
    private var nextSyntheticId = -1

    /**
     * Retention + sizing counters for the soak log. [trackCount] is CUMULATIVE so it stays comparable
     * to the 3 362 measured before eviction existed. [faceCount]/[untrackedCount] are plan-v2 §7.1's
     * "size it first": how many raw detections the pass saw, and how many of them ML Kit could not
     * assign a tracking id to — the ones the old `?: continue` silently never censored. That number
     * has never been measured; it is expected to spike at scene cuts and fast pans.
     */
    var trackCount: Int = 0
        private set
    var faceCount: Int = 0
        private set
    var untrackedCount: Int = 0
        private set

    /**
     * The retention line for the soak log. Read it whenever — including from a `finally` after a cancel
     * mid-pass, which is the only way these numbers ever surface on a multi-hour job that gets stopped.
     */
    fun retention(): String =
        "tracks=$trackCount faces=$faceCount untracked=$untrackedCount liveTracks=${tracks.size}"

    /**
     * Start ML Kit and hand the Task back un-awaited (perf-plan 1.3a): it runs on ML Kit's own executor,
     * so the caller gets the NSFW gate for free by awaiting only after running it. The caller must await
     * before [image]'s backing buffer is reused — ML Kit reads it until the Task completes, and
     * [FrameSampler] hands out ring buffers that are valid for one callback only.
     */
    fun detect(image: InputImage): Task<List<Face>> = detector().process(image)

    /**
     * Group [faces] into tracks, then sweep. [uprightW]/[uprightH] are the frame dimensions ML Kit
     * reported boxes against — NOT the buffer's own, which are the swapped pair for a 90/270 source.
     */
    fun onFaces(faces: List<Face>, uprightW: Int, uprightH: Int, ptsMs: Long) {
        val w = uprightW.toFloat()
        val h = uprightH.toFloat()
        faceCount += faces.size
        for (face in faces) {
            // plan-v2 §7.1: this was `val id = face.trackingId ?: continue`, which silently DROPPED any
            // detection ML Kit found but did not assign a tracking id to — never blurred, and the failure
            // is content-correlated (ML Kit's tracker is motion-based with no re-identification, so ids
            // are least likely to be populated exactly at scene cuts and fast pans, and we sample 10 fps
            // out of 23.976 which is ~2.4x the per-step motion it was built for). An untracked face now
            // becomes its own one-frame track and is censored like any other; normal eviction closes it.
            val id = face.trackingId ?: run { untrackedCount++; nextSyntheticId-- }
            val box = face.boundingBox
            // A reused tracking id after eviction just starts a fresh track, which yields two EDL spans
            // covering their own samples — coverage is preserved either way.
            val track = tracks.getOrPut(id) { trackCount++; FaceTrack(id) }
            track.samples += FaceSample(ptsMs, NRect(box.left / w, box.top / h, box.right / w, box.bottom / h))
        }
        sweep(ptsMs)
    }

    /**
     * Evict the tracks that are over. O(live tracks) per frame, and eviction is exactly what keeps that
     * count small — on the measured film it is a handful, against the 3 362 a never-evicting map reached.
     */
    private fun sweep(nowMs: Long) {
        val iter = tracks.entries.iterator()
        while (iter.hasNext()) {
            val track = iter.next().value
            // A live track always holds at least the sample that created it, so last() cannot throw and
            // its pts IS "last seen" — no separate field to keep in sync with the samples.
            if (isStale(track.samples.last().ptsMs, nowMs)) {
                edlFor(track)?.let { emittedEdls += it }
                iter.remove()
            }
        }
    }

    /** Emit whatever is still live, then return every EDL the pass produced. */
    fun finish(): List<FaceTrackEdl> {
        tracks.values.forEach { track -> edlFor(track)?.let { emittedEdls += it } }
        tracks.clear()
        // Emission order is track-END order now that tracks leave as they finish, where M1 emitted in
        // first-detection order. Rendered pixels do not care (Edl.regionsAt unions the active rects and
        // CensorEffect re-sorts when it overflows), but sorting keeps Edl.toJson() diffable against the
        // M1-verified runs. Copy, so the returned Edl does not alias state this class keeps mutating.
        return emittedEdls.sortedBy { it.startMs }
    }

    /** Release the ML Kit detector. Idempotent. */
    fun closeDetector() {
        detector?.close()
        detector = null
    }

    private fun detector(): FaceDetector = detector ?: FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build(),
    ).also { detector = it }
}

/**
 * Source-time gap after which an unseen track is considered over. 2 s = 20 sampled frames at 10 fps:
 * long enough that a blink or a brief occlusion does not split one face into two tracks, short
 * enough that the live map stays a handful of tracks on a feature-length film.
 *
 * ponytail: one fixed gap for every video. A shot-length-aware value would split fewer tracks on
 * fast-cut content — worth doing only if QA ever shows a split costing coverage. (It no longer costs
 * a censor decision: since V4 both halves of a split track are censored anyway.)
 */
private const val EVICT_AFTER_MS = 2_000L

/** Half the 100 ms (10 fps) sample gap, so between-sample frames stay covered at a span's edges. */
private const val SPAN_PAD_MS = 50L

/** 25% per side ⇒ 1.5x each dimension (PRD: pad 25%). */
private const val KEYFRAME_PAD = 0.25f

/**
 * The span for one finished track: every track with samples produces one. Pure (no Android, no ML
 * Kit) so it is unit-testable — see `FaceTrackerLogicTest`. The empty-samples guard is the only
 * "return null" left; it cannot happen through [FaceTracker] (a track is created with its first
 * sample) and exists so a future caller cannot crash on `samples.first()`.
 */
internal fun edlFor(track: FaceTrack): FaceTrackEdl? {
    if (track.samples.isEmpty()) return null
    return FaceTrackEdl(
        startMs = (track.samples.first().ptsMs - SPAN_PAD_MS).coerceAtLeast(0L),
        endMs = track.samples.last().ptsMs + SPAN_PAD_MS,
        keyframes = track.samples.map { it.ptsMs to padRect(it.rect) },
    )
}

/** True once [nowMs] is [EVICT_AFTER_MS] past the last frame a track appeared in. */
internal fun isStale(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs >= EVICT_AFTER_MS

/** Pad a sample rect to 1.5x each dimension (25% per side) and clamp to [0,1] for the EDL keyframe. */
internal fun padRect(r: NRect): NRect {
    val dx = r.width * KEYFRAME_PAD
    val dy = r.height * KEYFRAME_PAD
    return NRect(
        (r.left - dx).coerceIn(0f, 1f),
        (r.top - dy).coerceIn(0f, 1f),
        (r.right + dx).coerceIn(0f, 1f),
        (r.bottom + dy).coerceIn(0f, 1f),
    )
}
