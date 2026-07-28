package com.haithamassoli.naqi.work

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import com.haithamassoli.naqi.R
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
 *
 * Every cause is a **@StringRes id, not a String**. It used to be a String, while `JobsScreen` already
 * read the key with `getInt` — so `Data.getInt` fell through to its default on every single failure and
 * the UI showed nothing but "Filtering failed", and the Arabic translations of these eight sentences
 * (which have existed since the localization pass) were unreachable. Resource ids also re-localize if
 * the app language changes after a job failed, which is why the id and not the resolved string travels
 * through `WorkData`.
 */
internal object Preflight {

    val DRM = R.string.err_drm
    val UNREADABLE = R.string.err_unreadable
    val NO_VIDEO = R.string.err_no_video
    val NO_AUDIO = R.string.err_no_audio
    val LOW_SPACE = R.string.err_low_space
    val UNSUPPORTED_CODEC = R.string.err_unsupported_codec
    val OUT_OF_SPACE = R.string.err_out_of_space
    val GENERIC = R.string.err_generic

    /** Headroom the PRD requires on top of the working copies. */
    private const val SLACK_BYTES = 2L * 1024 * 1024 * 1024

    /**
     * @param needsAudio music removal was requested — a missing audio track is then fatal, not fine.
     * @param tempCopies working copies the job writes before publishing: censor-only and music-only
     *   each write one temp, combined writes a render temp AND a mux temp. The published output
     *   lands on the same filesystem as the cache on every modern device, so it is counted too —
     *   the PRD's "2× source + 2 GB" is exactly the one-temp case.
     * @param extraScratchBytes scratch this shape needs on top of the working copies — Phase 2's
     *   separated-audio PCM file, which is 176 400 B per second of source (int16 stereo 44.1 kHz) and so
     *   reaches ~1.6 GB on a 155-min film. It scales with duration, not with source size, so it cannot be
     *   folded into [tempCopies].
     * @return the @StringRes id of a user-facing message, or null when the job may proceed.
     */
    @StringRes
    fun check(
        context: Context,
        uri: Uri,
        needsAudio: Boolean,
        tempCopies: Int,
        extraScratchBytes: Long = 0L,
    ): Int? {
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
        val required = (tempCopies + 1) * sourceBytes + extraScratchBytes + SLACK_BYTES // +1 = published copy
        // filesDir, not cacheDir: Phase 1 of long-film-plan.md moved the working temps there ([JobStore]).
        // Same partition on every modern device, so this is about measuring the volume we actually fill.
        return if (context.filesDir.usableSpace >= required) null else LOW_SPACE
    }

    /**
     * Map a mid-pipeline failure to its cause. Order matters: the specific cases shadow [GENERIC].
     *
     * An unrecognized cause resolves to [GENERIC] rather than the throwable's own message. That message
     * used to reach the screen verbatim — untranslated developer text like "separator emitted 3 of 4
     * frames", which tells the user nothing they can act on. It is still logged with the full stack by
     * every caller, which is where it belongs.
     */
    @StringRes
    fun messageFor(t: Throwable): Int {
        val text = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "enospc" in text || "no space left" in text -> OUT_OF_SPACE
            "crypto" in text || "drm" in text -> DRM
            "codec" in text || "decoder" in text || "encoder" in text -> UNSUPPORTED_CODEC
            t is FileNotFoundException -> UNREADABLE
            t is IOException -> UNREADABLE
            else -> GENERIC
        }
    }

    /** Source size in bytes; 0 when the provider won't say (then only the 2 GB slack is required). */
    private fun sourceSize(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)
}
