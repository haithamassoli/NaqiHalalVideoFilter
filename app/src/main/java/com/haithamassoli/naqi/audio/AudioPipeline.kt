package com.haithamassoli.naqi.audio

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * M2 music-removal entry point — the single seam `work/` sees for the audio side. Composes the
 * audio package (AudioDecoder → DemucsSeparator ← HtdemucsSession → AacWriter, gated by MusicGate)
 * into a sampled stats pass for the mono-mix mean/std the demucs driver needs, then a streaming pass
 * that feeds interleaved f32 stereo 44.1k into the chunked overlap-add separator, whose kept-stem
 * output is AAC-encoded into [File] tempM4a. 44.1 kHz end to end since A6 — nothing in this package
 * resamples on egress.
 *
 * Progress is chunk-count-driven (the natural unit — htdemucs chunks dominate wall time): [onProgress]
 * reports 2 after the stats pass, 2..98 across separation chunks, 100 at the end. [isCancelled] is
 * polled between chunks (inside the separator) and in the stream sink; a true reading throws
 * CancellationException so WorkManager sees the job as cancelled.
 */
object AudioPipeline {

    private const val TAG = "AudioPipeline"

    /** ~1 s at 44.1 kHz. Container durations are routinely loose by a few hundred ms; a second is not. */
    private const val LENGTH_WARN_FRAMES = 44_100L

    /**
     * PRD "Thermal: chunked work yields between segments; no hard fail on throttle, just slower."
     * Called between htdemucs chunks — the only natural seam, since one chunk is an uninterruptible
     * ONNX run. Blocking sleep is the point: the CPU must go idle for the SoC to shed heat, and this
     * thread has nothing else to do.
     *
     * [demoteWhile] is S1's thermal demotion (`plan-v2` §5.8). Once a video branch runs alongside this
     * one, a 2 s yield no longer idles the SoC — the sibling simply takes the cores back, so the pause
     * buys nothing and the device keeps heating. At SEVERE the separator therefore blocks at this chunk
     * boundary until the caller says the sibling is done, which IS the demotion to sequential the plan
     * asks for, using the seam that already exists rather than a new one.
     */
    private fun thermalYield(context: Context, isCancelled: () -> Boolean, demoteWhile: () -> Boolean) {
        if (isCancelled()) return
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        val pause = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> return
            PowerManager.THERMAL_STATUS_MODERATE -> 500L
            else -> 2_000L // SEVERE and above: back off hard rather than let the system kill us
        }
        if (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE && demoteWhile()) {
            Log.w(TAG, "thermal status ${pm.currentThermalStatus} — demoting to sequential at this chunk")
            while (demoteWhile() && !isCancelled()) runCatching { Thread.sleep(500L) }
        }
        Log.i(TAG, "thermal status ${pm.currentThermalStatus} — yielding ${pause}ms")
        runCatching { Thread.sleep(pause) }
    }

    /**
     * The one number `plan-v2` §5.9 says has to exist before anyone ranks A5: how the separate stage
     * actually splits between the Kotlin STFT, ORT, and the iSTFT + overlap-add. Logged at the end of a
     * run rather than per chunk — the separator cannot be referenced from inside its own constructor's
     * `onChunk`, and a resumed run logs its own segment's split anyway.
     */
    private fun logStageSplit(s: DemucsSeparator) {
        Log.i(
            TAG,
            "separate split: stft=${s.stftNs / 1_000_000}ms ort=${s.inferNs / 1_000_000}ms " +
                "istft+ola=${s.olaNs / 1_000_000}ms (rest of wall = ring gather, flush, decode, thermal)",
        )
    }

    /**
     * The three things only the caller can say about a finished separator run:
     *
     * - **the A1 skip rate**, which is the number that tells the next person whether the gate was worth
     *   it on real content (`plan-v2` §4.5 measured the duty cycle on a corpus; this measures THIS film);
     * - **non-finite samples**, tolerated by the separator and silenced, so otherwise invisible;
     * - **correctness item 7.5** — the stream delivered a different number of frames than the container
     *   claimed. Since A3 that shortens the output honestly instead of zero-padding it to the declared
     *   length, so this is a warning and not a failure: [estFrames] is an estimate and a source whose
     *   container duration is loose must not lose a job over it.
     */
    private fun logRun(s: DemucsSeparator, estFrames: Long) {
        val pct = if (s.chunksDone > 0) 100 * s.skippedChunks / s.chunksDone else 0
        Log.i(TAG, "music gate: skipped ${s.skippedChunks}/${s.chunksDone} chunks ($pct%) fed=${s.framesFed} frames")
        if (s.nonFinite > 0) Log.w(TAG, "separator produced ${s.nonFinite} non-finite samples (silenced)")
        val delta = s.framesFed - estFrames
        if (estFrames > 0 && abs(delta) > LENGTH_WARN_FRAMES) {
            Log.w(TAG, "stream delivered ${s.framesFed} frames, container said $estFrames (delta $delta)")
        }
        logStageSplit(s)
    }

    /**
     * Decode [uri]'s audio → the A1 music gate → htdemucs chunked overlap-add → keep only [keepStems]
     * ("vocals" | "vocals_other") → AAC-LC 44.1 kHz 192 kbps into [tempM4a]. Empty/undecodable audio
     * fails with a clear message rather than emitting a silent .m4a — from the stats pass when it can
     * decode nothing at all, and from the frame count the separator was actually fed otherwise.
     *
     * [demoteWhile] is S1's hook and defaults to "no sibling branch"; see [thermalYield].
     */
    suspend fun removeMusic(
        context: Context,
        uri: Uri,
        keepStems: String,
        tempM4a: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        jobDir: File? = null,
        demoteWhile: () -> Boolean = { false },
    ) = withContext(Dispatchers.Default) {
        if (jobDir != null) {
            removeMusicResumable(context, uri, keepStems, tempM4a, onProgress, isCancelled, jobDir, demoteWhile)
            return@withContext
        }
        val stats = AudioDecoder.stats(context, uri, isCancelled)
        val estFrames = AudioDecoder.estimateFrames(context, uri)
        Log.i(TAG, "stats: mean=${stats.mean} std=${stats.std} firstPtsUs=${stats.firstPtsUs} estFrames=$estFrames")
        var lastPct = 2
        onProgress(lastPct)

        val keepOther = keepStems == "vocals_other"
        // A1: null when the model is not installed, and then every chunk is separated — see MusicGate.open.
        val gate = MusicGate.open(context)
        try {
            // A4: the session copies only the kept stems out of ORT, so it is built from the same set the
            // separator keeps — one derivation, no way for the two to disagree about what the arrays hold.
            HtdemucsSession(context, DemucsSeparator.keptStems(keepOther)).use { session ->
                // Sources with an AAC priming edit report a small NEGATIVE first PTS; MediaMuxer rejects
                // negative sample times, and our re-encode introduces its own priming anyway — clamp to 0.
                val writer = AacWriter(tempM4a, stats.firstPtsUs.coerceAtLeast(0L))
                try {
                    // Reset AFTER thermalYield, so a throttle pause is not billed to the next chunk.
                    var tChunk = System.nanoTime()
                    val separator = DemucsSeparator(
                        keepOther = keepOther,
                        mean = stats.mean,
                        std = stats.std,
                        estimatedFrames = estFrames,
                        infer = session::infer,
                        onChunk = { done, total ->
                            Log.i(TAG, "chunk $done/$total ${(System.nanoTime() - tChunk) / 1_000_000}ms")
                            // Post only when the integer percent actually moves: ~4800 chunks on a film map
                            // onto 96 distinct values, and every call is a setProgressAsync + a
                            // setForegroundAsync. The identical bug in analyze measured −12.2 % when fixed
                            // (`perf-plan.md` 1.1); `plan-v2` §5.9 asks for the same guard here.
                            val pct = 2 + 96 * done / total // 0..total -> 2..98
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            thermalYield(context, isCancelled, demoteWhile)
                            tChunk = System.nanoTime()
                        },
                        isMusic = gate?.let { it::isMusic },
                        emit = writer::write,
                    )
                    AudioDecoder.stream(context, uri, isCancelled) { buf, n ->
                        if (isCancelled()) throw CancellationException()
                        separator.feed(buf, n)
                    }
                    separator.finish()
                    // The stats pass no longer decodes the whole track (A3), so this is where an
                    // undecodable stream is finally provable — and it costs nothing, since an empty
                    // stream means the loop above did nothing.
                    if (separator.framesFed == 0L) error("Could not decode any audio from this video.")
                    logRun(separator, estFrames)
                    writer.finish()
                    onProgress(100)
                } finally {
                    runCatching { writer.close() } // safe after finish or on error; don't mask the original throw
                }
            }
        } finally {
            runCatching { gate?.close() }
        }
    }

    /**
     * Transcode [uri]'s audio track to AAC-LC 44.1 kHz 192 kbps into [tempM4a] — [removeMusic] with the
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
     * (2) A 48 kHz source is still resampled down to 44.1 by [AudioDecoder] for nothing — 44.1 is
     * htdemucs's rate and there is no htdemucs here — and it is now a one-way trip, since A6 deleted the
     * 44.1 -> 48 leg on the way back out. Half the damage gone; the remaining half is a decoder-side
     * change. The upgrade path for both ceilings is the same and is a different shape of change: run this
     * as an audio-only media3 Transformer export, which downmixes with proper gain and keeps the source
     * rate. Worth it if a real 5.1 film ever sounds wrong — no such asset exists in `qa-assets/` to
     * measure against today.
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
     * - **The scratch is 44.1 kHz because that is the separator's rate.** Nothing converts it: since A6
     *   the whole chain is 44.1 in and 44.1 out. So 1 scratch frame == 1 separator frame, which is what
     *   makes `framesEmitted * 4` an exact byte offset and the resume seam a plain file append.
     *
     * int16 costs 635 MB per hour of source (measured 87.3 dB round-trip SNR, ~50 dB below what AAC-LC at
     * 192 kbps discards); f32 would double that for nothing, and disk is wall #4.
     *
     * The stats pass is skipped on resume: `mean`/`std` normalize on feed and are inverted on emit, so
     * re-deriving them from a sample that drew different windows would step the level mid-film.
     *
     * **`stats.frames` is the completion marker** (A3). It used to be the whole-track frame count from a
     * full stats decode; the sampled pass cannot count frames, so it writes 0 and the true total is the
     * one [DemucsSeparator.framesFed] learns from the stream, persisted here the moment a COMPLETE run
     * resolves it. Reading 0 therefore means "the separator still owes work", which is the only safe
     * direction: re-running a finished separator costs one audio decode with every chunk skipped, while
     * skipping an unfinished one truncates the user's film. The estimate is never used for that decision.
     */
    private suspend fun removeMusicResumable(
        context: Context,
        uri: Uri,
        keepStems: String,
        tempM4a: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean,
        jobDir: File,
        demoteWhile: () -> Boolean,
    ) {
        val pcm = File(jobDir, "audio.pcm")
        val saved = Checkpoint.readAudio(jobDir)
        val stats = saved?.stats ?: AudioDecoder.stats(context, uri, isCancelled).also {
            Checkpoint.writeAudio(jobDir, 0L, it)
        }
        // The checkpoint is authoritative, but never claim more than the file actually holds: a power loss
        // can leave a size that outran its durable data. One min() is correct in both directions.
        var written = minOf(saved?.framesEmitted ?: 0L, pcm.length() / 4)
        if (pcm.length() != written * 4) {
            RandomAccessFile(pcm, "rw").use { it.setLength(written * 4) }
        }
        // Cosmetic only, and recomputed rather than persisted: once `stats.frames` is non-zero it IS the
        // true length, and before that the container's word for it is all there is.
        val estFrames = if (stats.frames > 0L) stats.frames else AudioDecoder.estimateFrames(context, uri)
        Log.i(TAG, "resumable audio: total=${stats.frames} est=$estFrames alreadyWritten=$written pcm=${pcm.length()}B")
        // Start the bar where the previous run left off, not at 2%. The skip phase re-decodes without
        // reporting anything (it must not — see DemucsSeparator.processChunk), which on a film is a couple
        // of minutes; from 2% that reads as a hang, from the resumed percentage it reads as resuming.
        // Doubles as the seed for the per-chunk guard below, so resuming never re-posts this same value.
        var lastPct = (2 + 88 * written / estFrames.coerceAtLeast(1)).toInt().coerceIn(2, 90)
        onProgress(lastPct)

        var total = stats.frames
        if (total == 0L || written < total) {
            val keepOther = keepStems == "vocals_other"
            val gate = MusicGate.open(context) // A1; null => separate everything, see MusicGate.open
            try {
                HtdemucsSession(context, DemucsSeparator.keptStems(keepOther)).use { session ->
                    FileOutputStream(pcm, /* append = */ true).use { out ->
                        val quantized = ByteArray(4 * DemucsSeparator.STRIDE) // one flush batch, one write
                        // As above; discard the first line of a RESUMED run — skipped chunks report nothing.
                        var tChunk = System.nanoTime()
                        val separator = DemucsSeparator(
                            keepOther = keepOther,
                            mean = stats.mean,
                            std = stats.std,
                            estimatedFrames = estFrames,
                            infer = session::infer,
                            onChunk = { done, t ->
                                Log.i(TAG, "chunk $done/$t ${(System.nanoTime() - tChunk) / 1_000_000}ms")
                                // Post only on a real percent change — see removeMusic's onChunk.
                                val pct = 2 + 88 * done / t // 0..t -> 2..90; 90..100 is the AAC pass
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                                // After the flush, so `written` is current. Skipped while stopping, so a
                                // cancel racing JobStore.delete cannot re-create the file it just removed.
                                // `stats` still carries frames=0 here — the true length is only written
                                // once the run below completes.
                                if (!isCancelled()) Checkpoint.writeAudio(jobDir, written, stats)
                                thermalYield(context, isCancelled, demoteWhile)
                                tChunk = System.nanoTime()
                            },
                            resumeFrames = written,
                            isMusic = gate?.let { it::isMusic },
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
                        )
                        AudioDecoder.stream(context, uri, isCancelled) { buf, n ->
                            if (isCancelled()) throw CancellationException()
                            separator.feed(buf, n) // skipped chunks cost ~0; the ring still fills from real input
                        }
                        separator.finish()
                        if (separator.framesFed == 0L) error("Could not decode any audio from this video.")
                        logRun(separator, estFrames)
                        total = separator.framesFed
                    }
                }
            } finally {
                runCatching { gate?.close() }
            }
            // The completion marker, written only here: `total` is now the length the stream actually had.
            Checkpoint.writeAudio(jobDir, written, AudioDecoder.Stats(total, stats.mean, stats.std, stats.firstPtsUs))
        }
        // Still the invariant that catches a short scratch — now against the length the separator measured
        // (this run) or recorded (a previous complete run), rather than against a declared total.
        check(pcm.length() == total * 4) { "pcm ${pcm.length()} != ${total * 4}" }

        // ONE AAC encode, at the end, over the whole scratch.
        val writer = AacWriter(tempM4a, stats.firstPtsUs.coerceAtLeast(0L))
        try {
            encodePcm(pcm, writer, onProgress, isCancelled)
            writer.finish()
            onProgress(100)
        } finally {
            runCatching { writer.close() }
        }
    }

    /**
     * Stream the int16 scratch back through [AacWriter] as f32, unity-gain inverse of the quantizer.
     * Both sides are 44.1 kHz since A6, so this pass now only quantizes and encodes.
     */
    private fun encodePcm(pcm: File, writer: AacWriter, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val bytes = ByteArray(4 * DemucsSeparator.STRIDE)
        val floats = FloatArray(2 * DemucsSeparator.STRIDE)
        val total = pcm.length().coerceAtLeast(1L)
        var done = 0L
        var lastPct = 89 // this pass only ever posts 90..99; 89 makes the first real value post
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
                // Post only on a real percent change — see removeMusic's onChunk.
                val pct = (90 + 10 * done / total).toInt().coerceAtMost(99)
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
        }
    }
}
