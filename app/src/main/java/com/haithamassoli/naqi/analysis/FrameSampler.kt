package com.haithamassoli.naqi.analysis

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.haithamassoli.naqi.media.requireTrackIndex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * M1 pass-1 frame sampler. ONE sequential decode-only pass (no per-frame seeking) feeds both
 * analysis consumers: [sample] emits, per sample slot at [fps], an [InputImage] for ML Kit and — on
 * every [gateEvery]'th slot only — a filled NSFW-gate tensor. Decode uses the ByteBuffer path
 * (COLOR_FormatYUV420Flexible + [MediaCodec.getOutputImage]).
 *
 * **No RGB bitmap is built any more (plan-v2 §5.1 "V1").** Until V4 removed NudeNet, every sampled
 * frame was walked into a 640-px upright ARGB bitmap, which ML Kit then converted BACK into its own
 * YUV format and the gate re-scaled to 224² and re-walked into a heap float buffer — three
 * conversions of 230 400 px, 93 240 times on a film (~26 min, 38 % of the analyze pass). The bitmap
 * existed only to crop faces for the gender vote. Now:
 *
 * - **ML Kit** gets [packNv21]'s output: the decoder's own planes repacked to NV21, downscaled to
 *   [maxDim] but NOT rotated — rotation is handed over as `rotationDegrees` instead. Byte moves only,
 *   no arithmetic.
 * - **The gate** gets [convertToTensor]: the decoder's planes walked straight into a reused DIRECT
 *   [FloatBuffer] at 224² NCHW, rotation applied in the same walk (plan-v2 §5.2 "V2").
 *
 * **Coordinate space — the one thing that must not regress.** ML Kit is given an UNROTATED buffer
 * plus a rotation, and it returns bounding boxes in the ROTATED (upright) space, whose dimensions are
 * the swapped ones for 90/270. That is why [sample] hands the consumer [uprightSize] rather than the
 * buffer's own width/height: every [NRect] in the EDL stays normalized to the same upright space
 * pass 2 reads (see [Contracts] and `render/CensorEffect.kt`).
 *
 * Timestamps cross the MediaCodec/Media3 (µs) <-> analysis (ms) boundary here and only here.
 */
object FrameSampler {

    private const val TAG = "FrameSampler"
    private const val TIMEOUT_US = 10_000L

    /** NSFW gate input side — the model's locked contract is `[1,3,224,224]` (see `ml/Models.kt`). */
    private const val GATE_SIDE = 224

    /** Face-gender crop side — genderage.onnx's locked contract is `[1,3,96,96]` (see `ml/Models.kt`). */
    internal const val CROP_SIDE = 96

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
     * Sequentially decode the first video track and emit one frame per sample slot (slots spaced
     * `1000/fps` ms, anchored to the first decoded frame — so a video shorter than one slot still
     * emits its first frame). Non-sampled frames are released unconverted. Honours coroutine
     * cancellation and releases the codec + extractor on any exit.
     *
     * Decode+convert runs on its own coroutine behind a [Channel] (perf-plan 1.3b) so it overlaps the
     * consumer's inference instead of taking turns with it; [sample] still returns only once the whole
     * pass is consumed. **Both buffers behind an [onFrame] call are one of [RING] the sampler cycles:
     * valid for that call only** (ML Kit reads the NV21 until its Task completes, so the consumer must
     * await inside the callback), overwritten a few frames later, so a consumer keeping either must copy.
     *
     * @param gateEvery fill the gate tensor on every N'th emitted frame; other frames get `null`. The
     *   cadence has to live on the PRODUCER side: filling the tensor reads the decoder's [Image], which
     *   is closed and its output buffer released before the frame reaches the consumer. 2 = the gate's
     *   5 fps against face detection's 10 (plan-v2 §5.3b would raise it). Not filling it on the other
     *   half of the frames is half of what V1 saves.
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
        gateEvery: Int = 2,
        startMs: Long = 0L,
        endMs: Long = Long.MAX_VALUE,
        onFrame: suspend (image: InputImage, gate: FloatBuffer?, uprightW: Int, uprightH: Int, ptsMs: Long) -> Unit,
    ) {
        // InputImage.fromByteBuffer REJECTS anything that is not 0/90/180/270 (IllegalArgumentException),
        // where the old bitmap path silently treated an unrecognised value as 0. A bizarrely authored
        // rotation must degrade to "unrotated", not kill a three-hour job.
        val rotation = probe(context, uri).rotationDegrees.let { if (it % 90 == 0) it else 0 }
        val gateStride = gateEvery.coerceAtLeast(1) // 0 would divide by zero below; 1 = "gate every frame"
        val slotIntervalUs = (1_000_000f / fps).toLong().coerceAtLeast(1L)
        val windowed = startMs > 0L || endMs != Long.MAX_VALUE
        val startUs = startMs * 1000
        val endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000

        // The reused output buffers — only the decode coroutine writes either, and it finishes a frame
        // before starting the next. Both are allocated on first use because their size comes from the
        // first decoded Image's crop rect, and DIRECT because both cross a native boundary: the NV21 into
        // ML Kit, the gate tensor into ORT (a heap FloatBuffer would be copied to native on every run —
        // plan-v2 §5.2). Nothing frees them: they die with the pass.
        // ponytail: one decode coroutine, one convert thread. perf-plan Phase 5 measured the old RGB loop
        // at 16-19 ms/frame, NOT the ~41 the plan assumed, and splitting it across cores moved analyze wall
        // by 0 % — the pass is consumer-bound on the NSFW gate. Do not optimize here again without first
        // making the gate cheaper; `nv21=`/`gateFill=` in the log below are the numbers that say so.
        val nv21Ring = arrayOfNulls<ByteBuffer>(RING)
        val gateRing = arrayOfNulls<FloatBuffer>(RING)
        coroutineScope {
            val frames = Channel<Frame>(QUEUE)
            // The CONSUMER is the child and the decode loop stays in this coroutine, so a throw out of
            // onFrame (ORT dying mid-pass) cancels the loop rather than leaving it parked in send(). The
            // cancel() covers the one case that would not: a CancellationException thrown BY onFrame
            // completes this child quietly, without cancelling the scope the decode loop is checking.
            launch {
                try {
                    for (f in frames) onFrame(f.image, f.gate, f.uprightW, f.uprightH, f.ptsMs)
                } finally {
                    frames.cancel()
                }
            }

            var slot = 0
            var emitted = 0
            // perf-plan Phase 5, and the reason it was rejected. 1.2 could only get decode+convert as a
            // residual, and after 1.3b even that stopped working (wall became max(producer, consumer)),
            // so the convert's real cost was never measured. Kept, and now SPLIT (plan-v2 §4.6): the gate
            // fill is the preprocessing that used to hide inside FilterWorker's `gate=`, so timing it apart
            // from the NV21 repack is what makes the remaining gate cost attributable to the model.
            var convertNs = 0L
            var gateNs = 0L
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
                // plan-v2 §5.1 is explicit that this request must NOT change: GraphicView2MediaImageConverter
                // tries to ALIAS the mapped gralloc planes with no copy and only falls back to libyuv when
                // aliasing fails, where a concrete layout like COLOR_FormatYUV420Planar would guarantee a
                // full-frame conversion on every Qualcomm NV12 component. Both walks below take the plane
                // strides as parameters precisely so they can consume whatever layout aliasing produces.
                // perf-plan 1.4 proposed KEY_OPERATING_RATE=MAX_VALUE + KEY_PRIORITY=1 here ("ask the decoder
                // to run flat out"). Tried and REMOVED on 2026-07-28: measured -0.5 % on an S23 analyze pass
                // (9 985 -> 9 938 ms, i.e. noise).
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
                    val frame = if (render) {
                        val t0 = System.nanoTime()
                        codec.getOutputImage(outIndex)?.let { image ->
                            try {
                                toFrame(
                                    image, rotation, maxDim, ptsUs / 1000, // µs -> ms
                                    wantGate = emitted % gateStride == 0,
                                    nv21Reuse = nv21Ring[slot], gateReuse = gateRing[slot],
                                )
                            } finally {
                                image.close()
                            }
                        }?.also {
                            // First RING frames fill the rings, the rest reuse them. The gate slot keeps its
                            // buffer on a non-gate frame, so the next gate frame in this slot reuses it.
                            nv21Ring[slot] = it.nv21
                            if (it.gate != null) gateRing[slot] = it.gate
                            convertNs += System.nanoTime() - t0
                            gateNs += it.gateFillNs
                        }
                    } else null
                    if (render) {
                        nextSlotUs += slotIntervalUs
                        if (nextSlotUs <= ptsUs) nextSlotUs = ptsUs + slotIntervalUs // resync after a gap
                    }
                    codec.releaseOutputBuffer(outIndex, false) // release before handing the frame over

                    if (frame != null) {
                        frames.send(frame) // parks here when the consumer is behind
                        slot = (slot + 1) % RING
                        emitted++
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            } finally {
                // plan-v2 §4.6: `gateFill=` is the preprocessing half of the gate. Its other half —
                // session.run — is FilterWorker's `gate=`, logged on the line right after this one.
                if (emitted > 0) Log.i(TAG, "sample: frames=$emitted " +
                    "nv21=${(convertNs - gateNs) / 1_000_000}ms gateFill=${gateNs / 1_000_000}ms")
                frames.close() // ends the consumer's loop on ANY exit, cancel included
                codec?.let {
                    runCatching { it.stop() } // stop() throws if the codec already errored; release regardless
                    it.release()
                }
                extractor.release()
            }
        }
    }

    /**
     * One sampled frame. Both buffers are ring-owned: valid for exactly one `onFrame` call.
     * [gateFillNs] rides along because only [toFrame] can see where the gate's preprocessing ends and
     * the NV21 repack begins, and §4.6 wants those apart.
     */
    private class Frame(
        val nv21: ByteBuffer,
        val image: InputImage,
        val gate: FloatBuffer?,
        val uprightW: Int,
        val uprightH: Int,
        val ptsMs: Long,
        val gateFillNs: Long,
    )

    /**
     * Everything one decoded [Image] turns into: the ML Kit input, and — when [wantGate] — the filled
     * gate tensor. [nv21Reuse]/[gateReuse] are this slot's ring buffers, reused when they still fit.
     */
    private fun toFrame(
        image: Image,
        rotation: Int,
        maxDim: Int,
        ptsMs: Long,
        wantGate: Boolean,
        nv21Reuse: ByteBuffer?,
        gateReuse: FloatBuffer?,
    ): Frame {
        val crop = image.cropRect // exclusive-right Rect bounding the valid region
        val cw = crop.width()
        val ch = crop.height()
        val longest = maxOf(cw, ch)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f // downscale only, never up
        // NV21 is 4:2:0, so ML Kit computes the plane sizes as w*h + w*h/2 — an odd dimension would make
        // that disagree with the bytes written. Round DOWN to even (`and 1.inv()`), floor 2.
        val dispW = maxOf(2, (cw * scale).roundToInt()) and 1.inv()
        val dispH = maxOf(2, (ch * scale).roundToInt()) and 1.inv()

        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yBuf = yP.buffer; val yRow = yP.rowStride; val yPix = yP.pixelStride; val yBase = yBuf.position()
        val uBuf = uP.buffer; val uRow = uP.rowStride; val uPix = uP.pixelStride; val uBase = uBuf.position()
        val vBuf = vP.buffer; val vRow = vP.rowStride; val vPix = vP.pixelStride; val vBase = vBuf.position()

        // --- ML Kit: unrotated NV21 at maxDim, rotation passed as metadata ---
        // Display (unrotated) coordinate -> source luma index, nearest-neighbour, crop offset baked in.
        val sxMap = IntArray(dispW) { crop.left + it * cw / dispW }
        val syMap = IntArray(dispH) { crop.top + it * ch / dispH }
        val nv21Size = dispW * dispH * 3 / 2
        val nv21 = nv21Reuse?.takeIf { it.capacity() == nv21Size } ?: ByteBuffer.allocateDirect(nv21Size)
        // BEFORE the pack, not after: an absolute put() is bounds-checked against limit(), and ML Kit
        // may have left this slot's buffer with a moved position/limit on its previous trip through.
        nv21.clear()
        packNv21(
            yBuf, yRow, yPix, yBase, uBuf, uRow, uPix, uBase, vBuf, vRow, vPix, vBase,
            sxMap, syMap, nv21,
        )
        // Downscaled, NOT rotated: ML Kit rotates internally and returns boxes upright, which costs nothing
        // here and deletes the rotation from the walk above. Downscaled because detect cost scales with
        // input area (~8.6 ms/frame at 640 px, plan-v2 §1) — handing over the source's own 1080p would
        // trade the whole of V1's saving for a slower detector.
        val (uprightW, uprightH) = uprightSize(dispW, dispH, rotation)
        val input = InputImage.fromByteBuffer(nv21, dispW, dispH, rotation, InputImage.IMAGE_FORMAT_NV21)

        // --- NSFW gate: straight from the same planes into a direct NCHW tensor, rotation applied here ---
        var gate: FloatBuffer? = null
        var gateFillNs = 0L
        if (wantGate) {
            val t0 = System.nanoTime()
            // The model input is a STRETCH to 224² (what Bitmap.createScaledBitmap(224, 224) used to do),
            // so both maps span the whole crop in GATE_SIDE steps and convertToTensor's rotation cases
            // index them against a square output.
            val gx = IntArray(GATE_SIDE) { crop.left + it * cw / GATE_SIDE }
            val gy = IntArray(GATE_SIDE) { crop.top + it * ch / GATE_SIDE }
            val buf = gateReuse ?: ByteBuffer.allocateDirect(3 * GATE_SIDE * GATE_SIDE * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            convertToTensor(
                yBuf, yRow, yPix, yBase, uBuf, uRow, uPix, uBase, vBuf, vRow, vPix, vBase,
                rotation, gx, gy, buf,
            )
            gate = buf
            gateFillNs = System.nanoTime() - t0
        }
        return Frame(nv21, input, gate, uprightW, uprightH, ptsMs, gateFillNs)
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
     * The upright frame size for a [width]x[height] buffer handed to ML Kit with [rotationDegrees]:
     * ML Kit rotates before detecting and reports `boundingBox` in that ROTATED space, so 90/270 swap
     * the axes. **This is the size every EDL [NRect] is normalized against**, and it is the whole of
     * what V1 changed about the coordinate space — the sampler used to bake the rotation into the
     * bitmap and normalize by the bitmap's own dimensions, which are these same swapped numbers.
     * Same space in, same space out, so `NRect.toStoredSpace` and `CensorEffect` are untouched.
     */
    internal fun uprightSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (((rotationDegrees % 360) + 360) % 360 % 180 == 90) height to width else width to height

    /**
     * Repack one decoded frame's planes into ML Kit's NV21: a full luma plane of `sxMap.size *
     * syMap.size` bytes, then half-resolution V,U pairs. [sxMap]/[syMap] are display coordinate ->
     * source luma index (crop offset baked in), so their sizes ARE the output width/height and the
     * nearest-neighbour downscale is entirely in them. Both must be EVEN.
     *
     * Every output byte is a source byte — no arithmetic, no clamping, nothing to get wrong about
     * colour. Both chroma layouts a `COLOR_FormatYUV420Flexible` decoder can produce are handled by
     * the same code with no branch: semi-planar (pixelStride 2, U and V interleaved in one buffer,
     * the Qualcomm NV12 case) and fully planar (pixelStride 1) differ only in the strides, which are
     * already parameters. Takes primitives only, no [Image], so `FrameSamplerConvertTest` needs no Robolectric.
     *
     * ponytail: no bulk-move fast path. A plane-sized `put(ByteBuffer)` needs BOTH `scale == 1` and
     * `rowStride == width`, and the caller always downscales (see [toFrame] — ML Kit's cost scales
     * with input area), so on real content the fast path is unreachable. The strided gather is ~345 kB
     * of byte moves per 1080p frame against the 230 400-iteration arithmetic loop it replaces. Add the
     * fast path only if a source already at or below `maxDim` shows up as a measurement.
     */
    internal fun packNv21(
        yBuf: ByteBuffer, yRow: Int, yPix: Int, yBase: Int,
        uBuf: ByteBuffer, uRow: Int, uPix: Int, uBase: Int,
        vBuf: ByteBuffer, vRow: Int, vPix: Int, vBase: Int,
        sxMap: IntArray, syMap: IntArray, out: ByteBuffer,
    ) {
        val w = sxMap.size
        val h = syMap.size
        for (oy in 0 until h) {
            val src = yBase + syMap[oy] * yRow
            val dst = oy * w
            for (ox in 0 until w) out.put(dst + ox, yBuf.get(src + sxMap[ox] * yPix))
        }
        // 4:2:0 chroma: one V,U pair per 2x2 output block, taken at the block's top-left source pixel —
        // the same `sx shr 1` subsampling the RGB walk uses, so both consumers see the same chroma.
        val plane = w * h
        for (cy in 0 until h / 2) {
            val sy = syMap[cy * 2] shr 1
            val dst = plane + cy * w
            for (cx in 0 until w / 2) {
                val sx = sxMap[cx * 2] shr 1
                out.put(dst + cx * 2, vBuf.get(vBase + sy * vRow + sx * vPix))     // NV21: V first,
                out.put(dst + cx * 2 + 1, uBuf.get(uBase + sy * uRow + sx * uPix)) // then U
            }
        }
    }

    /**
     * Walk one decoded frame's planes straight into [out] as the NSFW gate's `[1,3,224,224]` NCHW RGB
     * tensor, scaled 1/255 — no bitmap, no IntArray, no heap buffer anywhere (plan-v2 §5.1/§5.2).
     * [out] must be a DIRECT, native-order buffer of `3 * side²` floats; writes are ABSOLUTE, so its
     * position never moves and the reused `OnnxTensor` ORT holds over it stays valid.
     *
     * [sxMap]/[syMap] are display coordinate -> source luma index (crop offset baked in) and must be
     * the SAME length, `side`: the model input is a square stretch of the whole frame, exactly what
     * `Bitmap.createScaledBitmap(frame, 224, 224, true)` produced before, so each axis gets its own
     * scale factor and the output is `side x side` under every rotation.
     *
     * [rotation] is applied here, in the same walk, mapping upright output -> unrotated display
     * coordinate. Integer BT.601 full-range YUV -> RGB, coefficients UNCHANGED from the walk this
     * replaces: the gate's strictness thresholds are QA-tuned against these exact numbers.
     *
     * Takes primitives only, no [Image], so `FrameSamplerConvertTest` needs no Robolectric. Every buffer read
     * is ABSOLUTE (`get(index)`, never `get()`), so a plane's position is never disturbed.
     */
    internal fun convertToTensor(
        yBuf: ByteBuffer, yRow: Int, yPix: Int, yBase: Int,
        uBuf: ByteBuffer, uRow: Int, uPix: Int, uBase: Int,
        vBuf: ByteBuffer, vRow: Int, vPix: Int, vBase: Int,
        rotation: Int, sxMap: IntArray, syMap: IntArray, out: FloatBuffer,
    ) {
        val side = sxMap.size
        val plane = side * side
        for (oy in 0 until side) {
            val row = oy * side
            for (ox in 0 until side) {
                val dx: Int
                val dy: Int
                when (rotation) { // upright output -> unrotated display coordinate
                    90 -> { dx = oy; dy = side - 1 - ox }
                    180 -> { dx = side - 1 - ox; dy = side - 1 - oy }
                    270 -> { dx = side - 1 - oy; dy = ox }
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
                val i = row + ox
                out.put(i, r / 255f)              // R plane
                out.put(plane + i, g / 255f)      // G plane
                out.put(2 * plane + i, b / 255f)  // B plane
            }
        }
    }

    /**
     * Walk one face box out of the NV21 buffer ML Kit was handed into [out] as genderage.onnx's
     * `[1,3,96,96]` NCHW RGB tensor (plan-censor-who §4.1).
     *
     * **Why the NV21 and not a bitmap.** V1 deleted the 640-px ARGB bitmap that used to exist purely so
     * the old gender vote could crop faces out of it (see this object's header). Re-adding one — or
     * re-decoding the frame — would hand plan-v2 §5.1's whole saving back for a secondary feature. The
     * pixels are already in memory, unrotated, in the very buffer the detector read, so the crop is a
     * ~9 216-px gather against the NSFW gate's 50 176 per frame: noise next to the pass it rides on.
     *
     * **Coordinates.** [rect] is the UPRIGHT-normalized box `FaceTracker` stores (normalized by
     * [uprightSize], the same space as every EDL [NRect]); the buffer is the
     * UNROTATED [dispW]x[dispH] one. So this maps upright -> display with exactly the case table
     * [convertToTensor] uses, generalized off a square onto the two frame sides — at 90/270
     * `uprightW == dispH` and `uprightH == dispW`, which is what keeps `dx` inside [dispW].
     *
     * **The crop shape is InsightFace's, not `FaceTracker.padRect`'s.** The model trains on a SQUARE of side
     * `max(boxW, boxH) * 1.5` centred on the box centre, resized to 96² — a square, where the EDL's
     * padded rect grows each axis by 25 % independently. Same 1.5 factor, different shape; feeding it
     * the EDL rect would stretch every non-square face against what it saw in training.
     *
     * **Output is 0..255, NOT scaled by 1/255** — insightface's Attribute preprocessing is
     * `input_mean=0.0, input_std=1.0` (unlike the NSFW gate above, hence the two near-identical walks).
     * BT.601 coefficients are copied verbatim from [convertToTensor] so both consumers see one colour.
     *
     * [out] must be a DIRECT, native-order buffer of `3 * CROP_SIDE²` floats, reused for the whole
     * pass; every read and write is ABSOLUTE, so neither this buffer's position nor the NV21's moves
     * (ML Kit is still reading that one).
     *
     * ponytail: nearest-neighbour, and out-of-frame samples clamp to the edge pixel rather than the
     * black/reflected border the Python reference pads with — a face at the frame edge therefore gets a
     * smear of its own edge column instead of padding. Same simplification [packNv21]/[convertToTensor]
     * already make about sampling, and it only affects crops that are partly outside the frame. Revisit
     * only if §3.2's per-size-band numbers show edge crops failing specifically.
     */
    internal fun cropToTensor(
        nv21: ByteBuffer, dispW: Int, dispH: Int, rotation: Int,
        uprightW: Int, uprightH: Int, rect: NRect, out: FloatBuffer,
    ) {
        val half = maxOf(rect.width * uprightW, rect.height * uprightH) * 1.5f / 2f
        val x0 = (rect.left + rect.right) / 2f * uprightW - half
        val y0 = (rect.top + rect.bottom) / 2f * uprightH - half
        val step = half * 2f / CROP_SIDE
        // Output index -> upright pixel, clamped into the frame (that is the edge padding above).
        val uxMap = IntArray(CROP_SIDE) { (x0 + it * step).toInt().coerceIn(0, uprightW - 1) }
        val uyMap = IntArray(CROP_SIDE) { (y0 + it * step).toInt().coerceIn(0, uprightH - 1) }

        val plane = CROP_SIDE * CROP_SIDE
        val chromaBase = dispW * dispH // NV21: full luma plane, then interleaved V,U per 2x2 block
        for (oy in 0 until CROP_SIDE) {
            val uy = uyMap[oy]
            val row = oy * CROP_SIDE
            for (ox in 0 until CROP_SIDE) {
                val ux = uxMap[ox]
                val dx: Int
                val dy: Int
                when (rotation) { // upright pixel -> unrotated display pixel
                    90 -> { dx = uy; dy = uprightW - 1 - ux }
                    180 -> { dx = uprightW - 1 - ux; dy = uprightH - 1 - uy }
                    270 -> { dx = uprightH - 1 - uy; dy = ux }
                    else -> { dx = ux; dy = uy }
                }
                val ci = chromaBase + (dy shr 1) * dispW + (dx shr 1) * 2
                val y = nv21.get(dy * dispW + dx).toInt() and 0xFF
                val v = (nv21.get(ci).toInt() and 0xFF) - 128     // NV21: V first,
                val u = (nv21.get(ci + 1).toInt() and 0xFF) - 128 // then U
                val r = (y + ((1436 * v) shr 10)).coerceIn(0, 255)
                val g = (y - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255)
                val b = (y + ((1815 * u) shr 10)).coerceIn(0, 255)
                val i = row + ox
                out.put(i, r.toFloat())              // R plane
                out.put(plane + i, g.toFloat())      // G plane
                out.put(2 * plane + i, b.toFloat())  // B plane
            }
        }
    }
}
