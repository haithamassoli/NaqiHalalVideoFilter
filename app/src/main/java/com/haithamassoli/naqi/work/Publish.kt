package com.haithamassoli.naqi.work

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Moving a finished temp into the user's media library — the last step of every job shape.
 *
 * Extracted out of [FilterWorker] because [com.haithamassoli.naqi.download.DownloadWorker] needs the
 * same code path for the "all filters off" case: a download with nothing to filter still has to land
 * in `Movies/Naqi`, and a second copy of the pending-row dance is exactly the kind of duplication that
 * drifts apart.
 *
 * The blocking copy has no suspension point, so a cancel that arrives mid-copy only surfaces after it:
 * [isStopped] is re-checked before the output is finalized, and any failure or cancel deletes the
 * half-written row/file. **A cancelled or failed job must never leave output in the gallery** — that is
 * the whole quarantine promise, and it is enforced here rather than by the callers.
 */
internal object Publish {

    const val MIME_MP4 = "video/mp4"

    /** m4a is an MPEG-4 audio container; `audio/mp4` is the mime MediaStore and every player expect. */
    const val MIME_M4A = "audio/mp4"

    /** Filtered video → `Movies/Naqi`. */
    fun video(context: Context, tempFile: File, displayName: String, isStopped: () -> Boolean): Uri =
        into(
            context, tempFile, displayName, MIME_MP4, Environment.DIRECTORY_MOVIES,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), isStopped,
        )

    /**
     * Audio-only job → `Music/Naqi`. Same dance, different collection: an `.m4a` published into the
     * Video collection is invisible to every music player, which is the only app that would want it.
     */
    fun audio(context: Context, tempFile: File, displayName: String, isStopped: () -> Boolean): Uri =
        into(
            context, tempFile, displayName, MIME_M4A, Environment.DIRECTORY_MUSIC,
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), isStopped,
        )

    private fun into(
        context: Context,
        tempFile: File,
        displayName: String,
        mime: String,
        publicDir: String,
        collection: Uri,
        isStopped: () -> Boolean,
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            // MediaColumns, not Video.Media/Audio.Media: these four columns are declared on the shared
            // superinterface, so one set of puts serves both collections.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/Naqi")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val item = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                (resolver.openOutputStream(item) ?: error("MediaStore openOutputStream failed"))
                    .use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                if (isStopped()) throw CancellationException("cancelled during publish")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(item, values, null, null)
                return item
            } catch (t: Throwable) {
                runCatching { resolver.delete(item, null, null) } // drop the un-finalized (still-pending) row
                throw t
            }
        }
        // API 26-28: write into the public dir (needs WRITE_EXTERNAL_STORAGE) and hand it to the scanner.
        val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), "Naqi").apply { mkdirs() }
        val dest = File(dir, displayName)
        try {
            tempFile.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (isStopped()) throw CancellationException("cancelled during publish")
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return Uri.fromFile(dest)
        } catch (t: Throwable) {
            dest.delete() // remove the partial/complete copy before the scanner ever sees it
            throw t
        }
    }
}
