package com.haithamassoli.naqi.desktop.media

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Writes 44.1 kHz stereo float PCM as AAC-LC 192 kbps into an .m4a, in the order given: the port of
 * Android's `audio/AacWriter.kt` onto FFmpeg's native AAC encoder. In 44.1, out 44.1, no resampling.
 *
 * The first sample is pts 0; [Renderer] places the track at the source audio's start, so this file
 * has no clock of its own to get wrong. The encoder's priming is recorded in the MP4 edit list, so a
 * decoder gets back exactly the samples written. [close] finishes the file (flush + trailer) and frees
 * everything; once a [write] has thrown it only frees. Serialized, like Android's: no overlapping calls.
 */
class AacWriter(output: File) : AutoCloseable {
    private val fmt = AVFormatContext(null)
    private var pb: AVIOContext? = null
    private var enc: AVCodecContext? = null
    private val st: AVStream
    private val frame = av_frame_alloc()
    private val pkt = av_packet_alloc()
    private val frameSize: Int

    // Interleaved samples waiting for a whole encoder frame (1024 sample frames for AAC).
    private val pending: FloatArray
    private var pendingFrames = 0
    private var samplesOut = 0L
    private var closed = false
    private var broken = false

    init {
        try {
            ffCheck(avformat_alloc_output_context2(fmt, null, "mp4", output.absolutePath), "mp4 muxer")
            val codec = avcodec_find_encoder(AV_CODEC_ID_AAC)
            if (codec == null || codec.isNull) throw IOException("no AAC encoder")
            val c = avcodec_alloc_context3(codec).also { enc = it }
            c.sample_fmt(AV_SAMPLE_FMT_FLTP) // the native encoder's only input format
            c.sample_rate(AudioDecoder.RATE)
            av_channel_layout_default(c.ch_layout(), 2)
            c.bit_rate(192_000)
            c.profile(AV_PROFILE_AAC_LOW)
            c.time_base(rational(1, AudioDecoder.RATE))
            if (fmt.oformat().flags() and AVFMT_GLOBALHEADER != 0) c.flags(c.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
            ffCheck(avcodec_open2(c, codec, null as AVDictionary?), "open AAC encoder")
            frameSize = c.frame_size()
            pending = FloatArray(frameSize * 2)

            st = avformat_new_stream(fmt, null)
            ffCheck(avcodec_parameters_from_context(st.codecpar(), c), "AAC params")
            st.time_base(c.time_base())

            val io = AVIOContext(null)
            ffCheck(avio_open(io, BytePointer(output.absolutePath, "UTF-8"), AVIO_FLAG_WRITE), "create $output")
            pb = io
            fmt.pb(io)
            ffCheck(avformat_write_header(fmt, null as AVDictionary?), "m4a header")

            frame.format(AV_SAMPLE_FMT_FLTP)
            frame.sample_rate(AudioDecoder.RATE)
            ffCheck(av_channel_layout_copy(frame.ch_layout(), c.ch_layout()), "layout")
            frame.nb_samples(frameSize)
            ffCheck(av_frame_get_buffer(frame, 0), "audio frame")
        } catch (t: Throwable) {
            free()
            throw t
        }
    }

    fun write(interleaved: FloatArray, frames: Int) {
        check(!closed) { "AacWriter is closed" }
        try {
            var i = 0
            while (i < frames) {
                val n = min(frames - i, frameSize - pendingFrames)
                System.arraycopy(interleaved, i * 2, pending, pendingFrames * 2, n * 2)
                pendingFrames += n
                i += n
                if (pendingFrames == frameSize) encodePending()
            }
        } catch (t: Throwable) {
            broken = true
            throw t
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!broken) {
                if (pendingFrames > 0) encodePending() // a short last frame: the AAC encoder accepts one
                send(null)
                ffCheck(av_write_trailer(fmt), "m4a trailer")
            }
        } finally {
            free()
        }
    }

    private fun encodePending() {
        ffCheck(av_frame_make_writable(frame), "audio frame")
        val l = FloatPointer(frame.data(0))
        val r = FloatPointer(frame.data(1))
        for (k in 0 until pendingFrames) {
            // Same boundary guard as Android: a NaN from upstream becomes silence, never a lost job.
            l.put(k.toLong(), finite(pending[2 * k]))
            r.put(k.toLong(), finite(pending[2 * k + 1]))
        }
        frame.nb_samples(pendingFrames)
        frame.pts(samplesOut)
        samplesOut += pendingFrames
        pendingFrames = 0
        send(frame)
    }

    private fun finite(v: Float) = if (v.isFinite()) max(-1f, min(1f, v)) else 0f

    private fun send(f: org.bytedeco.ffmpeg.avutil.AVFrame?) {
        val c = enc!!
        ffCheck(avcodec_send_frame(c, f), "AAC encode")
        while (true) {
            val r = avcodec_receive_packet(c, pkt)
            if (r == AVERROR_EAGAIN || r == AVERROR_EOF) return
            ffCheck(r, "AAC encode")
            av_packet_rescale_ts(pkt, c.time_base(), st.time_base())
            pkt.stream_index(st.index())
            ffCheck(av_interleaved_write_frame(fmt, pkt), "m4a write")
        }
    }

    private fun free() {
        av_frame_free(frame)
        av_packet_free(pkt)
        enc?.let { avcodec_free_context(it) }
        enc = null
        pb?.let { avio_closep(it) }
        pb = null
        avformat_free_context(fmt)
    }
}
