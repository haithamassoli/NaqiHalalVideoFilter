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
import com.haithamassoli.naqi.media.intOrNull
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
 * One slice of the timeline to render on its own (`long-film-plan.md` Phase 2). [index] only names the
 * output file; [startMs]/[endMs] are absolute source times, and [startMs] doubles as the EDL offset
 * because a clipped export's timestamps are rebased to 0 (see [CensorGlEffect.drawFrame]).
 */
data class RenderSegment(val index: Int, val startMs: Long, val endMs: Long)

/**
 * M1 pass-2 driver: a Media3 Transformer export that applies [CensorGlEffect] per frame and re-encodes
 * H.264 within a resolution-tiered bitrate cap, tone-mapping HDR to SDR, while transmuxing the source
 * audio untouched (the censor-only audio passthrough fast path). Rotation survives either as
 * pre-rotated frames or as forwarded metadata (decoder-dependent); [CensorGlEffect] detects which
 * from the probed [VideoMeta] and maps EDL rects accordingly.
 *
 * When the EDL is empty there is nothing to draw, and the whole export collapses to a container copy —
 * see the passthrough guard in [renderCensor].
 */
object RenderPipeline {

    private const val PROGRESS_POLL_MS = 500L

    /**
     * Bitrate multiplier over the source, so re-encoding does not visibly soften an otherwise
     * untouched frame. Tune here if output size matters more than fidelity.
     */
    private const val GEN2_HEADROOM = 1.3f

    /**
     * @param segment null renders the whole timeline exactly as M1 did — audio transmuxed alongside, one
     *   file, no mux step. Non-null renders just that slice, and **drops audio**: per-segment AAC could
     *   not be concatenated (encoder frames do not align with arbitrary clip boundaries), so the
     *   segmented path assembles video only and muxes one continuous audio track at the end.
     * @param removeAudio for whole-timeline callers that mux their own audio afterwards — the combined
     *   job, whose `Remux.mux` reads only the video track back out of this file, so everything
     *   Transformer wrote into the audio track is thrown away. `video-performance-plan-v2.md` 5.9 S2:
     *   for an AAC source that is a wasted transmux of a few hundred MB; for a non-AAC source (MKV/Opus,
     *   AC-3) media3 cannot transmux at all, so it is a full audio decode + AAC encode that is then
     *   discarded — measured 12.9 s per 193 s track, ~10 min on a film. The default false keeps the
     *   censor-only shape exactly: audio transmuxed alongside, one file, no mux step.
     * @param bitrate the job's already-resolved encode bitrate; null resolves it here. One bitrate per
     *   job is an invariant, not a tuning knob — see [resolveBitrate]. Segmented callers resolve it once
     *   before their loop instead of re-probing the container 35–70 times per film (5.9 S3).
     */
    suspend fun renderCensor(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        edl: Edl,
        blurAmount: Int,
        grayscale: Boolean,
        meta: VideoMeta,
        solidColor: Int = com.haithamassoli.naqi.model.FilterOps.BLUR,
        segment: RenderSegment? = null,
        removeAudio: Boolean = false,
        bitrate: Int? = null,
        onProgress: (Int) -> Unit,
    ) {
        outputFile.parentFile?.mkdirs()

        // S4 (`video-performance-plan-v2.md` 5.9). Nothing to censor => hand Transformer an EMPTY effects
        // list. That is the whole mechanism: TransformerUtil.shouldTranscodeVideo() ends with
        // `!combinedEffects.isEmpty() && …`, so an empty list makes it skip decoder, GL and encoder and
        // do a container copy. A CensorGlEffect that happens to draw nothing still costs the full
        // decode -> GL -> encode, so the effect has to be ABSENT, not idle. Same reason the H.264 mime
        // type, the HDR tone-map mode and the encoder factory are all withheld below — each one forces a
        // transcode on its own, before shouldTranscodeVideo ever looks at the effects.
        //
        // JOB LEVEL ONLY. Do not extend this per segment, however tempting (on a film most segments
        // contain no regions): a transmuxed segment carries the SOURCE codec configuration and a
        // re-encoded one carries the encoder's, and Remux.concat can only write one track format —
        // MediaMuxer exposes no way to put a second stsd entry in a single track. A mixed concat produces
        // a file whose second sample entry is silently wrong. Hence the `segment == null`.
        val passthrough = segment == null && edl.censorIntervalsMs.isEmpty() && edl.faceTracks.isEmpty()

        val mediaItem = MediaItem.Builder().setUri(inputUri).apply {
            if (segment != null) {
                // Frame-accurate on the transcoding path: the decoder starts at the preceding sync
                // sample and frames whose rebased timestamp is negative are dropped
                // (media3 1.10.1 ExoAssetLoaderVideoRenderer.java:186).
                setClipStartPositionMs(segment.startMs)
                setClipEndPositionMs(segment.endMs)
            }
        }.build()

        // Video re-encoded with the censor effect. Whole-timeline without [removeAudio]: audio has no
        // processors and is not removed, so Transformer transmuxes it (the M1 no-audio-re-encode fast
        // path, which is what a censor-only job wants — it publishes this file directly).
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(segment != null || removeAudio)
            .setEffects(
                if (passthrough) Effects.EMPTY else Effects(
                    /* audioProcessors = */ emptyList(),
                    listOf<Effect>(
                        CensorGlEffect(edl, blurAmount, grayscale, meta, segment?.startMs ?: 0L, solidColor),
                    ),
                ),
            )
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(editedItem).build())
            // Left at the default HDR_MODE_KEEP_HDR on the passthrough path: it is the only mode that
            // does not force a transcode, so an untouched HDR source now stays HDR instead of being
            // tone-mapped into a copy of itself.
            .apply { if (!passthrough) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
            .build()

        // Null on the passthrough path, and not merely unused: DefaultEncoderFactory.videoNeedsEncoding()
        // is `!requestedVideoEncoderSettings.equals(DEFAULT)`, and shouldTranscodeVideo() returns true on
        // that before it reaches the effects list — a tuned factory alone would re-encode the whole film.
        // Resolving the bitrate goes with it, so a passthrough job opens no container of its own.
        // ponytail: if media3 then finds the source cannot be transmuxed into MP4 (VP9/WebM, say) it
        // transcodes anyway, at its own default bitrate rather than the tier below. Rare, and the only
        // alternative is probing the source codec here to decide — the cap is a ceiling, not a target.
        val encoderFactory = if (passthrough) null else DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(bitrate ?: resolveBitrate(context, inputUri))
                    // media3 defaults to 1s, which spends a big slice of the budget on I-frames.
                    // ponytail: 2s matches typical camera sources; no B-frames (setMaxBFrames) yet —
                    // add if 1.3x headroom below still isn't enough.
                    .setiFrameIntervalSeconds(2f)
                    // Latent-risk pin, NOT a performance change (`video-performance-plan-v2.md` 6b, last
                    // paragraph). Media3's KEY_OPERATING_RATE overflow workaround for SM8550 — this exact
                    // SoC — is guarded on SDK_INT 31..34, so from API 35 media3 goes back to requesting
                    // Integer.MAX_VALUE on a chipset Google blacklisted for throwing at configure.
                    // Rendering works on the current device; this just stops it becoming a crash later.
                    .setEncoderPerformanceParameters(/* operatingRate = */ 1000, /* priority = */ 1)
                    .build(),
            )
            .build()

        // Transformer is single-threaded: build/start/poll/cancel all run on the Looper thread (Main).
        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(context).apply {
                // Both force a transcode by themselves, so they are only requested when transcoding is
                // happening anyway. H.264 keeps every segment of a job in the one format Remux.concat can
                // write; requesting it on a passthrough job is exactly what would convert an HEVC source.
                if (encoderFactory != null) {
                    setVideoMimeType(MimeTypes.VIDEO_H264)
                    setEncoderFactory(encoderFactory)
                }
            }.build()
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

    /**
     * Effective encode bitrate = min(source bitrate x [GEN2_HEADROOM] when known, resolution-tier cap).
     * The headroom is not greed: this is a second-generation encode, so it has to spend bits
     * reproducing the source encoder's artifacts as well as the picture. At equal bitrate it always
     * looks worse than the source.
     *
     * Derived from the SOURCE, so ONE call per job feeds every [renderCensor] call of that job: identical
     * encoder settings per segment is a precondition for `Remux.concat`, which can only carry one track
     * format. Public because that hoist has to happen in the caller's segment loop — it opens a
     * MediaExtractor (and sometimes a MediaMetadataRetriever), i.e. 35–70 container opens on a film if
     * left per segment (`video-performance-plan-v2.md` 5.9 S3).
     */
    suspend fun resolveBitrate(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
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
        // Float, not Int: `it * 1.3` overflows Int above ~165 Mbps, whereas Float.toInt() saturates
        // to Int.MAX_VALUE and the min() then picks the cap.
        sourceBitrate?.takeIf { it > 0 }?.let { min((it * GEN2_HEADROOM).toInt(), cap) } ?: cap
    }


    /**
     * Bitrate cap by output pixel count, set near what phone cameras actually record so a camera
     * original is not halved on the way through. Bounds are widescreen pixel counts so wide/tall
     * variants bin correctly. A low-bitrate source still encodes low — the cap is a ceiling, not a
     * target, and [resolveBitrate] takes the min.
     */
    private fun bitrateCap(pixels: Long): Int = when {
        pixels <= 854L * 480 -> 4_000_000     // <=480p
        pixels <= 1280L * 720 -> 10_000_000   // <=720p
        pixels <= 1920L * 1080 -> 16_000_000  // <=1080p, vs ~17-20 Mbps camera original
        pixels <= 2560L * 1440 -> 24_000_000  // <=1440p
        else -> 45_000_000                    // 4K, vs ~45-50 Mbps camera original
    }
}
