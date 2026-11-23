package com.haithamassoli.naqi.desktop.media

import com.haithamassoli.naqi.analysis.VideoMeta
import java.io.File

// Owner: the media agent (JavaCPP FFmpeg). The signatures below are the contract in apps/windows/SEAMS.md;
// change one only together with that file and its callers.

/**
 * One decoded picture: packed RGB24 (R, G, B bytes), row-major, stride exactly `width * 3`.
 * [ptsMs] is the frame's own timestamp (`best_effort_timestamp`, rescaled from the stream time base and
 * floored to ms, relative to the video stream's start time), never a frame counter. Both [VideoDecoder.sample]
 * and [Renderer.render] use that same clock, which is what lets an EDL built from one drive the other.
 *
 * The decoder may reuse [rgb] for the next frame: a frame is valid only inside the callback it is passed to.
 */
class RgbFrame(val width: Int, val height: Int, val rgb: ByteArray, val ptsMs: Long)

object Probe {
    /** Container metadata without decoding: stored size, rotation (clockwise degrees), duration, fps (30 if absent). */
    fun probe(input: File): VideoMeta = TODO("media")
}

object VideoDecoder {
    /**
     * Decode the first video stream and call [onFrame] once per sample slot, Android's `FrameSampler` rule:
     * slots are `1000 / fps` ms apart, anchored to the first decoded frame; a frame is emitted when its
     * pts ≥ the next slot, after which the slot advances one interval, or to `pts + interval` after a gap.
     * Frames are UPRIGHT (rotation applied) and scaled so the longer side is at most [maxDim] (never up).
     * Suspends while [onFrame] runs, so the consumer sets the pace; cancellable.
     */
    suspend fun sample(
        input: File,
        fps: Float = 10f,
        maxDim: Int = 640,
        onFrame: suspend (RgbFrame) -> Unit,
    ): Unit = TODO("media")
}

object AudioDecoder {
    /**
     * Decode the first audio stream to 44 100 Hz stereo float, interleaved L R L R, nominally in [-1, 1],
     * and hand it over in batches of [frames] sample frames (`interleaved.size >= frames * 2`; the array may
     * be reused after the call returns). Starts at the stream's first sample. Returns false, without calling
     * [onPcm], when the file has no audio stream. Cancellable.
     */
    suspend fun decode(input: File, onPcm: suspend (interleaved: FloatArray, frames: Int) -> Unit): Boolean =
        TODO("media")
}

/** Writes 44.1 kHz stereo float PCM as AAC-LC 192 kbps into an .m4a, in the order given. */
class AacWriter(output: File) : AutoCloseable {
    fun write(interleaved: FloatArray, frames: Int): Unit = TODO("media")
    override fun close(): Unit = TODO("media")
}

/** What the output's audio is. */
sealed interface AudioOut {
    /** The source's audio stream, stream-copied. */
    data object Keep : AudioOut
    /** This .m4a (from [AacWriter]) instead, stream-copied and placed at the source audio's start time. */
    data class Replace(val m4a: File) : AudioOut
    /** No audio stream. */
    data object Drop : AudioOut
}

object Renderer {
    /**
     * Write [output] as MP4. With [edit] null the video stream is copied untouched; otherwise every frame is
     * decoded full size in STORED orientation (no rotation applied), passed to [edit] to change in place, and
     * encoded with libx264 (yuv420p) keeping that frame's original pts. The rotation metadata is carried to
     * the output either way, so it plays upright.
     *
     * [input] is opened read-only. [output] must not be [input] (the same file by canonical path → throws
     * IllegalArgumentException); it is written to a temp file in its own directory and atomically renamed
     * over [output] only when complete, so a failure or cancel leaves no partial file and no damaged
     * original. [onProgress] gets 0..100.
     */
    suspend fun render(
        input: File,
        output: File,
        audio: AudioOut,
        edit: ((RgbFrame) -> Unit)?,
        onProgress: (Int) -> Unit,
    ): Unit = TODO("media")
}
