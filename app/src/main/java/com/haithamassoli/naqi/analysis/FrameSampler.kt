package com.haithamassoli.naqi.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.haithamassoli.naqi.media.requireTrackIndex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * M1 pass-1 frame sampler. ONE sequential decode-only pass (no per-frame seeking) feeds both
 * analysis consumers: [sample] emits an upright, downscaled ARGB_8888 bitmap per sample slot at
 * [fps] (the caller runs face detection on every emitted frame and the NSFW gate on every 2nd,
 * i.e. 5 fps). Decode uses the ByteBuffer path (COLOR_FormatYUV420Flexible + [MediaCodec.getOutputImage])
 * with a hand-rolled YUV420 -> RGB convert that downscales by nearest-neighbour and bakes
 * [VideoMeta.rotationDegrees] into the bitmap, so every consumer sees upright pixels and every
 * [NRect] lives in one upright coordinate space.
 *
 * Timestamps cross the MediaCodec/Media3 (µs) <-> analysis (ms) boundary here and only here.
 */
object FrameSampler {

    private const val TIMEOUT_US = 10_000L

    // perf-plan 1.3b. [RING] must stay above the frames the consumer side can be holding — [QUEUE]
    // queued plus the one it is reading — or the decoder would overwrite pixels still being read.
    private const val QUEUE = 2
    private const val RING = QUEUE + 2

    /**
     * Container metadata without decoding. [VideoMeta.fps] falls back to 30 when the track omits a
     * frame rate; [VideoMeta.width]/[VideoMeta.height] are the pre-rotation display size.
     */
    fun probe(context: Context, uri: Uri): VideoMeta {
        val extractor = MediaExtractor()
        val mmr = MediaMetadataRetriever()
        try {
            extractor.setDataSource(context, uri, null)
            val format = extractor.getTrackFormat(extractor.requireTrackIndex("video/"))
            mmr.setDataSource(context, uri)
            // MMR is the reliable rotation/duration source; the track format is the fallback.
            val rotation = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
                ?: format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0).let { ((it % 360) + 360) % 360 }
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: format.longOrNull(MediaFormat.KEY_DURATION)?.div(1000) ?: 0L
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) frameRate(format) else 30f
            return VideoMeta(displayWidth(format), displayHeight(format), rotation, durationMs, fps)
        } finally {
            extractor.release()
            mmr.release()
        }
    }

    /**
     * Sequentially decode the first video track and emit one upright ARGB_8888 bitmap per sample
     * slot (slots spaced `1000/fps` ms, anchored to the first decoded frame — so a video shorter
     * than one slot still emits its first frame). Non-sampled frames are released unconverted.
     * Honours coroutine cancellation and releases the codec + extractor on any exit.
     *
     * Decode+convert runs on its own coroutine behind a [Channel] (perf-plan 1.3b) so it overlaps the
     * consumer's inference instead of taking turns with it; [sample] still returns only once the whole
     * pass is consumed. The bitmap handed to [onFrame] is one of [RING] the sampler cycles: valid for
     * that call only, overwritten a few frames later, so a consumer keeping pixels must copy them.
     *
     * @param startMs/[endMs] restrict the pass to `[startMs, endMs)` for a Phase 2 segment; the defaults
     *   cover the whole track and reproduce the M1 pass byte for byte. A window seeks to the preceding
     *   sync sample and discards frames below [startMs], and anchors the sample grid to [startMs] rather
     *   than to the first decoded frame — so segment N samples exactly the same timestamps whether it runs
     *   in a fresh job or after a resume, which is what makes a per-segment checkpoint reproducible.
     */
    suspend fun sample(
        context: Context,
        uri: Uri,
        fps: Float = 10f,
        maxDim: Int = 640,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
        onFrame: suspend (bitmap: Bitmap, ptsMs: Long) -> Unit,
    ) {
        val rotation = probe(context, uri).rotationDegrees
        val slotIntervalUs = (1_000_000f / fps).toLong().coerceAtLeast(1L)
        val windowed = startMs > 0L || endMs != Long.MAX_VALUE
        val startUs = startMs * 1000
        val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000

        // The reused output bitmaps plus the single scratch every convert fills before copying it into the
        // current slot — only the decode coroutine touches either, and it finishes a frame before starting
        // the next. maxDim² holds any output (the convert downscales only). Nothing recycles the rotation:
        // it dies with the pass, and a sweep could only race a reader still going — ML Kit's Task, when
        // onFrame throws before awaiting it.
        // ponytail: one decode coroutine, so the floor is now the single-threaded convert, ~41 ms/frame on
        // an S23. Splitting THAT across cores is the next lever, and a much bigger change than this one.
        val ring = arrayOfNulls<Bitmap>(RING)
        val scratch = IntArray(maxDim * maxDim)
        coroutineScope {
            val frames = Channel<Pair<Bitmap, Long>>(QUEUE)
            // The CONSUMER is the child and the decode loop stays in this coroutine, so a throw out of
            // onFrame (ORT dying mid-pass) cancels the loop rather than leaving it parked in send(). The
            // cancel() covers the one case that would not: a CancellationException thrown BY onFrame
            // completes this child quietly, without cancelling the scope the decode loop is checking.
            launch { try { for ((bmp, ptsMs) in frames) onFrame(bmp, ptsMs) } finally { frames.cancel() } }

            var slot = 0
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                val trackIndex = extractor.requireTrackIndex("video/")
                extractor.selectTrack(trackIndex)
                // Decoding has to start at a sync sample at or before the window, and the frames between it
                // and startMs are decoded but never emitted (they are the reference frames the window needs).
                if (startMs > 0L) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val format = extractor.getTrackFormat(trackIndex)
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatYUV420Flexible)
                // perf-plan 1.4 proposed KEY_OPERATING_RATE=MAX_VALUE + KEY_PRIORITY=1 here ("ask the decoder
                // to run flat out"). Tried and REMOVED on 2026-07-28: measured -0.5 % on an S23 analyze pass
                // (9 985 -> 9 938 ms, i.e. noise). 1.2 explains why — the 53 % "decode+convert" share is
                // dominated by the Kotlin per-pixel convert below, which no decoder hint can touch. Re-try only
                // on a device where the DECODE half is shown to be the expensive one.
                codec.configure(format, null, null, 0)
                codec.start()

                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                // Windowed: the grid is anchored to the window start, so a segment is reproducible.
                // Unwindowed: anchored to the first decoded frame's pts, exactly as M1 did.
                var nextSlotUs = if (windowed) startUs else Long.MIN_VALUE

                while (!outputDone) {
                    coroutineContext.ensureActive() // cooperative cancel: throws when the caller cancels

                    if (!inputDone) {
                        val inIndex = codec.dequeueInputBuffer(0) // non-blocking; drain output when full
                        if (inIndex >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex < 0) continue // TRY_AGAIN_LATER / *_FORMAT_CHANGED / *_BUFFERS_CHANGED

                    val ptsUs = info.presentationTimeUs
                    if (nextSlotUs == Long.MIN_VALUE && info.size > 0) nextSlotUs = ptsUs
                    // Past the window: stop. Decode order is not display order, so this trusts the sample's
                    // own pts rather than assuming the stream reached the end.
                    if (info.size > 0 && ptsUs >= endUs) {
                        codec.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    val render = info.size > 0 && ptsUs >= nextSlotUs
                    val bitmap = if (render) {
                        codec.getOutputImage(outIndex)?.let { image ->
                            try { toUprightBitmap(image, rotation, maxDim, ring[slot], scratch) }
                            finally { image.close() }
                        }?.also { ring[slot] = it } // first RING frames fill the rotation, the rest reuse it
                    } else null
                    if (render) {
                        nextSlotUs += slotIntervalUs
                        if (nextSlotUs <= ptsUs) nextSlotUs = ptsUs + slotIntervalUs // resync after a gap
                    }
                    codec.releaseOutputBuffer(outIndex, false) // release before handing the frame over

                    if (bitmap != null) {
                        frames.send(bitmap to ptsUs / 1000) // µs -> ms; parks here when the consumer is behind
                        slot = (slot + 1) % RING
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            } finally {
                frames.close() // ends the consumer's loop on ANY exit, cancel included
                codec?.let {
                    runCatching { it.stop() } // stop() throws if the codec already errored; release regardless
                    it.release()
                }
                extractor.release()
            }
        }
    }

    /** KEY_FRAME_RATE is stored as a Float on some devices and an Integer on others. */
    private fun frameRate(format: MediaFormat): Float =
        try { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
        catch (_: ClassCastException) { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }

    // MediaFormat crop keys are inclusive pixel indices (right/bottom are the last valid column/row).
    private fun displayWidth(format: MediaFormat): Int =
        if (format.containsKey("crop-left") && format.containsKey("crop-right"))
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        else format.getInteger(MediaFormat.KEY_WIDTH)

    private fun displayHeight(format: MediaFormat): Int =
        if (format.containsKey("crop-top") && format.containsKey("crop-bottom"))
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        else format.getInteger(MediaFormat.KEY_HEIGHT)

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.longOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null

    /**
     * Convert one decoded [Image] (COLOR_FormatYUV420Flexible, 3 planes with independent row/pixel
     * strides) to an upright ARGB_8888 bitmap, downscaling by nearest-neighbour and applying
     * [rotation] in the same pixel walk. [Image.getCropRect] (exclusive-right [android.graphics.Rect])
     * bounds the valid region. Integer BT.601 full-range YUV -> RGB.
     *
     * Writes through the caller's [pixels] store into [reuse] when that bitmap already has the output
     * shape, so a pass allocates [RING] bitmaps instead of one per frame; a mismatch allocates one.
     */
    private fun toUprightBitmap(image: Image, rotation: Int, maxDim: Int, reuse: Bitmap?, pixels: IntArray): Bitmap {
        val crop = image.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val longest = maxOf(cw, ch)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f // downscale only, never up
        val dispW = maxOf(1, (cw * scale).roundToInt())
        val dispH = maxOf(1, (ch * scale).roundToInt())

        // Display (unrotated) coordinate -> source luma index, nearest-neighbour, crop offset baked in.
        val sxMap = IntArray(dispW) { crop.left + it * cw / dispW }
        val syMap = IntArray(dispH) { crop.top + it * ch / dispH }

        val swap = rotation == 90 || rotation == 270
        val outW = if (swap) dispH else dispW
        val outH = if (swap) dispW else dispH

        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yBuf = yP.buffer; val yRow = yP.rowStride; val yPix = yP.pixelStride; val yBase = yBuf.position()
        val uBuf = uP.buffer; val uRow = uP.rowStride; val uPix = uP.pixelStride; val uBase = uBuf.position()
        val vBuf = vP.buffer; val vRow = vP.rowStride; val vPix = vP.pixelStride; val vBase = vBuf.position()

        for (oy in 0 until outH) {
            val row = oy * outW
            for (ox in 0 until outW) {
                val dx: Int
                val dy: Int
                when (rotation) { // upright output -> unrotated display coordinate
                    90 -> { dx = oy; dy = dispH - 1 - ox }
                    180 -> { dx = dispW - 1 - ox; dy = dispH - 1 - oy }
                    270 -> { dx = dispW - 1 - oy; dy = ox }
                    else -> { dx = ox; dy = oy }
                }
                val sx = sxMap[dx]
                val sy = syMap[dy]
                val cx = sx shr 1 // chroma is 4:2:0 subsampled: luma (sx,sy) -> chroma (sx/2,sy/2)
                val cy = sy shr 1
                val y = yBuf.get(yBase + sy * yRow + sx * yPix).toInt() and 0xFF
                val u = (uBuf.get(uBase + cy * uRow + cx * uPix).toInt() and 0xFF) - 128
                val v = (vBuf.get(vBase + cy * vRow + cx * vPix).toInt() and 0xFF) - 128
                val r = (y + ((1436 * v) shr 10)).coerceIn(0, 255)
                val g = (y - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
                val b = (y + ((1815 * u) shr 10)).coerceIn(0, 255)
                pixels[row + ox] = -0x1000000 or (r shl 16) or (g shl 8) or b // opaque ARGB
            }
        }
        // createBitmap(pixels, ...) would allocate AND return an immutable bitmap; the rotation needs a
        // mutable one it can refill, and setPixels copies, so the store above is free to be reused.
        val out = reuse?.takeIf { it.width == outW && it.height == outH }
            ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        return out
    }
}
