package com.haithamassoli.naqi.desktop.media

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.io.IOException

// The few libav* idioms every file in this package repeats. Everything here is a thin wrapper: the
// FFmpeg C API is the API, this only turns its negative return codes into exceptions.

/** AVERROR(EAGAIN). FFmpeg's errors are negated errno values and EAGAIN differs: 35 on macOS, 11 elsewhere. */
internal val AVERROR_EAGAIN = -(if (Loader.getPlatform().startsWith("macosx")) 35 else 11)

internal fun rational(num: Int, den: Int): AVRational = AVRational().num(num).den(den)
internal val MICROS = rational(1, 1_000_000)
internal val MILLIS = rational(1, 1000)

internal fun ffCheck(ret: Int, what: String): Int {
    if (ret < 0) throw IOException("$what: ${ffError(ret)}")
    return ret
}

internal fun ffError(code: Int): String {
    val buf = ByteArray(256)
    av_strerror(code, buf, buf.size.toLong())
    return String(buf, Charsets.UTF_8).substringBefore('\u0000')
}

/**
 * Opens [file] for reading with FFmpeg's file protocol, which opens it O_RDONLY: nothing on this path can
 * write to a user's original. The path goes over as UTF-8, which is what FFmpeg's Windows file protocol
 * expects (it converts to UTF-16 itself), so non-ASCII names work there too.
 */
internal fun openInput(file: File): AVFormatContext {
    av_log_set_level(AV_LOG_ERROR) // libx264/aac print banners and stats at the default level; keep stderr for errors
    val ctx = AVFormatContext(null)
    ffCheck(avformat_open_input(ctx, BytePointer(file.absolutePath, "UTF-8"), null, null as AVDictionary?), "open $file")
    try {
        ffCheck(avformat_find_stream_info(ctx, null as AVDictionary?), "stream info $file")
    } catch (t: Throwable) {
        avformat_close_input(ctx)
        throw t
    }
    return ctx
}

/** The best stream of [type] (AVMEDIA_TYPE_*), or null. */
internal fun bestStream(fmt: AVFormatContext, type: Int): AVStream? {
    val ix = av_find_best_stream(fmt, type, -1, -1, null as AVCodec?, 0)
    return if (ix < 0) null else fmt.streams(ix)
}

internal fun openDecoder(st: AVStream): AVCodecContext {
    val codec = avcodec_find_decoder(st.codecpar().codec_id())
    if (codec == null || codec.isNull) throw IOException("no decoder for codec ${st.codecpar().codec_id()}")
    val c = avcodec_alloc_context3(codec)
    try {
        ffCheck(avcodec_parameters_to_context(c, st.codecpar()), "decoder params")
        c.pkt_timebase(st.time_base())
        c.thread_count(0) // auto: one per core
        ffCheck(avcodec_open2(c, codec, null as AVDictionary?), "open decoder")
    } catch (t: Throwable) {
        avcodec_free_context(c)
        throw t
    }
    return c
}

/**
 * Sends [pkt] (null = flush) to [dec] and hands every frame it yields to [onFrame]. A corrupt packet is
 * skipped rather than failing a multi-hour job, which is what the ffmpeg CLI does too.
 */
internal inline fun decodePacket(dec: AVCodecContext, pkt: AVPacket?, frame: AVFrame, onFrame: (AVFrame) -> Unit) {
    val sent = avcodec_send_packet(dec, pkt)
    if (sent < 0 && sent != AVERROR_INVALIDDATA && sent != AVERROR_EOF) ffCheck(sent, "decode")
    while (true) {
        val r = avcodec_receive_frame(dec, frame)
        if (r == AVERROR_EAGAIN || r == AVERROR_EOF) return
        if (r == AVERROR_INVALIDDATA) continue
        ffCheck(r, "decode")
        try {
            onFrame(frame)
        } finally {
            av_frame_unref(frame)
        }
    }
}

/** The stream's start in its own time base, 0 when the container does not say. */
internal fun startPts(st: AVStream): Long = st.start_time().let { if (it == AV_NOPTS_VALUE) 0L else it }

/** [ts] in [tb] as whole milliseconds, floored (SEAMS: ptsMs is floored, never rounded up past a frame). */
internal fun toMs(ts: Long, tb: AVRational): Long = av_rescale_q_rnd(ts, tb, MILLIS, AV_ROUND_DOWN)

internal fun toUs(ts: Long, tb: AVRational): Long = av_rescale_q_rnd(ts, tb, MICROS, AV_ROUND_DOWN)

/**
 * One side of a `sws_scale` call for packed RGB24: FFmpeg copies four plane pointers and four strides from
 * each side whatever the format, so a one-slot array is read past its end. Four slots, zeroed; only plane 0
 * is set.
 */
internal class SwsPlanes : AutoCloseable {
    val data = PointerPointer<BytePointer>(4L).apply { zero<PointerPointer<BytePointer>>() }
    val stride = IntPointer(4L).apply { zero<IntPointer>() }

    fun set(plane0: BytePointer, stride0: Int) {
        data.put(0L, plane0)
        stride.put(0L, stride0) // 0L: a bare 0 picks the put(int...) overload
    }

    override fun close() {
        data.close()
        stride.close()
    }
}
