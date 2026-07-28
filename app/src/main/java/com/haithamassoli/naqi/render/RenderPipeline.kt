@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.haithamassoli.naqi.render

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.media.firstTrackFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

/**
 * M1 pass-2 driver: a Media3 Transformer export that applies [CensorGlEffect] per frame and re-encodes
 * H.264 within a resolution-tiered bitrate cap, tone-mapping HDR to SDR, while transmuxing the source
 * audio untouched (the censor-only audio passthrough fast path). Rotation survives either as
 * pre-rotated frames or as forwarded metadata (decoder-dependent); [CensorGlEffect] detects which
 * from [meta] (FrameSampler.probe) and maps EDL rects accordingly.
 */
/**
 * One slice of the timeline to render on its own (`long-film-plan.md` Phase 2). [index] only names the
 * output file; [startMs]/[endMs] are absolute source times, and [startMs] doubles as the EDL offset
 * because a clipped export's timestamps are rebased to 0 (see [CensorGlEffect.drawFrame]).
 */
data class RenderSegment(val index: Int, val startMs: Long, val endMs: Long)

object RenderPipeline {

    private const val PROGRESS_POLL_MS = 500L

    /**
     * @param segment null renders the whole timeline exactly as M1 did — audio transmuxed alongside, one
     *   file, no mux step. Non-null renders just that slice, and **drops audio**: per-segment AAC could
     *   not be concatenated (encoder frames do not align with arbitrary clip boundaries), so the
     *   segmented path assembles video only and muxes one continuous audio track at the end.
     */
    suspend fun renderCensor(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        edl: Edl,
        blurAmount: Int,
        grayscale: Boolean,
        meta: VideoMeta,
        segment: RenderSegment? = null,
        onProgress: (Int) -> Unit,
    ) {
        outputFile.parentFile?.mkdirs()
        // Derived from the SOURCE, so every segment of one job is encoded at the same bitrate — a
        // precondition for the concat, which can only carry one track format.
        val bitrate = withContext(Dispatchers.IO) { resolveBitrate(context, inputUri) }

        val mediaItem = MediaItem.Builder().setUri(inputUri).apply {
            if (segment != null) {
                // Frame-accurate on the transcoding path: the decoder starts at the preceding sync
                // sample and frames whose rebased timestamp is negative are dropped
                // (media3 1.10.1 ExoAssetLoaderVideoRenderer.java:186).
                setClipStartPositionMs(segment.startMs)
                setClipEndPositionMs(segment.endMs)
            }
        }.build()

        // Video re-encoded with the censor effect. Whole-timeline: audio has no processors and is not
        // removed, so Transformer transmuxes it (the M1 no-audio-re-encode fast path).
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(segment != null)
            .setEffects(
                Effects(
                    /* audioProcessors = */ emptyList(),
                    listOf<Effect>(CensorGlEffect(edl, blurAmount, grayscale, meta, segment?.startMs ?: 0L)),
                ),
            )
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(editedItem).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()

        // Transformer is single-threaded: build/start/poll/cancel all run on the Looper thread (Main).
        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .build()
            coroutineScope {
                val poller = launch {
                    val holder = ProgressHolder()
                    while (true) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(holder.progress.coerceIn(0, 100))
                        }
                        delay(PROGRESS_POLL_MS)
                    }
                }
                try {
                    suspendCancellableCoroutine<Unit> { cont ->
                        transformer.addListener(object : Transformer.Listener {
                            override fun onCompleted(exportedComposition: Composition, result: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(exportedComposition: Composition, result: ExportResult, exception: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exception)
                            }
                        })
                        // Cancellation may arrive off-thread; Transformer.cancel() must run on its Looper (Main).
                        cont.invokeOnCancellation {
                            Handler(Looper.getMainLooper()).post { runCatching { transformer.cancel() } }
                        }
                        transformer.start(composition, outputFile.absolutePath)
                    }
                } finally {
                    poller.cancel() // stop polling on completion, error, or cancellation
                }
            }
        }
    }

    /** Effective encode bitrate = min(source bitrate when known, resolution-tier cap). */
    private fun resolveBitrate(context: Context, uri: Uri): Int {
        var pixels = 0L
        var sourceBitrate: Int? = null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.firstTrackFormat("video/")?.let { format ->
                val w = format.intOrNull(MediaFormat.KEY_WIDTH) ?: 0
                val h = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: 0
                pixels = w.toLong() * h
                sourceBitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE) // per-track, video-only
            }
        } catch (_: Exception) {
        } finally {
            extractor.release()
        }

        if (pixels == 0L || sourceBitrate == null) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                if (pixels == 0L) {
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    pixels = w.toLong() * h
                }
                if (sourceBitrate == null) {
                    // Whole-file bitrate (incl. audio) — an accepted upper-bound fallback.
                    sourceBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                }
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }

        val cap = bitrateCap(pixels)
        return sourceBitrate?.takeIf { it > 0 }?.let { min(it, cap) } ?: cap
    }

    /** Bitrate cap by output pixel count. Bounds are widescreen pixel counts so wide/tall variants bin correctly. */
    private fun bitrateCap(pixels: Long): Int = when {
        pixels <= 854L * 480 -> 2_500_000     // <=480p
        pixels <= 1280L * 720 -> 5_000_000    // <=720p
        pixels <= 1920L * 1080 -> 8_000_000   // <=1080p
        pixels <= 2560L * 1440 -> 14_000_000  // <=1440p
        else -> 25_000_000
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        try { getInteger(key) } catch (_: Exception) { null } // getInteger throws when the key is absent (< API 29)
}
