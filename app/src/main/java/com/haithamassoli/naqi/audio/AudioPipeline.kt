package com.haithamassoli.naqi.audio

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import com.haithamassoli.naqi.media.requireTrackIndex
import com.haithamassoli.naqi.work.Checkpoint
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * M2 music-removal entry point — the single seam `work/` sees for the audio side. Composes the
 * audio package (AudioDecoder → DemucsSeparator ← HtdemucsSession → AacWriter) into a two-pass
 * decode: a stats pass computes the whole-track mono-mix mean/std the demucs driver needs, then a
 * streaming pass feeds interleaved f32 stereo 44.1k into the chunked overlap-add separator, whose
 * kept-stem output is resampled and AAC-encoded into [File] tempM4a.
 *
 * Progress is chunk-count-driven (the natural unit — htdemucs chunks dominate wall time): [onProgress]
 * reports 2 after the stats pass, 2..98 across separation chunks, 100 at the end. [isCancelled] is
 * polled between chunks (inside the separator) and in the stream sink; a true reading throws
 * CancellationException so WorkManager sees the job as cancelled.
 */
object AudioPipeline {

    private const val TAG = "AudioPipeline"

    /**
     * PRD "Thermal: chunked work yields between segments; no hard fail on throttle, just slower."
     * Called between htdemucs chunks — the only natural seam, since one chunk is an uninterruptible
     * ONNX run. Blocking sleep is the point: the CPU must go idle for the SoC to shed heat, and this
     * thread has nothing else to do. Pre-Q has no thermal API, so those devices just run hot.
     */
    private fun thermalYield(context: Context, isCancelled: () -> Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || isCancelled()) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val pause = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> return
            PowerManager.THERMAL_STATUS_MODERATE -> 500L
            else -> 2_000L // SEVERE and above: back off hard rather than let the system kill us
        }
        Log.i(TAG, "thermal status ${pm.currentThermalStatus} — yielding ${pause}ms")
        runCatching { Thread.sleep(pause) }
    }

    /**
     * Decode [uri]'s audio → htdemucs chunked overlap-add → keep only [keepStems] ("vocals" |
     * "vocals_other") → AAC-LC 48 kHz 192 kbps into [tempM4a]. Empty/undecodable audio (stats.frames
     * == 0) fails with a clear message rather than emitting a silent .m4a.
     */
    suspend fun removeMusic(
        context: Context,
        uri: Uri,
        keepStems: String,
        tempM4a: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        jobDir: File? = null,
    ) = withContext(Dispatchers.Default) {
        if (jobDir != null) {
            removeMusicResumable(context, uri, keepStems, tempM4a, onProgress, isCancelled, jobDir)
            return@withContext
        }
        val stats = AudioDecoder.stats(context, uri, isCancelled)
        if (stats.frames == 0L) error("Could not decode any audio from this video.")
        Log.i(TAG, "stats: frames=${stats.frames} mean=${stats.mean} std=${stats.std} firstPtsUs=${stats.firstPtsUs}")
        onProgress(2)

        HtdemucsSession(context).use { session ->
            // Sources with an AAC priming edit report a small NEGATIVE first PTS; MediaMuxer rejects
            // negative sample times, and our re-encode introduces its own priming anyway — clamp to 0.
            val writer = AacWriter(tempM4a, stats.firstPtsUs.coerceAtLeast(0L))
            try {
                // Reset AFTER thermalYield, so a throttle pause is not billed to the next chunk.
                var tChunk = System.nanoTime()
                val separator = DemucsSeparator(
                    keepOther = keepStems == "vocals_other",
                    mean = stats.mean,
                    std = stats.std,
                    totalFrames = stats.frames,
                    infer = session::infer,
                    onChunk = { done, total ->
                        Log.i(TAG, "chunk $done/$total ${(System.nanoTime() - tChunk) / 1_000_000}ms")
                        onProgress(2 + 96 * done / total) // 0..total -> 2..98
                        thermalYield(context, isCancelled)
                        tChunk = System.nanoTime()
                    },
                    emit = writer::write,
                )
                AudioDecoder.stream(context, uri, isCancelled) { buf, n ->
                    if (isCancelled()) throw CancellationException()
                    separator.feed(buf, n)
                }
                separator.finish()
                // Tolerated by DemucsSeparator.finish, so it would otherwise pass silently.
                if (separator.shortfall > 0) Log.w(TAG, "separator short by ${separator.shortfall} frames")
                if (separator.nonFinite > 0) Log.w(TAG, "separator produced ${separator.nonFinite} non-finite samples (silenced)")
                writer.finish()
                onProgress(100)
            } finally {
                runCatching { writer.close() } // safe after finish or on error; don't mask the original throw
            }
        }
    }

    /**
     * Transcode [uri]'s audio track to AAC-LC 48 kHz 192 kbps into [tempM4a] — [removeMusic] with the
     * separator deleted (`long-film-followups.md` item 1).
     *
     * One caller, one reason: [Remux.concat] copies the audio track sample-for-sample and framework
     * `MediaMuxer` carries only AAC/AMR, so an AC-3/E-AC-3/DTS film — or ANY MKV/WebM, which is Opus or
     * Vorbis — used to have to give up the segmented route entirely. That meant correct output with **no
     * resume on a ~3 h job**, which is the exact failure Phase 2 exists to remove. Transcode that one track
     * up front and the concat copies THAT instead.
     *
     * **This replaces one transcode with one transcode, not passthrough with a transcode.** The
     * unsegmented fallback had Transformer silently re-encoding the same track inside every export
     * already; bit-identical passthrough was never on offer for a source the muxer cannot carry. A source
     * it CAN carry never reaches here and still gets its own bytes back verbatim.
     *
     * [AudioDecoder.stream] emits exactly what [AacWriter] eats — interleaved f32 stereo 44.1 kHz, both
     * fixed constants — so the two compose with no glue but the soft clip; see the sink.
     *
     * ponytail: two ceilings, both inherited from reusing the separator's ingress/egress verbatim.
     * (1) A >2-channel source is **limited, not merely guarded** — the soft clip maps the un-normalized
     * BS.775 downmix's peaks into [0.95, 1), and a synthetic 5.1 asset measured +10.7 dBFS before it, so a
     * loud passage loses several dB of dynamics. Still strictly better than the hard clip that would
     * otherwise happen, and a source the muxer CAN copy never comes through here.
     * (2) A 48 kHz source is resampled 48 -> 44.1 (AudioDecoder) -> 48 (AacWriter) for nothing; 44.1 is
     * htdemucs's rate and there is no htdemucs here. The upgrade path for both is the same and is a
     * different shape of change: run this as an audio-only media3 Transformer export, which downmixes
     * with proper gain and keeps the source rate. Worth it if a real 5.1 film ever sounds wrong — no such
     * asset exists in `qa-assets/` to measure against today.
     */
    suspend fun transcodeToAac(
        context: Context,
        uri: Uri,
        tempM4a: File,
        isCancelled: () -> Boolean,
    ) = withContext(Dispatchers.Default) {
        val writer = AacWriter(tempM4a, firstAudioPtsUs(context, uri))
        try {
            var frames = 0L
            AudioDecoder.stream(context, uri, isCancelled) { buf, n ->
                // AacWriter.write's contract is "already soft-clipped upstream", and deleting the separator
                // deletes the only thing that ever satisfied it. This is not belt-and-braces: AudioDecoder's
                // >2-channel BS.775 downmix is deliberately UN-normalized (level is irrelevant to the
                // separator, which divides and re-multiplies by the same std), so a 5.1 film — precisely the
                // source this function exists for — arrives at |x| up to ~2.4 and AacWriter's int16
                // quantizer would hard-clip every peak. In place: buf is AudioDecoder's own hot buffer,
                // fully rewritten before each sink call.
                for (i in 0 until 2 * n) buf[i] = softclip(buf[i])
                writer.write(buf, n)
                frames += n
            }
            // Checked BEFORE finish(): with no samples the encoder never emits INFO_OUTPUT_FORMAT_CHANGED,
            // so the muxer is never started and the .m4a has no moov for the concat to open — a confusing
            // failure two stages later instead of a clear one here.
            if (frames == 0L) error("Could not decode any audio from this video.")
            writer.finish()
            Log.i(TAG, "transcodeToAac: $frames frames -> ${tempM4a.length()}B")
        } finally {
            runCatching { writer.close() } // safe after finish or on error; don't mask the original throw
        }
    }

    /**
     * The source audio track's first sample PTS, without decoding anything — the epoch [AacWriter] stamps
     * from, so the transcoded track lands where the source's own track did and keeps whatever inter-track
     * offset it authored.
     *
     * [AudioDecoder] derives this exact value from `extractor.sampleTime` on its first read, but only
     * hands it back once the WHOLE track is decoded, and [AacWriter] needs it in its constructor.
     * [removeMusic] reads it off the stats pass it has to run anyway; there is no stats pass here, and a
     * second full decode of a 155-min film's audio to learn one Long is not a trade worth making.
     *
     * Honestly: this is 0 on every asset measured here, and it only differs from 0 on a source whose audio
     * track starts later than its video. It is kept because that source exists and would otherwise have its
     * audio pulled early by exactly that offset, not because it has ever been observed to matter.
     */
    private fun firstAudioPtsUs(context: Context, uri: Uri): Long {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(context, uri, null)
            ext.selectTrack(ext.requireTrackIndex("audio/"))
            // Negative on a source carrying an AAC priming edit. MediaMuxer rejects negative sample times
            // and our own re-encode introduces its own priming anyway — same clamp, same reason, as
            // removeMusic's stats.firstPtsUs.coerceAtLeast(0L).
            ext.sampleTime.coerceAtLeast(0L)
        } finally {
            runCatching { ext.release() }
        }
    }

    /**
     * The resumable variant (`long-film-plan.md` Phase 2), taken only for a long source so the M2 5-min
     * route above stays byte-identical and device-verified.
     *
     * The separated stream goes to an append-only **int16 LE stereo 44.1 kHz** scratch file instead of
     * straight into the AAC encoder, and one AAC encode reads it back at the end. Two consequences make
     * this the whole mechanism:
     *
     * - **A kill loses at most one htdemucs chunk.** `audio.json` records how many frames are safely on
     *   disk; on resume [DemucsSeparator] re-runs exactly one chunk before that point to rebuild its
     *   overlap-add ring and everything from there is bit-identical to an uninterrupted run.
     * - **44.1 kHz, not 48.** Resampling before the scratch would restart the 44.1→48 Sonic session at the
     *   resume seam — a different interpolation phase, a click, and a rounding-different frame count. At
     *   44.1 kHz there is exactly ONE uninterrupted Sonic session at the end, and 1 scratch frame == 1
     *   separator frame, which is what makes `framesEmitted * 4` an exact byte offset.
     *
     * int16 costs 635 MB per hour of source (measured 87.3 dB round-trip SNR, ~50 dB below what AAC-LC at
     * 192 kbps discards); f32 would double that for nothing, and disk is wall #4.
     *
     * The stats pass is skipped on resume: `mean`/`std` normalize on feed and are inverted on emit, so
     * re-deriving them from a decode that differed by one frame would step the level mid-film, and
     * `totalFrames` fixes the entire chunk grid.
     */
    private suspend fun removeMusicResumable(
        context: Context,
        uri: Uri,
        keepStems: String,
        tempM4a: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        jobDir: File,
    ) {
        val pcm = File(jobDir, "audio.pcm")
        val saved = Checkpoint.readAudio(jobDir)
        val stats = saved?.stats ?: AudioDecoder.stats(context, uri, isCancelled).also {
            if (it.frames == 0L) error("Could not decode any audio from this video.")
            Checkpoint.writeAudio(jobDir, 0L, it)
        }
        // The checkpoint is authoritative, but never claim more than the file actually holds: a power loss
        // can leave a size that outran its durable data. One min() is correct in both directions.
        var written = minOf(saved?.framesEmitted ?: 0L, pcm.length() / 4)
        if (pcm.length() != written * 4) {
            RandomAccessFile(pcm, "rw").use { it.setLength(written * 4) }
        }
        Log.i(TAG, "resumable audio: frames=${stats.frames} alreadyWritten=$written pcm=${pcm.length()}B")
        // Start the bar where the previous run left off, not at 2%. The skip phase re-decodes without
        // reporting anything (it must not — see DemucsSeparator.processChunk), which on a film is a couple
        // of minutes; from 2% that reads as a hang, from the resumed percentage it reads as resuming.
        onProgress((2 + 88 * written / stats.frames.coerceAtLeast(1)).toInt())

        if (written < stats.frames) {
            HtdemucsSession(context).use { session ->
                FileOutputStream(pcm, /* append = */ true).use { out ->
                    val quantized = ByteArray(4 * DemucsSeparator.STRIDE) // one flush batch, one write
                    // As above; discard the first line of a RESUMED run — skipped chunks report nothing.
                    var tChunk = System.nanoTime()
                    val separator = DemucsSeparator(
                        keepOther = keepStems == "vocals_other",
                        mean = stats.mean,
                        std = stats.std,
                        totalFrames = stats.frames,
                        infer = session::infer,
                        onChunk = { done, total ->
                            Log.i(TAG, "chunk $done/$total ${(System.nanoTime() - tChunk) / 1_000_000}ms")
                            onProgress(2 + 88 * done / total) // 0..total -> 2..90; 90..100 is the AAC pass
                            // After the flush, so `written` is current. Skipped while stopping, so a cancel
                            // that is racing JobStore.delete cannot re-create the file it just removed.
                            if (!isCancelled()) Checkpoint.writeAudio(jobDir, written, stats)
                            thermalYield(context, isCancelled)
                            tChunk = System.nanoTime()
                        },
                        emit = { interleaved, frames ->
                            var b = 0
                            for (i in 0 until 2 * frames) {
                                val s = (interleaved[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
                                quantized[b++] = (s and 0xFF).toByte()
                                quantized[b++] = ((s shr 8) and 0xFF).toByte()
                            }
                            out.write(quantized, 0, b) // unbuffered: in the page cache once this returns
                            written += frames
                        },
                        resumeFrames = written,
                    )
                    AudioDecoder.stream(context, uri, isCancelled) { buf, n ->
                        if (isCancelled()) throw CancellationException()
                        separator.feed(buf, n) // skipped chunks cost ~0; the ring still fills from real input
                    }
                    separator.finish()
                    if (separator.shortfall > 0) Log.w(TAG, "separator short by ${separator.shortfall} frames")
                if (separator.nonFinite > 0) Log.w(TAG, "separator produced ${separator.nonFinite} non-finite samples (silenced)")
                }
            }
            Checkpoint.writeAudio(jobDir, written, stats)
        }
        // The invariant that can actually catch a short stream pass, unlike the separator's own frame
        // count (which is pinned to totalFrames by construction — see DemucsSeparator.finish).
        check(pcm.length() == stats.frames * 4) { "pcm ${pcm.length()} != ${stats.frames * 4}" }

        // ONE AAC encode, ONE uninterrupted 44.1 -> 48 kHz Sonic session, at the end.
        val writer = AacWriter(tempM4a, stats.firstPtsUs.coerceAtLeast(0L))
        try {
            encodePcm(pcm, writer, onProgress, isCancelled)
            writer.finish()
            onProgress(100)
        } finally {
            runCatching { writer.close() }
        }
    }

    /** Stream the int16 scratch back through [AacWriter] as f32, unity-gain inverse of the quantizer. */
    private fun encodePcm(pcm: File, writer: AacWriter, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val bytes = ByteArray(4 * DemucsSeparator.STRIDE)
        val floats = FloatArray(2 * DemucsSeparator.STRIDE)
        val total = pcm.length().coerceAtLeast(1L)
        var done = 0L
        pcm.inputStream().use { ins ->
            while (true) {
                if (isCancelled()) throw CancellationException()
                var n = 0
                while (n < bytes.size) {
                    val k = ins.read(bytes, n, bytes.size - n)
                    if (k < 0) break
                    n += k
                }
                val frames = n / 4
                if (frames == 0) break
                val shorts = ByteBuffer.wrap(bytes, 0, frames * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (i in 0 until frames * 2) floats[i] = shorts.get(i) / 32767f
                writer.write(floats, frames)
                done += n
                onProgress((90 + 10 * done / total).toInt().coerceAtMost(99))
            }
        }
    }
}
