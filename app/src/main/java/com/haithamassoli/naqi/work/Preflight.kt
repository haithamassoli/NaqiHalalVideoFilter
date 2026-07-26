package com.haithamassoli.naqi.work

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Source validation and the failure taxonomy (PRD "Preflight & failure"): every way a job can fail
 * gets a per-cause sentence the user can act on, instead of a stack trace.
 *
 * [check] runs BEFORE any foreground work so a doomed job never promotes to a foreground service.
 * It deliberately opens the source itself rather than trusting the picker: an unreadable, DRM'd or
 * exotic-container file only reveals itself when `MediaExtractor` touches it, and that throw used to
 * escape `doWork` entirely — WorkManager logged `Failed to instantiate extractor` and the UI showed
 * nothing at all (observed on device, 2026-07-26).
 *
 * [messageFor] is the backstop for everything that only fails deep in the pipeline (a codec the
 * device advertises but can't actually start, a disk that fills mid-render).
 */
internal object Preflight {

    const val DRM = "This video is copy-protected (DRM), so it can’t be filtered."
    const val UNREADABLE = "This file couldn’t be opened. It may be damaged or in a format this device doesn’t support."
    const val NO_VIDEO = "This file has no video track."
    const val NO_AUDIO = "This video has no audio track, so there’s no music to remove."
    const val LOW_SPACE = "Not enough free space. Filtering needs room for a temporary copy plus about 2 GB."
    const val UNSUPPORTED_CODEC = "This video uses a codec this device can’t decode."
    const val OUT_OF_SPACE = "The device ran out of space while saving the filtered copy."
    const val GENERIC = "Filtering failed."

    /** Headroom the PRD requires on top of the working copies. */
    private const val SLACK_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * @param needsAudio music removal was requested — a missing audio track is then fatal, not fine.
     * @param tempCopies working copies the job writes before publishing: censor-only and music-only
     *   each write one temp, combined writes a render temp AND a mux temp. The published output
     *   lands on the same filesystem as the cache on every modern device, so it is counted too —
     *   the PRD's "2× source + 2 GB" is exactly the one-temp case.
     * @return a user-facing message, or null when the job may proceed.
     */
    fun check(context: Context, uri: Uri, needsAudio: Boolean, tempCopies: Int): String? {
        val extractor = MediaExtractor()
        var hasVideo = false
        var hasAudio = false
        try {
            extractor.setDataSource(context, uri, null)
            // Non-empty PSSH = the container carries DRM init data. Checked before codec lookup
            // because a protected track otherwise fails later with an opaque crypto error.
            if (extractor.psshInfo?.isNotEmpty() == true) return DRM
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/")) hasAudio = true
            }
        } catch (_: IOException) {
            return UNREADABLE // "Failed to instantiate extractor" — unsupported container or damaged file
        } catch (_: IllegalArgumentException) {
            return UNREADABLE // malformed/unresolvable Uri
        } catch (_: SecurityException) {
            return UNREADABLE // the read permission grant expired since the pick
        } finally {
            runCatching { extractor.release() }
        }

        if (!hasVideo) return NO_VIDEO
        if (needsAudio && !hasAudio) return NO_AUDIO

        val sourceBytes = sourceSize(context, uri)
        val required = (tempCopies + 1) * sourceBytes + SLACK_BYTES // +1 = the published copy
        return if (context.cacheDir.usableSpace >= required) null else LOW_SPACE
    }

    /** Map a mid-pipeline failure to its cause. Order matters: the specific cases shadow [GENERIC]. */
    fun messageFor(t: Throwable): String {
        val text = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "enospc" in text || "no space left" in text -> OUT_OF_SPACE
            "crypto" in text || "drm" in text -> DRM
            "codec" in text || "decoder" in text || "encoder" in text -> UNSUPPORTED_CODEC
            t is FileNotFoundException -> UNREADABLE
            t is IOException -> UNREADABLE
            else -> t.message ?: GENERIC
        }
    }

    /** Source size in bytes; 0 when the provider won't say (then only the 2 GB slack is required). */
    private fun sourceSize(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
}
