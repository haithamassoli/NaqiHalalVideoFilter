package com.haithamassoli.naqi.download

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * What this device decodes in hardware, for [Downloader.formatArgs] to pick a format the filter
 * pipeline can open fast — a software AV1 decode of a 1080p film is the difference between minutes and
 * an hour, and no download flag buys that back.
 *
 * `isHardwareAccelerated` is API 29, which is our minSdk, so no `OMX.google.` / `c2.android.` name
 * heuristics are needed.
 */
internal object DeviceCodecs {

    private const val TAG = "DeviceCodecs"

    /** yt-dlp-facing codec key → framework mime. */
    private val MIMES = mapOf(
        "avc" to MediaFormat.MIMETYPE_VIDEO_AVC,
        "hevc" to MediaFormat.MIMETYPE_VIDEO_HEVC,
        "vp9" to MediaFormat.MIMETYPE_VIDEO_VP9,
        "av1" to MediaFormat.MIMETYPE_VIDEO_AV1,
    )

    /** Landscape sizes; the smaller side is what yt-dlp's `res` sort compares, so this is the height. */
    private val SIZES = listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480)

    /** Largest height each codec hardware-decodes; a codec with no HW decoder is absent. Probed once. */
    val hwDecodeHeights: Map<String, Int> by lazy {
        runCatching { probe() }
            .onFailure { Log.w(TAG, "codec probe failed; format choice falls back to yt-dlp's defaults", it) }
            .getOrDefault(emptyMap())
            .also { Log.i(TAG, "hw decode heights: $it") }
    }

    private fun probe(): Map<String, Int> {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.isHardwareAccelerated }
        return MIMES.mapNotNull { (key, mime) ->
            val best = decoders
                .filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .mapNotNull { info ->
                    val caps = info.getCapabilitiesForType(mime).videoCapabilities ?: return@mapNotNull null
                    SIZES.firstOrNull { (w, h) -> caps.isSizeSupported(w, h) }?.second
                }
                .maxOrNull()
            best?.let { key to it }
        }.toMap()
    }
}
