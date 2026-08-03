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
 * for a detection ML Kit could not assign an id to (see [FaceTracker]); it groups samples and nothing
 * else. Every track is censored — plan-v2 §5.4 removed the gender vote, so there is no per-track
 * verdict left to carry.
 */
class FaceTrack(val id: Int) {
    val samples = mutableListOf<FaceSample>()
}
