package com.haithamassoli.naqi.media

import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * "Which track is the video/audio one?" — asked by nearly every file that opens a [MediaExtractor]
 * (the sampler, the decoder, both mux paths, preflight, the bitrate probe). It was seven hand-rolled
 * copies of the same `for (i in 0 until trackCount)` loop; the loop is trivial, but seven of them is
 * seven places for a future edit to get the mime-prefix test subtly wrong.
 *
 * The extractor must already have its data source set. Neither function selects a track or mutates
 * any extractor state, so both are safe to call before [MediaExtractor.selectTrack].
 */

/** Index of the first track whose mime starts with [mimePrefix] (`"video/"` / `"audio/"`), or null. */
fun MediaExtractor.firstTrackIndex(mimePrefix: String): Int? =
    (0 until trackCount).firstOrNull {
        getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
    }

/** Format of the first track whose mime starts with [mimePrefix], or null when there is none. */
fun MediaExtractor.firstTrackFormat(mimePrefix: String): MediaFormat? =
    firstTrackIndex(mimePrefix)?.let { getTrackFormat(it) }

/** Index of the first [mimePrefix] track; throws when the source has none (callers that require one). */
fun MediaExtractor.requireTrackIndex(mimePrefix: String): Int =
    firstTrackIndex(mimePrefix) ?: throw IllegalArgumentException("no $mimePrefix track")
