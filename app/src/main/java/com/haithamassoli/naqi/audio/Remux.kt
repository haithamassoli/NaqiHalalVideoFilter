package com.haithamassoli.naqi.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * The video the final mux copies its picture from: either the original source Uri (music-only, the
 * source video track is passed through untouched) or the M1 pass-2 render file (combined).
 */
sealed interface VideoSource {
    data class FromUri(val uri: Uri) : VideoSource     // music-only: passthrough original video track
    data class FromFile(val file: File) : VideoSource  // combined: pass-2 rendered mp4
}

/**
 * Stage (e/f): mux one video track (copied sample-for-sample, ZERO re-encode — CSD rides in the
 * extractor's track format so copied samples stay bit-identical) with the AAC in a temp .m4a into
 * [File] outFile. Two `MediaExtractor`s feed one framework `MediaMuxer`, PTS-interleaved with
 * `Long.MAX_VALUE` sentinels so the trailing track drains once the other ends. Rotation is preserved
 * (`setOrientationHint` before `start()`); sample timestamps are passed through verbatim (no rebase).
 *
 * A half-written mp4 has no `moov` until `stop()`, so any failure deletes [outFile]; muxer/extractors
 * are always released. See android-media-spec §5–§6.
 */
object Remux {

    /**
     * Mux [video]'s video track with [audioM4a]'s audio into [outFile]. [onProgress] reports 0..100
     * by muxed video-sample fraction (coarse — only on change).
     */
    suspend fun mux(
        context: Context,
        video: VideoSource,
        audioM4a: File,
        outFile: File,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        var muxer: MediaMuxer? = null
        var failed = true
        try {
            when (video) {
                is VideoSource.FromUri -> vExt.setDataSource(context, video.uri, null)
                is VideoSource.FromFile -> vExt.setDataSource(video.file.absolutePath)
            }
            val vTrackIx = firstTrack(vExt, "video/")
            vExt.selectTrack(vTrackIx)
            val vFormat = vExt.getTrackFormat(vTrackIx)

            aExt.setDataSource(audioM4a.absolutePath)
            val aTrackIx = firstTrack(aExt, "audio/")
            aExt.selectTrack(aTrackIx)
            val aFormat = aExt.getTrackFormat(aTrackIx)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoSrc = Src(vExt, muxer.addTrack(vFormat), allocFor(vFormat))   // CSD (avcC/hvcC) rides in vFormat
            val audioSrc = Src(aExt, muxer.addTrack(aFormat), allocFor(aFormat))
            muxer.setOrientationHint(rotationOf(context, video, vFormat))          // MUST precede start()
            muxer.start()

            // KEY_DURATION present on a well-formed track: drive coarse progress; else just 100 at end.
            val durationUs = if (vFormat.containsKey(MediaFormat.KEY_DURATION)) vFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val info = MediaCodec.BufferInfo()
            var lastPct = -1
            while (!(videoSrc.eos && audioSrc.eos)) {
                ensureActive() // honor cancellation once per interleaved sample — a multi-GB remux must not run on after cancel
                val vt = if (videoSrc.eos) Long.MAX_VALUE else videoSrc.ext.sampleTime
                val at = if (audioSrc.eos) Long.MAX_VALUE else audioSrc.ext.sampleTime
                if (vt <= at) {
                    writeOne(muxer, videoSrc, info)
                    if (durationUs > 0L && vt >= 0L) {
                        val pct = (vt * 100L / durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                } else {
                    writeOne(muxer, audioSrc, info)
                }
            }

            muxer.stop()   // finalizes moov; only reached on success
            onProgress(100)
            failed = false
        } finally {
            runCatching { muxer?.release() }
            runCatching { vExt.release() }
            runCatching { aExt.release() }
            if (failed) runCatching { if (outFile.exists()) outFile.delete() } // never leave a half-written mp4
        }
    }

    /** One track being copied: its extractor, the muxer output track index, and a grow-on-demand buffer. */
    private class Src(val ext: MediaExtractor, val outTrack: Int, var buf: ByteBuffer, var eos: Boolean = false)

    /** Copy the next sample of [s] verbatim; sets [Src.eos] and returns without writing at track end. */
    private fun writeOne(muxer: MediaMuxer, s: Src, info: MediaCodec.BufferInfo) {
        val size = readWithGrow(s)
        if (size < 0) { s.eos = true; return }
        info.offset = 0
        info.size = size
        info.presentationTimeUs = s.ext.sampleTime   // pass through verbatim — no rebase
        info.flags = if (s.ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
            MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        muxer.writeSampleData(s.outTrack, s.buf, info)
        s.ext.advance()
    }

    /**
     * `readSampleData` into [Src.buf], growing on `IllegalArgumentException` (too-small buffer) — the
     * failed read does NOT advance the extractor, so retrying the same sample is safe (§5.2; avoids
     * the API 28+ `getSampleSize`). Returns the sample size, or -1 at EOS.
     */
    private fun readWithGrow(s: Src): Int {
        while (true) {
            try {
                return s.ext.readSampleData(s.buf, 0)
            } catch (_: IllegalArgumentException) {
                s.buf = ByteBuffer.allocate(s.buf.capacity() * 2)
            }
        }
    }

    private fun allocFor(format: MediaFormat): ByteBuffer {
        val cap = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1 shl 16)
        else (1 shl 20) // 1 MiB when the track format omits KEY_MAX_INPUT_SIZE
        return ByteBuffer.allocate(cap)
    }

    /** Track KEY_ROTATION is the authored value; fall back to MMR (mirrors FrameSampler.probe). */
    private fun rotationOf(context: Context, video: VideoSource, vFormat: MediaFormat): Int {
        val fromFmt = if (vFormat.containsKey(MediaFormat.KEY_ROTATION))
            vFormat.getInteger(MediaFormat.KEY_ROTATION) else null
        val deg = fromFmt ?: run {
            val mmr = MediaMetadataRetriever()
            try {
                when (video) {
                    is VideoSource.FromUri -> mmr.setDataSource(context, video.uri)
                    is VideoSource.FromFile -> mmr.setDataSource(video.file.absolutePath)
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            } finally {
                mmr.release()
            }
        } ?: 0
        return ((deg % 360) + 360) % 360
    }

    private fun firstTrack(ext: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until ext.trackCount) {
            if (ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true) return i
        }
        throw IllegalArgumentException("no $mimePrefix track")
    }
}
