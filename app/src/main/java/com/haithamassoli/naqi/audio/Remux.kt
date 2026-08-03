package com.haithamassoli.naqi.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.haithamassoli.naqi.media.firstTrackFormat
import com.haithamassoli.naqi.media.requireTrackIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Where one track being copied comes from — the original source Uri (music-only passes the source video
 * track through untouched; the segmented censor path copies the source AUDIO track through untouched) or
 * a file this job produced (the pass-2 render, or the separated .m4a).
 */
sealed interface TrackSource {
    data class FromUri(val uri: Uri) : TrackSource
    data class FromFile(val file: File) : TrackSource
}

/** One rendered segment and the absolute source time it starts at, for [Remux.concat]. */
data class ConcatPart(val file: File, val startMs: Long)

/**
 * Where a muxed mp4 is written (`plan-v2` §5.9 S5). [ToFd] is what removes one full generation of the
 * final video: the job used to write `render.mp4`, mux that into `mux.mp4`, then COPY `mux.mp4` into
 * MediaStore — ~1.7 GB written and read again per film, for a file the muxer could have produced in
 * place. `MediaMuxer` takes a `FileDescriptor` (API 26; minSdk is 29), and `Publish` already opens one
 * on the pending MediaStore row, so the middle generation just disappears.
 *
 * The two differ in who cleans up a half-written file, which is why this is a type rather than a
 * nullable parameter. A partial mp4 has no `moov` and must never survive: for [ToFile] this object
 * deletes it, for [ToFd] it CANNOT — the descriptor's row belongs to [com.haithamassoli.naqi.work.Publish],
 * which drops it as part of the same contract that already covers a cancelled copy.
 */
sealed interface MuxOut {
    data class ToFile(val file: File) : MuxOut

    /** Descriptor opened "rw", not "w" — `MediaMuxer` seeks back over the file to write `moov`. */
    data class ToFd(val fd: FileDescriptor) : MuxOut
}

/** What MPEG-4 `MediaMuxer` will accept as an audio track (framework `MPEG4Writer`). */
private val MUXABLE_AUDIO = setOf("audio/mp4a-latm", "audio/3gpp", "audio/amr-wb")

/** What the segmented route must do with [uri]'s audio to get it through [Remux.concat]. */
enum class ConcatAudio {
    /** Framework `MediaMuxer` carries this codec: copy the source samples verbatim, bit-identical. */
    COPY,

    /** It does not: [com.haithamassoli.naqi.audio.AudioPipeline.transcodeToAac] makes an AAC copy first. */
    TRANSCODE,

    /** No audio track at all — [Remux.concat] takes a null audio source and writes a video-only file. */
    NONE,
}

/**
 * Which of the three [ConcatAudio] cases [uri] is, decided up front so the segmented censor-only route
 * knows whether it owes itself a transcode before it spends hours rendering.
 *
 * This used to be a Boolean `canCopyAudio`, and the Boolean is what made it wrong: it folded NONE in with
 * COPY ("nothing to copy, which is equally fine for concat"), but the caller then passed
 * `TrackSource.FromUri` unconditionally and [Remux.concat] calls `requireTrackIndex("audio/")` on it — so a
 * long silent source (a screen recording, a dashcam clip) threw after the whole analyze+render pass, with
 * no per-cause message. Three states, three branches.
 *
 * An unreadable source answers TRANSCODE, not NONE: the transcode then fails with a clear message, where
 * NONE would silently publish a film with its audio missing.
 */
fun concatAudio(context: Context, uri: Uri): ConcatAudio {
    val ext = MediaExtractor()
    return try {
        ext.setDataSource(context, uri, null)
        val mime = ext.firstTrackFormat("audio/")?.getString(MediaFormat.KEY_MIME) ?: return ConcatAudio.NONE
        if (mime in MUXABLE_AUDIO) ConcatAudio.COPY else ConcatAudio.TRANSCODE
    } catch (_: Throwable) {
        ConcatAudio.TRANSCODE // never NONE — losing the audio silently is the worse failure
    } finally {
        runCatching { ext.release() }
    }
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
     * Mux [video]'s video track with [audioM4a]'s audio into [out]. [onProgress] reports 0..100
     * by muxed video-sample fraction (coarse — only on change).
     */
    suspend fun mux(
        context: Context,
        video: TrackSource,
        audioM4a: File,
        out: MuxOut,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val vExt = MediaExtractor()
        val aExt = MediaExtractor()
        var muxer: MediaMuxer? = null
        var failed = true
        try {
            when (video) {
                is TrackSource.FromUri -> vExt.setDataSource(context, video.uri, null)
                is TrackSource.FromFile -> vExt.setDataSource(video.file.absolutePath)
            }
            val vTrackIx = vExt.requireTrackIndex("video/")
            vExt.selectTrack(vTrackIx)
            val vFormat = vExt.getTrackFormat(vTrackIx)

            aExt.setDataSource(audioM4a.absolutePath)
            val aTrackIx = aExt.requireTrackIndex("audio/")
            aExt.selectTrack(aTrackIx)
            val aFormat = aExt.getTrackFormat(aTrackIx)

            muxer = out.muxer()
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
            if (failed) out.discard() // never leave a half-written mp4
        }
    }

    /**
     * Concatenate video-only rendered [parts] with one [audio] track into [outFile], sample-copy, in ONE
     * muxer pass (`long-film-plan.md` Phase 2). No intermediate joined file: on a feature-length film that
     * would be another 4+ GB of scratch for no gain.
     *
     * **Offsets are the INTENDED segment starts, not measured durations.** Accumulating each segment's
     * observed length would fold a sub-frame rounding error into every following segment — across ~31
     * joins that is up to a second of A/V drift against the single continuous audio track, well past the
     * PRD's 50 ms budget. Placing every segment at the absolute time it was clipped from keeps the error
     * local to a seam and stops it accumulating. This is only sound because a clipped Transformer export
     * is rebased to 0 and is frame-accurate (media3 1.10.1 ExoAssetLoaderVideoRenderer.java:185-187).
     *
     * The muxer takes ONE format per track, so [parts] must share an encoder configuration; a mismatch
     * throws rather than writing a file whose tail cannot be decoded.
     */
    suspend fun concat(
        context: Context,
        parts: List<ConcatPart>,
        audio: TrackSource?,
        out: MuxOut,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(parts.isNotEmpty()) { "concat needs at least one segment" }
        val aExt = if (audio != null) MediaExtractor() else null
        var vExt = MediaExtractor()
        var muxer: MediaMuxer? = null
        var failed = true
        try {
            vExt.setDataSource(parts[0].file.absolutePath)
            val vTrackIx = vExt.requireTrackIndex("video/")
            vExt.selectTrack(vTrackIx)
            val vFormat = vExt.getTrackFormat(vTrackIx)

            muxer = out.muxer()
            val videoSrc = Src(vExt, muxer.addTrack(vFormat), allocFor(vFormat)) // CSD rides in vFormat
            val audioSrc = aExt?.let { ext ->
                when (audio) {
                    is TrackSource.FromUri -> ext.setDataSource(context, audio.uri, null)
                    is TrackSource.FromFile -> ext.setDataSource(audio.file.absolutePath)
                    null -> error("unreachable")
                }
                val ix = ext.requireTrackIndex("audio/")
                ext.selectTrack(ix)
                Src(ext, muxer.addTrack(ext.getTrackFormat(ix)), allocFor(ext.getTrackFormat(ix)))
            }
            muxer.setOrientationHint(rotationOf(context, TrackSource.FromFile(parts[0].file), vFormat))
            muxer.start()

            val totalUs = (parts.last().startMs * 1000) + segmentDurationUs(vFormat)
            val info = MediaCodec.BufferInfo()
            var partIx = 0
            var offsetUs = parts[0].startMs * 1000
            var lastPct = -1
            var videoDone = false
            while (!videoDone || audioSrc?.eos == false) {
                ensureActive() // a multi-GB concat must not run on after cancel
                val vt = if (videoDone) Long.MAX_VALUE else videoSrc.ext.sampleTime + offsetUs
                val at = if (audioSrc == null || audioSrc.eos) Long.MAX_VALUE else audioSrc.ext.sampleTime
                if (vt <= at) {
                    writeOne(muxer, videoSrc, info, offsetUs)
                    if (videoSrc.eos) {
                        // Roll over to the next segment, reusing one Src so the muxer track index and the
                        // grown buffer carry across.
                        partIx++
                        if (partIx >= parts.size) {
                            videoDone = true
                        } else {
                            videoSrc.ext.release()
                            val next = MediaExtractor()
                            vExt = next // the finally releases whichever segment is open when we exit
                            next.setDataSource(parts[partIx].file.absolutePath)
                            val ix = next.requireTrackIndex("video/")
                            next.selectTrack(ix)
                            requireSameFormat(vFormat, next.getTrackFormat(ix), partIx)
                            videoSrc.ext = next
                            offsetUs = parts[partIx].startMs * 1000
                            videoSrc.eos = false
                        }
                    } else if (totalUs > 0L && vt >= 0L) {
                        val pct = (vt * 100L / totalUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                } else {
                    writeOne(muxer, audioSrc!!, info, 0L)
                }
            }

            muxer.stop()
            onProgress(100)
            failed = false
        } finally {
            runCatching { muxer?.release() }
            runCatching { vExt.release() }
            runCatching { aExt?.release() }
            if (failed) out.discard()
        }
    }

    private fun MuxOut.muxer(): MediaMuxer = when (this) {
        is MuxOut.ToFile -> MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        is MuxOut.ToFd -> MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Half-written mp4 cleanup. See [MuxOut] for why the descriptor case is deliberately a no-op. */
    private fun MuxOut.discard() {
        if (this is MuxOut.ToFile) runCatching { if (file.exists()) file.delete() }
    }

    /**
     * The muxer carries one format per track, so every segment's encoder output must agree on the bits a
     * decoder configures itself from. Only those are compared: KEY_MAX_INPUT_SIZE and KEY_DURATION
     * legitimately differ per segment and are harmless.
     */
    private fun requireSameFormat(first: MediaFormat, next: MediaFormat, index: Int) {
        val mismatch = CONFIG_KEYS.firstOrNull { key -> describe(first, key) != describe(next, key) }
        check(mismatch == null) {
            "segment $index encoder config differs on '$mismatch' " +
                "(${describe(first, mismatch!!)} vs ${describe(next, mismatch)}) — concat would be undecodable"
        }
    }

    /** Mime/geometry plus the codec-specific data (avcC SPS/PPS) a decoder is configured with. */
    private val CONFIG_KEYS = listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT, "csd-0", "csd-1")

    /** One format key rendered comparably; ByteBuffers (the csd blobs) compare by content, not identity. */
    private fun describe(f: MediaFormat, key: String): String {
        if (!f.containsKey(key)) return "absent"
        return runCatching {
            when (val v = f.getByteBuffer(key)) {
                null -> f.getString(key) ?: f.getInteger(key).toString()
                else -> v.duplicate().let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    .joinToString("") { "%02x".format(it) }
            }
        }.getOrElse { runCatching { f.getInteger(key).toString() }.getOrDefault("?") }
    }

    private fun segmentDurationUs(f: MediaFormat): Long =
        if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L

    /** One track being copied: its extractor, the muxer output track index, and a grow-on-demand buffer. */
    private class Src(var ext: MediaExtractor, val outTrack: Int, var buf: ByteBuffer, var eos: Boolean = false)

    /**
     * Copy the next sample of [s] verbatim; sets [Src.eos] and returns without writing at track end.
     * [offsetUs] shifts the timestamp — 0 for [mux] (pass through verbatim), the segment's absolute start
     * for [concat]. Sample PTS arrive in DECODE order and are NOT monotonic on any stream with B-frames,
     * so nothing here may assume they increase; a uniform shift is order-agnostic.
     */
    private fun writeOne(muxer: MediaMuxer, s: Src, info: MediaCodec.BufferInfo, offsetUs: Long = 0L) {
        val size = readWithGrow(s)
        if (size < 0) { s.eos = true; return }
        info.offset = 0
        info.size = size
        info.presentationTimeUs = s.ext.sampleTime + offsetUs
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
    private fun rotationOf(context: Context, video: TrackSource, vFormat: MediaFormat): Int {
        val fromFmt = if (vFormat.containsKey(MediaFormat.KEY_ROTATION))
            vFormat.getInteger(MediaFormat.KEY_ROTATION) else null
        val deg = fromFmt ?: run {
            val mmr = MediaMetadataRetriever()
            try {
                when (video) {
                    is TrackSource.FromUri -> mmr.setDataSource(context, video.uri)
                    is TrackSource.FromFile -> mmr.setDataSource(video.file.absolutePath)
                }
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            } finally {
                mmr.release()
            }
        } ?: 0
        return ((deg % 360) + 360) % 360
    }

}
