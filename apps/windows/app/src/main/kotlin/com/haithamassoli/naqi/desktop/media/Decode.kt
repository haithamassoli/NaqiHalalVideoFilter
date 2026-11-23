package com.haithamassoli.naqi.desktop.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

object VideoDecoder {
    /**
     * Decode the first video stream and call [onFrame] once per sample slot, Android's `FrameSampler` rule
     * (see [Slots]). Frames are UPRIGHT (rotation applied) and scaled so the longer side is at most [maxDim]
     * (never up), with Android's even-dimension rounding. Suspends while [onFrame] runs, so the consumer
     * sets the pace; cancellable. NSFW's 5 fps is the caller's every-second-frame, as on Android.
     */
    suspend fun sample(
        input: File,
        fps: Float = 10f,
        maxDim: Int = 640,
        onFrame: suspend (RgbFrame) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val slots = Slots(fps)
        val toRgb = ToRgb(SWS_AREA) // area averaging: the analysis downscale should not alias small faces
        var upright = ByteArray(0)
        toRgb.use {
            readStream(input, AVMEDIA_TYPE_VIDEO) { st, frame ->
                val ts = frame.best_effort_timestamp()
                if (ts == AV_NOPTS_VALUE) return@readStream // cannot be placed on the clock; never invent one
                val rel = ts - startPts(st)
                if (!slots.take(toUs(rel, st.time_base()))) return@readStream
                val (w, h) = analysisSize(frame.width(), frame.height(), maxDim)
                val stored = toRgb.convert(frame, w, h)
                val rotation = rotationOf(st)
                val out = if (rotation == 0) {
                    RgbFrame(w, h, stored, toMs(rel, st.time_base()))
                } else {
                    if (upright.size != stored.size) upright = ByteArray(stored.size)
                    rotateRgb(stored, w, h, rotation, upright)
                    val swap = rotation % 180 != 0
                    RgbFrame(if (swap) h else w, if (swap) w else h, upright, toMs(rel, st.time_base()))
                }
                onFrame(out)
            } ?: throw IOException("no video stream in $input")
        }
    }

    /**
     * [sample], plus the SAME decoded picture at SOURCE resolution on every [sourceEvery]'th emitted frame
     * (the first, then every [sourceEvery]'th after it; never when it is 0), for a consumer that samples
     * source pixels itself, as Android's NSFW gate does (`FrameSampler.gatherGate`). [source] is converted
     * unscaled, so each pixel is the decoder's own, and left in STORED orientation; [rotation] is the
     * clockwise rotation to upright, the one [frame] already has applied. Both are valid only inside the call.
     */
    suspend fun sampleWithSource(
        input: File,
        fps: Float = 10f,
        maxDim: Int = 640,
        sourceEvery: Int,
        onFrame: suspend (frame: RgbFrame, source: RgbFrame?, rotation: Int) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val slots = Slots(fps)
        val toRgb = ToRgb(SWS_AREA)
        val toSource = ToRgb(SWS_POINT) // same size in and out: a plain colour conversion
        var upright = ByteArray(0)
        var n = 0
        toRgb.use {
            toSource.use {
                readStream(input, AVMEDIA_TYPE_VIDEO) { st, frame ->
                    val ts = frame.best_effort_timestamp()
                    if (ts == AV_NOPTS_VALUE) return@readStream
                    val rel = ts - startPts(st)
                    if (!slots.take(toUs(rel, st.time_base()))) return@readStream
                    val ptsMs = toMs(rel, st.time_base())
                    val (w, h) = analysisSize(frame.width(), frame.height(), maxDim)
                    val stored = toRgb.convert(frame, w, h)
                    val rotation = rotationOf(st)
                    val out = if (rotation == 0) {
                        RgbFrame(w, h, stored, ptsMs)
                    } else {
                        if (upright.size != stored.size) upright = ByteArray(stored.size)
                        rotateRgb(stored, w, h, rotation, upright)
                        val swap = rotation % 180 != 0
                        RgbFrame(if (swap) h else w, if (swap) w else h, upright, ptsMs)
                    }
                    val source = if (sourceEvery > 0 && n % sourceEvery == 0) {
                        RgbFrame(frame.width(), frame.height(), toSource.convert(frame, frame.width(), frame.height()), ptsMs)
                    } else {
                        null
                    }
                    n++
                    onFrame(out, source, rotation)
                } ?: throw IOException("no video stream in $input")
            }
        }
    }
}

object AudioDecoder {
    const val RATE = 44_100
    private const val SNAP_SECONDS = 0.01
    private const val SILENCE_CHUNK = 4096 // input samples per batch of inserted silence

    /**
     * The largest timestamp gap filled with silence in a format whose clock may jump ([AVFMT_TS_DISCONT]:
     * MPEG-TS and the like). A real hole there (lost packets, a dropout) is short; a jump past this is a
     * splice or a clock reset, where silence would invent time that the content never had. 10 s is the
     * ffmpeg CLI's own `-dts_delta_threshold` for these formats, so the audio reads as ffmpeg would read it.
     */
    private const val MAX_GAP_FILL_SECONDS = 10L

    /**
     * Decode the first audio stream to 44 100 Hz stereo float, interleaved L R L R, nominally in [-1, 1],
     * and hand it over in batches of [frames] sample frames (`interleaved.size >= frames * 2`; the array may
     * be reused after the call returns). Starts at the stream's first sample. Returns false, without calling
     * [onPcm], when the file has no audio stream. Cancellable.
     *
     * The PCM is laid out on the stream's own clock, not concatenated: a frame whose timestamp is later than
     * where the audio so far ends gets silence before it, and one that starts earlier but still ends past it
     * (an overlap) loses the samples already covered. Sample k of the output is then `k / RATE` s after the
     * first frame, which is what [Renderer] assumes when it puts an [AudioOut.Replace] track back. Drifts under
     * [SNAP_SECONDS] are timestamp rounding and left alone.
     *
     * A clock discontinuity is not a gap: a backward jump past the end of the frame (it would lose the whole
     * frame), or, in an [AVFMT_TS_DISCONT] format, a forward one over [MAX_GAP_FILL_SECONDS]. There the clock
     * is rebased onto where the audio so far ends and the frame is appended whole, so no decoded sample is
     * ever dropped except a genuine overlap. Other formats' timestamps are trusted (MP4, MKV: their gaps are
     * real, and the video sits on the same clock), so a forward gap of any size is filled there. In practice
     * [readStream]'s [TsDiscontinuity] has already moved a jumping file's packets onto one continuous clock, the
     * one the video is sampled and rendered on; this rule is what is left if the frames still disagree.
     *
     * In an MP4/MOV, an AAC encoder's padding of the last frame is marked only by that last packet's shorter
     * duration: FFmpeg 8.1's demuxer exports no end padding for the decoder to drop, so a frame longer than
     * its own duration is cut to it here. Only where the stream's time base is 1/rate, so the duration counts
     * samples exactly (the MP4 muxers' default for audio); the edit list's end is no substitute, since the
     * movie timescale it is written in is often 1/1000. Without this a 44.1 kHz source came back up to 1023
     * samples long, and so did the music-removed track put back over its video.
     *
     * Mono is duplicated at full level into both channels, as Android's decoder does (swresample's own
     * mono upmix would put it 3 dB down). More than two channels go through swresample's downmix, which,
     * like Android's hand-written one, keeps the centre (dialogue) channel and drops LFE.
     */
    suspend fun decode(input: File, onPcm: suspend (interleaved: FloatArray, frames: Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            var swr: SwrContext? = null
            var mono = false
            var native = FloatPointer(0L)
            val outPtr = PointerPointer<FloatPointer>(1L)
            var out = FloatArray(0)
            var stereo = FloatArray(0)
            var silence: AVFrame? = null

            /** Resamples one batch (null = flush the tail) and hands it on; returns the frames produced. */
            suspend fun push(inData: PointerPointer<*>?, inFrames: Int, inRate: Int): Int {
                val s = swr!!
                val outCh = if (mono) 1 else 2
                val cap = (swr_get_delay(s, RATE.toLong()) + inFrames.toLong() * RATE / inRate + 64).toInt()
                if (native.capacity() < cap.toLong() * outCh) {
                    native.close()
                    native = FloatPointer(cap.toLong() * outCh)
                    outPtr.put(0L, native)
                }
                val n = ffCheck(swr_convert(s, outPtr, cap, inData, inFrames), "resample")
                if (n == 0) return 0
                if (out.size < n * outCh) out = FloatArray(n * outCh)
                native.get(out, 0, n * outCh)
                if (mono) {
                    if (stereo.size < n * 2) stereo = FloatArray(n * 2)
                    for (i in 0 until n) {
                        stereo[2 * i] = out[i]
                        stereo[2 * i + 1] = out[i]
                    }
                    onPcm(stereo, n)
                } else {
                    onPcm(out, n)
                }
                return n
            }

            var inRate = 0
            var fed = 0L // input samples handed to the resampler since the first frame
            var origin = AV_NOPTS_VALUE // the first frame's timestamp, in input samples (rebased at a discontinuity)
            var discont = false
            var mov = false
            try {
                val opened = { fmt: AVFormatContext ->
                    discont = fmt.iformat().flags() and AVFMT_TS_DISCONT != 0
                    mov = fmt.iformat().name().string.split(',').contains("mov")
                }
                readStream(input, AVMEDIA_TYPE_AUDIO, opened) { st, frame ->
                    if (swr == null) {
                        inRate = frame.sample_rate()
                        val inLayout = AVChannelLayout()
                        if (frame.ch_layout().order() == AV_CHANNEL_ORDER_UNSPEC) {
                            av_channel_layout_default(inLayout, frame.ch_layout().nb_channels())
                        } else {
                            ffCheck(av_channel_layout_copy(inLayout, frame.ch_layout()), "layout")
                        }
                        mono = inLayout.nb_channels() == 1
                        val outLayout = AVChannelLayout()
                        av_channel_layout_default(outLayout, if (mono) 1 else 2)
                        val s = SwrContext(null as Pointer?)
                        ffCheck(
                            swr_alloc_set_opts2(s, outLayout, AV_SAMPLE_FMT_FLT, RATE, inLayout, frame.format(), inRate, 0, null),
                            "resampler",
                        )
                        swr = s
                        ffCheck(swr_init(s), "resampler")
                    }
                    var skip = 0
                    val ts = frame.best_effort_timestamp()
                    if (ts != AV_NOPTS_VALUE) {
                        val at = av_rescale(ts, st.time_base().num().toLong() * inRate, st.time_base().den().toLong())
                        if (origin == AV_NOPTS_VALUE) origin = at
                        val drift = at - origin - fed // > 0: a gap before this frame; < 0: it overlaps what was fed
                        val maxGap = if (discont) inRate * MAX_GAP_FILL_SECONDS else Long.MAX_VALUE
                        if (drift > maxGap || drift <= -frame.nb_samples()) {
                            origin += drift // a discontinuity: the clock goes on from where the audio ends
                        } else if (drift > inRate * SNAP_SECONDS) {
                            // Silence in the input's own format, through the resampler like any audio, so the
                            // resampler's tail of the audio before the gap stays before it.
                            val sil = silence ?: av_frame_alloc().also { f ->
                                silence = f
                                f.format(frame.format())
                                f.sample_rate(inRate)
                                ffCheck(av_channel_layout_copy(f.ch_layout(), frame.ch_layout()), "layout")
                                f.nb_samples(SILENCE_CHUNK)
                                ffCheck(av_frame_get_buffer(f, 0), "silence")
                                av_samples_set_silence(f.extended_data(), 0, SILENCE_CHUNK, f.ch_layout().nb_channels(), f.format())
                            }
                            var left = drift
                            while (left > 0) {
                                kotlin.coroutines.coroutineContext.ensureActive()
                                val k = minOf(left, SILENCE_CHUNK.toLong()).toInt()
                                push(sil.extended_data(), k, inRate)
                                left -= k
                            }
                            fed += drift
                        } else if (drift < -inRate * SNAP_SECONDS) {
                            skip = (-drift).toInt() // under nb_samples: the rest of the frame is new
                        }
                    }
                    // The encoder's end padding (see above): the frame's duration is its packet's, in samples here.
                    val tb = st.time_base()
                    val padding = if (mov && tb.num() == 1 && tb.den() == inRate && frame.duration() in 1 until frame.nb_samples()) {
                        frame.nb_samples() - frame.duration().toInt()
                    } else {
                        0
                    }
                    val n = frame.nb_samples() - skip - padding
                    if (n > 0) {
                        if (skip == 0) {
                            push(frame.extended_data(), n, inRate)
                        } else {
                            // The same planes, each starting `skip` samples in.
                            val fmt = frame.format()
                            val channels = frame.ch_layout().nb_channels()
                            val planar = av_sample_fmt_is_planar(fmt) != 0
                            val step = skip.toLong() * av_get_bytes_per_sample(fmt) * (if (planar) 1 else channels)
                            val planes = if (planar) channels else 1
                            val trimmed = PointerPointer<BytePointer>(planes.toLong())
                            try {
                                for (i in 0 until planes) {
                                    trimmed.put(i.toLong(), BytePointer(frame.extended_data().get(i.toLong())).getPointer(step))
                                }
                                push(trimmed, n, inRate)
                            } finally {
                                trimmed.close()
                            }
                        }
                        fed += n
                    }
                } ?: return@withContext false
                // The resampler holds a few ms of tail; without this the output is short by its delay.
                if (swr != null) while (push(null, 0, inRate) > 0) Unit
                true
            } finally {
                swr?.let { swr_free(it) }
                silence?.let { av_frame_free(it) }
                native.close()
                outPtr.close()
            }
        }
}

/**
 * Android's `FrameSampler` slot rule: slots `1000 / fps` ms apart (kept in µs, as Android does), anchored
 * to the first frame offered. A frame is taken when its pts is at or past the next slot; the slot then
 * advances one interval, or to `pts + interval` when that would leave it at or behind the frame (a gap).
 */
internal class Slots(fps: Float) {
    private val intervalUs = (1_000_000f / fps).toLong().coerceAtLeast(1L)
    private var nextUs = Long.MIN_VALUE

    fun take(ptsUs: Long): Boolean {
        if (nextUs == Long.MIN_VALUE) nextUs = ptsUs
        if (ptsUs < nextUs) return false
        nextUs += intervalUs
        if (nextUs <= ptsUs) nextUs = ptsUs + intervalUs
        return true
    }
}

/** Android's analysis size: longer side down to [maxDim] (never up), each side even and at least 2. */
internal fun analysisSize(w: Int, h: Int, maxDim: Int): Pair<Int, Int> {
    val longest = maxOf(w, h)
    val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
    return (maxOf(2, (w * scale).roundToInt()) and 1.inv()) to (maxOf(2, (h * scale).roundToInt()) and 1.inv())
}

/** Rotates packed RGB24 [src] ([w] x [h]) clockwise by [cw] (90/180/270) into [dst]. */
internal fun rotateRgb(src: ByteArray, w: Int, h: Int, cw: Int, dst: ByteArray) {
    val dw = if (cw == 180) w else h
    for (y in 0 until h) {
        for (x in 0 until w) {
            val dx: Int
            val dy: Int
            when (cw) {
                90 -> { dx = h - 1 - y; dy = x }
                180 -> { dx = w - 1 - x; dy = h - 1 - y }
                else -> { dx = y; dy = w - 1 - x } // 270
            }
            val s = (y * w + x) * 3
            val d = (dy * dw + dx) * 3
            dst[d] = src[s]
            dst[d + 1] = src[s + 1]
            dst[d + 2] = src[s + 2]
        }
    }
}

/** Any decoded picture to packed RGB24 at a given size, reusing its context and buffers between frames. */
internal class ToRgb(private val flags: Int) : AutoCloseable {
    private var sws: SwsContext? = null
    private var key = emptyList<Int>()
    private var native = BytePointer(0L)
    private val dst = SwsPlanes()
    private var out = ByteArray(0)

    fun convert(f: AVFrame, w: Int, h: Int): ByteArray {
        val k = listOf(f.width(), f.height(), f.format(), w, h)
        if (k != key) {
            sws?.let { sws_freeContext(it) }
            sws = sws_getContext(f.width(), f.height(), f.format(), w, h, AV_PIX_FMT_RGB24, flags, null, null, null as DoublePointer?)
                ?.takeIf { !it.isNull } ?: throw IOException("no scaler for pixel format ${f.format()}")
            native.close()
            native = BytePointer(w.toLong() * h * 3)
            dst.set(native, w * 3)
            out = ByteArray(w * h * 3)
            key = k
        }
        ffCheck(sws_scale(sws, f.data(), f.linesize(), 0, f.height(), dst.data, dst.stride), "to RGB")
        native.get(out)
        return out
    }

    override fun close() {
        sws?.let { sws_freeContext(it) }
        sws = null
        native.close()
        dst.close()
    }
}

/**
 * Decodes every frame of [input]'s best stream of [type] in presentation order and hands it to
 * [onFrame] (valid only inside the call), after [onOpen] has seen the opened file. Returns null, having
 * decoded nothing, when there is no such stream. Checks for cancellation once per packet. Timestamps are on
 * the file's continuous clock ([TsDiscontinuity]), the one [Renderer] writes.
 */
internal suspend inline fun readStream(
    input: File,
    type: Int,
    onOpen: (AVFormatContext) -> Unit = {},
    onFrame: (AVStream, AVFrame) -> Unit,
): Unit? {
    val fmt: AVFormatContext = openInput(input)
    var dec: AVCodecContext? = null
    val pkt = av_packet_alloc()
    val frame = av_frame_alloc()
    try {
        val st = bestStream(fmt, type) ?: return null
        onOpen(fmt)
        val d = openDecoder(st).also { dec = it }
        val clock = TsDiscontinuity(fmt)
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val r = av_read_frame(fmt, pkt)
            if (r == AVERROR_EOF) break
            ffCheck(r, "read $input")
            try {
                clock.fix(pkt) // every stream's packets, so a jump seen first in another stream moves this one too
                if (pkt.stream_index() == st.index()) decodePacket(d, pkt, frame) { onFrame(st, it) }
            } finally {
                av_packet_unref(pkt)
            }
        }
        decodePacket(d, null, frame) { onFrame(st, it) } // drain the decoder's delayed frames
        return Unit
    } finally {
        av_frame_free(frame)
        av_packet_free(pkt)
        dec?.let { avcodec_free_context(it) }
        avformat_close_input(fmt)
    }
}
