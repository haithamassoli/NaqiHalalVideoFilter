package com.haithamassoli.naqi.desktop.media

import com.haithamassoli.naqi.analysis.VideoMeta
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_DATA_DISPLAYMATRIX
import org.bytedeco.ffmpeg.global.avcodec.av_packet_side_data_get
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_display_rotation_get
import org.bytedeco.javacpp.IntPointer
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

// Owner: the media agent (JavaCPP FFmpeg). The signatures in this package are the contract in
// apps/windows/SEAMS.md; change one only together with that file and its callers.
// VideoDecoder/AudioDecoder are in Decode.kt, AacWriter in AacWriter.kt, Renderer in Renderer.kt.

/**
 * One decoded picture: packed RGB24 (R, G, B bytes), row-major, stride exactly `width * 3`.
 * [ptsMs] is the frame's own timestamp (`best_effort_timestamp`, rescaled from the stream time base and
 * floored to ms, relative to the video stream's start time), never a frame counter. Both [VideoDecoder.sample]
 * and [Renderer.render] use that same clock, which is what lets an EDL built from one drive the other.
 *
 * The decoder may reuse [rgb] for the next frame: a frame is valid only inside the callback it is passed to.
 */
class RgbFrame(val width: Int, val height: Int, val rgb: ByteArray, val ptsMs: Long)

/** What the output's audio is. */
sealed interface AudioOut {
    /** The source's audio stream, stream-copied (or AAC-transcoded when MP4 cannot carry its codec). */
    data object Keep : AudioOut
    /** This .m4a (from [AacWriter]) instead, stream-copied and placed at the source audio's start time. */
    data class Replace(val m4a: File) : AudioOut
    /** No audio stream. */
    data object Drop : AudioOut
}

object Probe {
    /** Container metadata without decoding: stored size, rotation (clockwise degrees), duration, fps (30 if absent). */
    fun probe(input: File): VideoMeta {
        val fmt = openInput(input)
        try {
            val st = bestStream(fmt, AVMEDIA_TYPE_VIDEO) ?: throw IOException("no video stream in $input")
            val par = st.codecpar()
            // Container duration first, as Android's MediaMetadataRetriever; the stream's is the fallback.
            val durationMs = when {
                fmt.duration() != AV_NOPTS_VALUE && fmt.duration() > 0 -> fmt.duration() / 1000
                st.duration() != AV_NOPTS_VALUE && st.duration() > 0 -> toMs(st.duration(), st.time_base())
                else -> 0L
            }
            val rate = st.avg_frame_rate()
            val fps = if (rate.num() > 0 && rate.den() > 0) rate.num().toFloat() / rate.den() else 30f
            return VideoMeta(par.width(), par.height(), rotationOf(st), durationMs, fps)
        } finally {
            avformat_close_input(fmt)
        }
    }
}

/**
 * Clockwise display rotation (0/90/180/270), the same convention as Android's KEY_ROTATION. FFmpeg's
 * display matrix angle is counter-clockwise, hence the sign flip. Anything that is not a quarter turn
 * (a hand-authored matrix) degrades to 0, as Android's sampler does, rather than failing the job.
 */
internal fun rotationOf(st: AVStream): Int {
    val par = st.codecpar()
    val sd = av_packet_side_data_get(par.coded_side_data(), par.nb_coded_side_data(), AV_PKT_DATA_DISPLAYMATRIX)
    if (sd == null || sd.isNull || sd.size() < 36) return 0
    val ccw = av_display_rotation_get(IntPointer(sd.data()))
    if (ccw.isNaN()) return 0
    val cw = ((-ccw.roundToInt()) % 360 + 360) % 360
    return if (cw % 90 == 0) cw else 0
}
