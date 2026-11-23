package com.haithamassoli.naqi.desktop.audio

import com.haithamassoli.naqi.audio.DemucsSeparator
import com.haithamassoli.naqi.audio.MusicGate
import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.desktop.media.AacWriter
import com.haithamassoli.naqi.desktop.media.AudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

// Port of Android's AudioPipeline.removeMusic (the plain, non-resumable route) onto core's
// DemucsSeparator and MusicGate. The chunking, gate dilation, passthrough and overlap-add all live in core;
// this file only supplies the PCM, the two ORT sessions and the progress mapping.

object MusicRemoval {
    /**
     * Remove the music from [input]'s audio and write what is left to [outM4a] (AAC-LC, 44.1 kHz stereo,
     * same length in samples as the decoded source). [keepStems] is `FilterOps.keepStems`: "vocals" or
     * "vocals_other". [durationMs] (from `Probe`) is only a progress denominator and decides whether the
     * YAMNet gate runs (Android's `openIfLong`: 10 min or more). A missing YAMNet means "separate
     * everything", a missing htdemucs throws.
     *
     * Returns false, writing nothing, when the source has no audio stream. [onProgress] gets 0..100.
     * Cancellable.
     */
    suspend fun run(
        input: File,
        keepStems: String,
        outM4a: File,
        durationMs: Long,
        modelsDir: File = Models.dir(),
        onProgress: (Int) -> Unit,
    ): Boolean {
        var writer: AacWriter? = null
        try {
            val had = removeMusic(
                decode = { onPcm -> AudioDecoder.decode(input, onPcm) },
                keepStems = keepStems,
                estFrames = durationMs * SAMPLE_RATE / 1000,
                modelsDir = modelsDir,
                // Opened lazily so a source with no audio leaves no file behind.
                write = { buf, n -> (writer ?: AacWriter(outM4a).also { writer = it }).write(buf, n) },
                onProgress = onProgress,
            )
            writer?.close()
            writer = null
            return had
        } catch (t: Throwable) {
            runCatching { writer?.close() }
            outM4a.delete() // a cancel or failure must not leave a short .m4a that looks finished
            throw t
        }
    }

    /**
     * The PCM-level pipeline, with the decoder and the sink as parameters so the tests can run it on
     * synthetic audio. [decode] has [AudioDecoder.decode]'s shape and is called twice: a stats pass for
     * the mono-mix mean/std DemucsSeparator normalizes by, then the separation pass. Android samples the
     * stats from windows across the track; here both passes are full decodes, which on a desktop costs
     * seconds against minutes of htdemucs.
     *
     * [gate] defaults to Android's rule. Exposed so a test can exercise the passthrough on a short clip.
     */
    internal suspend fun removeMusic(
        decode: suspend (onPcm: suspend (FloatArray, Int) -> Unit) -> Boolean,
        keepStems: String,
        estFrames: Long,
        modelsDir: File,
        write: (FloatArray, Int) -> Unit,
        onProgress: (Int) -> Unit,
        gate: Boolean = estFrames >= MusicGate.LONG_FRAMES,
    ): Boolean = withContext(Dispatchers.Default) {
        var sum = 0.0
        var sumsq = 0.0
        var count = 0L
        val hasAudio = decode { buf, n ->
            currentCoroutineContext().ensureActive()
            for (i in 0 until n) {
                val m = (buf[2 * i] + buf[2 * i + 1]).toDouble() * 0.5
                sum += m
                sumsq += m * m
            }
            count += n
        }
        if (!hasAudio) return@withContext false
        if (count == 0L) error("Could not decode any audio from this video.")
        val mean = (sum / count).toFloat()
        // Sample std with N-1 (Bessel), as Android: the normalization the model was trained with.
        val variance = if (count > 1) ((sumsq - sum * sum / count) / (count - 1)).coerceAtLeast(0.0) else 0.0
        val std = sqrt(variance).toFloat()
        var lastPct = 2
        onProgress(lastPct)

        val keepOther = keepStems == "vocals_other"
        val musicGate = if (gate) openGate(modelsDir) else null
        try {
            HtdemucsSession(File(modelsDir, Models.HTDEMUCS), DemucsSeparator.keptStems(keepOther)).use { session ->
                val separator = DemucsSeparator(
                    keepOther = keepOther,
                    mean = mean,
                    std = std,
                    // The real count from the stats pass, so the bar is exact; the container's figure
                    // is only the fallback the gate decision above already used.
                    estimatedFrames = count,
                    infer = session::infer,
                    onChunk = { done, total ->
                        val pct = 2 + 96 * done / total
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    },
                    musicScore = musicGate?.let { it::score },
                    emit = write,
                )
                // The decode sink is the only suspension point, so a cancel lands between PCM batches,
                // after at most the htdemucs chunks that batch completes (~1 s each on an M3).
                decode { buf, n ->
                    currentCoroutineContext().ensureActive()
                    separator.feed(buf, n)
                }
                separator.finish()
                if (separator.framesFed != count) {
                    // The two passes disagree: the source decodes nondeterministically or was truncated in
                    // between. The output length would follow the second pass; refuse rather than guess.
                    error("Audio decoded to $count frames, then ${separator.framesFed}; did the file change?")
                }
            }
        } finally {
            runCatching { musicGate?.close() }
        }
        onProgress(100)
        true
    }

    private const val SAMPLE_RATE = 44_100L
}
