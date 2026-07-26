package com.haithamassoli.naqi.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    /** True iff [uri] has at least one audio track. Preflight uses this for the no-audio error. */
    fun hasAudioTrack(context: Context, uri: Uri): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return true
                }
            }
            return false
        } finally {
            extractor.release()
        }
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
    ) = withContext(Dispatchers.Default) {
        val stats = AudioDecoder.stats(context, uri, isCancelled)
        if (stats.frames == 0L) error("Could not decode any audio from this video.")
        Log.i(TAG, "stats: frames=${stats.frames} mean=${stats.mean} std=${stats.std} firstPtsUs=${stats.firstPtsUs}")
        onProgress(2)

        HtdemucsSession(context).use { session ->
            // Sources with an AAC priming edit report a small NEGATIVE first PTS; MediaMuxer rejects
            // negative sample times, and our re-encode introduces its own priming anyway — clamp to 0.
            val writer = AacWriter(tempM4a, stats.firstPtsUs.coerceAtLeast(0L))
            try {
                val separator = DemucsSeparator(
                    keepOther = keepStems == "vocals_other",
                    mean = stats.mean,
                    std = stats.std,
                    totalFrames = stats.frames,
                    infer = session::infer,
                    onChunk = { done, total ->
                        Log.i(TAG, "chunk $done/$total")
                        onProgress(2 + 96 * done / total) // 0..total -> 2..98
                        thermalYield(context, isCancelled)
                    },
                    emit = writer::write,
                )
                AudioDecoder.stream(context, uri, isCancelled) { buf, n ->
                    if (isCancelled()) throw CancellationException()
                    separator.feed(buf, n)
                }
                separator.finish()
                writer.finish()
                onProgress(100)
            } finally {
                runCatching { writer.close() } // safe after finish or on error; don't mask the original throw
            }
        }
    }
}
