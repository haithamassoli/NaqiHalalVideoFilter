package com.haithamassoli.naqi.desktop.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * In the coroutine context of a [Renderer.render], lets it replace an existing output: the CLI's
 * `--overwrite`. Without it the renderer never replaces a file.
 */
object OverwriteOutput : CoroutineContext.Element, CoroutineContext.Key<OverwriteOutput> {
    override val key: CoroutineContext.Key<*> get() = this
}

object Renderer {
    // Android's quality intent (RenderPipeline.resolveBitrate): re-encoding must not visibly soften an
    // untouched frame, and the source's bitrate x 1.3, capped by a resolution tier, is the budget. x264's
    // CRF 18 is "visually transparent"; the Android budget becomes its VBV ceiling, so a clean low-bitrate
    // source stays small and a busy one cannot balloon past what Android would have written.
    private const val CRF = "18"
    private const val PRESET = "medium" // x264's default: the speed/size balance it is tuned around
    private const val GEN2_HEADROOM = 1.3

    /**
     * Write [output] as MP4. With [edit] null the video stream is copied untouched (re-encoded only if MP4
     * cannot carry its codec, e.g. VP8); otherwise every frame is decoded full size in STORED orientation
     * (no rotation applied), passed to [edit] to change in place, and encoded with libx264 (yuv420p)
     * keeping that frame's original pts, in the source stream's own time base. The rotation metadata is
     * carried to the output either way, so it plays upright. All streams are shifted by the file's start
     * time together, as the ffmpeg CLI does, so an audio offset survives.
     *
     * [input] is opened read-only. [output] must not be [input] (the same file by canonical path → throws
     * IllegalArgumentException). An existing [output] is never replaced unless the caller's coroutine context
     * holds [OverwriteOutput]: it fails with FileAlreadyExistsException before any work, or at the end if it
     * appeared meanwhile. The file is written to a temp file in its own directory and renamed to [output]
     * only when complete. That rename is the commit point: a failure or cancel before it leaves no partial
     * file and no damaged original; once it is done, [render] returns normally, even if cancelled since.
     * [onProgress] gets 0..100, and 100 only once [output] is there.
     */
    suspend fun render(
        input: File,
        output: File,
        audio: AudioOut,
        edit: ((RgbFrame) -> Unit)?,
        onProgress: (Int) -> Unit,
    ) {
        require(input.canonicalFile != output.canonicalFile) { "output $output is the input file" }
        val overwrite = coroutineContext[OverwriteOutput] != null
        if (!overwrite && output.exists()) throw FileAlreadyExistsException(output.path, null, "already exists")
        val dir = output.absoluteFile.parentFile ?: throw IOException("no directory for $output")
        val tmp = File.createTempFile(".${output.name}.", ".part", dir)
        var aac: File? = null
        try {
            withContext(Dispatchers.IO) {
                var out = audio
                if (audio is AudioOut.Keep && audioNeedsAac(input)) {
                    // e.g. Vorbis from a WebM: MP4 cannot carry it, so it goes through the same AAC path as music removal.
                    val m4a = File.createTempFile(".${output.name}.", ".m4a.part", dir).also { aac = it }
                    AacWriter(m4a).use { w -> AudioDecoder.decode(input) { pcm, n -> w.write(pcm, n) } }
                    out = AudioOut.Replace(m4a)
                }
                mux(input, tmp, out, edit, onProgress)
            }
            // The commit point. Nothing below suspends, so a cancel either lands here (the finally deletes the
            // temp file) or after the rename, when the output is complete and the job is done.
            coroutineContext.ensureActive()
            if (overwrite) {
                Files.move(tmp.toPath(), output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } else {
                Files.move(tmp.toPath(), output.toPath()) // a rename that refuses an existing target
            }
            onProgress(100)
        } finally {
            tmp.delete() // already gone after the move
            aac?.delete()
        }
    }

    private fun audioNeedsAac(input: File): Boolean {
        val fmt = openInput(input)
        try {
            val st = bestStream(fmt, AVMEDIA_TYPE_AUDIO) ?: return false
            return avformat_query_codec(av_guess_format("mp4", null, null), st.codecpar().codec_id(), FF_COMPLIANCE_NORMAL) != 1
        } finally {
            avformat_close_input(fmt)
        }
    }

    private suspend fun mux(input: File, tmp: File, audio: AudioOut, edit: ((RgbFrame) -> Unit)?, onProgress: (Int) -> Unit) {
        // Checked through the drains and before the trailer too, not only per packet read: the decoder's and
        // encoder's delayed frames and the .m4a's tail are work after the last read.
        val job = coroutineContext.job
        val inFmt = openInput(input)
        var m4aFmt: AVFormatContext? = null
        val out = AVFormatContext(null)
        var pb: AVIOContext? = null
        var dec: AVCodecContext? = null
        var enc: AVCodecContext? = null
        val pkt = av_packet_alloc()
        val m4aPkt = av_packet_alloc()
        val encPkt = av_packet_alloc()
        val frame = av_frame_alloc()
        val yuv = av_frame_alloc()
        val toRgb = ToRgb(SWS_BICUBIC)
        var fromRgb: SwsContext? = null
        var rgbNative = BytePointer(0L)
        val rgbIn = SwsPlanes()
        try {
            ffCheck(avformat_alloc_output_context2(out, null, "mp4", tmp.absolutePath), "mp4 muxer")
            val vIn = bestStream(inFmt, AVMEDIA_TYPE_VIDEO) ?: throw IOException("no video stream in $input")
            val srcAudio = bestStream(inFmt, AVMEDIA_TYPE_AUDIO)
            // The ffmpeg CLI's default: every stream moves by the file's start, so the earliest begins at 0
            // and the offsets between them survive.
            val fileStartUs = inFmt.start_time().let { if (it == AV_NOPTS_VALUE) 0L else it }

            val reencode = edit != null ||
                avformat_query_codec(out.oformat(), vIn.codecpar().codec_id(), FF_COMPLIANCE_NORMAL) != 1
            val vOut = avformat_new_stream(out, null)
            if (reencode) {
                val d = openDecoder(vIn).also { dec = it }
                val e = openX264(inFmt, vIn, d, out).also { enc = it }
                ffCheck(avcodec_parameters_from_context(vOut.codecpar(), e), "video params")
                vOut.time_base(e.time_base())
                copyDisplayMatrix(vIn.codecpar(), vOut.codecpar())
                yuv.format(e.pix_fmt())
                yuv.width(e.width())
                yuv.height(e.height())
                ffCheck(av_frame_get_buffer(yuv, 0), "video frame")
            } else {
                ffCheck(avcodec_parameters_copy(vOut.codecpar(), vIn.codecpar()), "video params") // incl. the display matrix
                vOut.codecpar().codec_tag(0) // let the MP4 muxer pick its own tag for the codec
                vOut.time_base(vIn.time_base())
            }

            val aIn: AVStream? = when (audio) {
                AudioOut.Keep -> srcAudio
                AudioOut.Drop -> null
                is AudioOut.Replace -> {
                    val m = openInput(audio.m4a).also { m4aFmt = it }
                    bestStream(m, AVMEDIA_TYPE_AUDIO) ?: throw IOException("no audio stream in ${audio.m4a}")
                }
            }
            val aOut = aIn?.let { a ->
                avformat_new_stream(out, null).also {
                    ffCheck(avcodec_parameters_copy(it.codecpar(), a.codecpar()), "audio params")
                    it.codecpar().codec_tag(0)
                    it.time_base(a.time_base())
                }
            }
            val keepIndex = if (audio is AudioOut.Keep && aIn != null) aIn.index() else -1
            // What to subtract from each input stream's timestamps to land on the output clock. The .m4a's
            // first sample goes where the source's audio began (where its decode for music removal started).
            val vShift = av_rescale_q(fileStartUs, MICROS, vIn.time_base())
            val aShift = when {
                aIn == null -> 0L
                audio is AudioOut.Replace -> {
                    val srcStartUs = srcAudio?.let { toUs(startPts(it), it.time_base()) } ?: fileStartUs
                    startPts(aIn) - av_rescale_q(srcStartUs - fileStartUs, MICROS, aIn.time_base())
                }
                else -> av_rescale_q(fileStartUs, MICROS, aIn.time_base())
            }

            val io = AVIOContext(null)
            ffCheck(avio_open(io, BytePointer(tmp.absolutePath, "UTF-8"), AVIO_FLAG_WRITE), "create $tmp")
            pb = io
            out.pb(io)
            ffCheck(avformat_write_header(out, null as AVDictionary?), "mp4 header")

            fun write(p: AVPacket, from: AVStream, to: AVStream, shift: Long) {
                if (p.pts() != AV_NOPTS_VALUE) p.pts(p.pts() - shift)
                if (p.dts() != AV_NOPTS_VALUE) p.dts(p.dts() - shift)
                av_packet_rescale_ts(p, from.time_base(), to.time_base())
                p.stream_index(to.index())
                p.pos(-1)
                ffCheck(av_interleaved_write_frame(out, p), "mp4 write") // takes the packet's data
            }

            // Replace: the .m4a is a second input, so its packets are fed in step with the video clock
            // rather than all at the end (which the interleaver would have to hold in memory).
            var m4aDone = audio !is AudioOut.Replace
            var m4aHeld = false
            fun feedM4a(untilUs: Long) {
                val m = m4aFmt ?: return
                while (!m4aDone) {
                    job.ensureActive()
                    if (!m4aHeld) {
                        val r = av_read_frame(m, m4aPkt)
                        if (r == AVERROR_EOF) { m4aDone = true; return }
                        ffCheck(r, "read ${(audio as AudioOut.Replace).m4a}")
                        if (m4aPkt.stream_index() != aIn!!.index()) { av_packet_unref(m4aPkt); continue }
                        m4aHeld = true
                    }
                    val ts = if (m4aPkt.dts() != AV_NOPTS_VALUE) m4aPkt.dts() else m4aPkt.pts()
                    if (toUs(ts - aShift, aIn!!.time_base()) > untilUs) return
                    write(m4aPkt, aIn, aOut!!, aShift)
                    m4aHeld = false
                }
            }

            val vStart = startPts(vIn)
            var lastPts = Long.MIN_VALUE
            // Each frame's own duration, by output pts. libx264 hands packets back with no duration, and an MP4
            // whose last sample has none gets an edit list that ends at the last pts, so players drop the final
            // frame. The ffmpeg CLI patches this in its muxer step; this is the same patch.
            val durations = HashMap<Long, Long>()
            fun encode(f: AVFrame?) {
                val e = enc!!
                ffCheck(avcodec_send_frame(e, f), "x264")
                while (true) {
                    job.ensureActive()
                    val r = avcodec_receive_packet(e, encPkt)
                    if (r == AVERROR_EAGAIN || r == AVERROR_EOF) return
                    ffCheck(r, "x264")
                    if (encPkt.duration() <= 0L) encPkt.duration(durations[encPkt.pts()] ?: 0L)
                    durations.remove(encPkt.pts())
                    write(encPkt, vIn, vOut, 0L) // already shifted: frames go in as `ts - vShift`
                }
            }
            fun editAndEncode(f: AVFrame) {
                job.ensureActive()
                val ts = f.best_effort_timestamp()
                // x264 needs strictly increasing pts. A frame with none, or a repeat, is dropped: re-timing
                // it would be exactly the synthesized timestamp this path exists to avoid.
                if (ts == AV_NOPTS_VALUE || ts <= lastPts) return
                lastPts = ts
                val w = f.width()
                val h = f.height()
                val rgb = toRgb.convert(f, w, h)
                edit?.invoke(RgbFrame(w, h, rgb, toMs(ts - vStart, vIn.time_base())))
                if (fromRgb == null || rgbNative.capacity() != rgb.size.toLong()) {
                    fromRgb?.let { sws_freeContext(it) }
                    fromRgb = sws_getContext(w, h, AV_PIX_FMT_RGB24, yuv.width(), yuv.height(), yuv.format(), SWS_BICUBIC, null, null, null as DoublePointer?)
                    rgbNative.close()
                    rgbNative = BytePointer(rgb.size.toLong())
                    rgbIn.set(rgbNative, w * 3)
                }
                rgbNative.put(rgb, 0, rgb.size)
                ffCheck(av_frame_make_writable(yuv), "video frame")
                ffCheck(sws_scale(fromRgb, rgbIn.data, rgbIn.stride, 0, h, yuv.data(), yuv.linesize()), "to YUV")
                yuv.pts(ts - vShift) // the encoder's time base is the source stream's, so this is the original pts
                if (f.duration() > 0L) durations[ts - vShift] = f.duration()
                encode(yuv)
            }

            val durationUs = inFmt.duration().takeIf { it != AV_NOPTS_VALUE && it > 0 } ?: 0L
            var lastPct = -1
            while (true) {
                coroutineContext.ensureActive()
                val r = av_read_frame(inFmt, pkt)
                if (r == AVERROR_EOF) break
                ffCheck(r, "read $input")
                try {
                    when (pkt.stream_index()) {
                        vIn.index() -> {
                            val ts = if (pkt.dts() != AV_NOPTS_VALUE) pkt.dts() else pkt.pts()
                            val tUs = if (ts == AV_NOPTS_VALUE) 0L else toUs(ts - vShift, vIn.time_base())
                            feedM4a(tUs)
                            if (durationUs > 0) {
                                val pct = (tUs * 100 / durationUs).toInt().coerceIn(0, 99)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                            if (reencode) decodePacket(dec!!, pkt, frame) { editAndEncode(it) } else write(pkt, vIn, vOut, vShift)
                        }
                        keepIndex -> write(pkt, aIn!!, aOut!!, aShift)
                    }
                } finally {
                    av_packet_unref(pkt)
                }
            }
            if (reencode) {
                decodePacket(dec!!, null, frame) { editAndEncode(it) }
                encode(null)
            }
            feedM4a(Long.MAX_VALUE)
            job.ensureActive()
            ffCheck(av_write_trailer(out), "mp4 trailer")
        } finally {
            av_frame_free(frame)
            av_frame_free(yuv)
            av_packet_free(pkt)
            av_packet_free(m4aPkt)
            av_packet_free(encPkt)
            dec?.let { avcodec_free_context(it) }
            enc?.let { avcodec_free_context(it) }
            toRgb.close()
            fromRgb?.let { sws_freeContext(it) }
            rgbNative.close()
            rgbIn.close()
            pb?.let { avio_closep(it) }
            avformat_free_context(out)
            m4aFmt?.let { avformat_close_input(it) }
            avformat_close_input(inFmt)
        }
    }

    private fun openX264(inFmt: AVFormatContext, vIn: AVStream, dec: AVCodecContext, out: AVFormatContext): AVCodecContext {
        val codec = avcodec_find_encoder_by_name("libx264")
        if (codec == null || codec.isNull) throw IOException("libx264 is missing: this needs the GPL FFmpeg build")
        val c = avcodec_alloc_context3(codec)
        val opts = AVDictionary(null)
        try {
            val par = vIn.codecpar()
            c.width(par.width())
            c.height(par.height())
            // A full-range (yuvj) source stays full range; sws converts both ways with the same matrix, so
            // untouched pixels come back as they were and the copied colour tags stay true.
            c.pix_fmt(if (dec.pix_fmt() == AV_PIX_FMT_YUVJ420P) AV_PIX_FMT_YUVJ420P else AV_PIX_FMT_YUV420P)
            c.time_base(vIn.time_base()) // frames keep their pts, so the encoder counts in the source's units
            vIn.avg_frame_rate().let { if (it.num() > 0 && it.den() > 0) c.framerate(it) } // a rate-control hint only
            c.sample_aspect_ratio(par.sample_aspect_ratio())
            c.color_primaries(par.color_primaries())
            c.color_trc(par.color_trc())
            c.colorspace(par.color_space())
            c.color_range(par.color_range())
            val maxRate = budget(inFmt, par)
            c.rc_max_rate(maxRate)
            c.rc_buffer_size((maxRate * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (out.oformat().flags() and AVFMT_GLOBALHEADER != 0) c.flags(c.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
            av_dict_set(opts, "crf", CRF, 0)
            av_dict_set(opts, "preset", PRESET, 0)
            ffCheck(avcodec_open2(c, codec, opts), "open libx264")
            return c
        } catch (t: Throwable) {
            avcodec_free_context(c)
            throw t
        } finally {
            av_dict_free(opts)
        }
    }

    /** Android's `resolveBitrate`: min(source video bitrate x 1.3, resolution-tier cap), bits/s. */
    internal fun budget(inFmt: AVFormatContext, par: AVCodecParameters): Long {
        val pixels = par.width().toLong() * par.height()
        val cap = when {
            pixels <= 854L * 480 -> 4_000_000L
            pixels <= 1280L * 720 -> 10_000_000L
            pixels <= 1920L * 1080 -> 16_000_000L
            pixels <= 2560L * 1440 -> 24_000_000L
            else -> 45_000_000L
        }
        // The whole-file rate (audio included) is Android's accepted upper-bound fallback too.
        val source = par.bit_rate().takeIf { it > 0 } ?: inFmt.bit_rate().takeIf { it > 0 }
        return source?.let { minOf((it * GEN2_HEADROOM).toLong(), cap) } ?: cap
    }

    /** Re-encoding starts from fresh parameters, so the rotation has to be carried over by hand. */
    private fun copyDisplayMatrix(src: AVCodecParameters, dst: AVCodecParameters) {
        val sd = av_packet_side_data_get(src.coded_side_data(), src.nb_coded_side_data(), AV_PKT_DATA_DISPLAYMATRIX)
        if (sd == null || sd.isNull) return
        // av_packet_side_data_new reallocates the array through these out-pointers; write both back.
        val arr = PointerPointer<AVPacketSideData>(1L).put(0L, dst.coded_side_data())
        val nb = IntPointer(1L).put(dst.nb_coded_side_data())
        try {
            val added = av_packet_side_data_new(arr, nb, AV_PKT_DATA_DISPLAYMATRIX, sd.size(), 0)
            if (added == null || added.isNull) throw IOException("out of memory copying the rotation")
            Pointer.memcpy(added.data(), sd.data(), sd.size())
            dst.coded_side_data(arr.get(AVPacketSideData::class.java, 0))
            dst.nb_coded_side_data(nb.get())
        } finally {
            arr.close()
            nb.close()
        }
    }
}
