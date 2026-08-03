package com.haithamassoli.naqi.analysis

/**
 * Shared M1 analysis types. Pure Kotlin — no Android imports, so gate/EDL logic stays
 * JVM-unit-testable. All rectangles are normalized to [0,1] in UPRIGHT (display-oriented)
 * frame space: the sampler hands ML Kit an unrotated buffer plus the source rotation and ML Kit
 * reports boxes in that rotated (upright) space — normalized against
 * [FrameSampler.uprightSize] — and Media3's effect pipeline hands effects upright frames, so both
 * passes share one coordinate space.
 */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /**
     * Map this upright-space rect into STORED-frame space for a video whose stored frames must be
     * rotated [rotationDegrees] clockwise for display (Android's rotation convention). Media3's
     * effect pipeline hands effects stored-orientation frames and forwards rotation metadata to the
     * muxer, so pass-2 region uniforms need this inverse mapping (identity for unrotated video).
     */
    fun toStoredSpace(rotationDegrees: Int): NRect = when (((rotationDegrees % 360) + 360) % 360) {
        90 -> NRect(top, 1f - right, bottom, 1f - left)
        180 -> NRect(1f - right, 1f - bottom, 1f - left, 1f - top)
        270 -> NRect(1f - bottom, left, 1f - top, right)
        else -> this
    }
}

/** Source video properties (probe result). [width]/[height] are as stored, pre-rotation. */
data class VideoMeta(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val durationMs: Long,
    val fps: Float,
)

/** One face observation on one sampled frame. */
data class FaceSample(val ptsMs: Long, val rect: NRect)

/**
 * A face tracked across sampled frames. [id] is the ML Kit tracking id, or a negative synthetic one
 * for a detection ML Kit could not assign an id to (see [FaceTracker]); it groups samples, and — when
 * the user picked Women or Men — carries the gender vote that decides whether this track is censored
 * at all (plan-censor-who §4.1). Under Everyone/Off nothing below moves off zero.
 *
 * The load-bearing property of this shape: the verdict is **four ints, not crop bitmaps**. The NudeNet
 * implementation plan-v2 §5.4 removed held every crop it classified for the whole pass — ~500 MB on a
 * feature-length film — and that retention, not the classifier, is the failure this layout exists to
 * avoid. Each crop is classified inside the detector callback and dropped; only the tallies survive.
 */
class FaceTrack(val id: Int) {
    val samples = mutableListOf<FaceSample>()

    // ponytail: four counters, not crops. That is the 500 MB fix.
    var femaleVotes = 0
    var maleVotes = 0

    /** Largest crop (max side, upright px) already classified in this track; 0 = none yet. §4.2. */
    var classifiedPx = 0

    /** Classifications RUN, abstentions included — the `VOTE_CAP` counter, so cost is per-track. */
    var votesTried = 0
}
