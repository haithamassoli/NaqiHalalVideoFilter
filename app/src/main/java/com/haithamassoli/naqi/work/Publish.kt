package com.haithamassoli.naqi.work

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileDescriptor

/**
 * Moving a finished temp into the user's media library — the last step of every job shape.
 *
 * Extracted out of [FilterWorker] because [com.haithamassoli.naqi.download.DownloadWorker] needs the
 * same code path for the "all filters off" case: a download with nothing to filter still has to land
 * in `Movies/Naqi`, and a second copy of the pending-row dance is exactly the kind of duplication that
 * drifts apart.
 *
 * The copy has no suspension point, so cancellation is polled by hand, once per buffer: [isStopped] is
 * checked while copying AND again before the output is finalized, and any failure or cancel deletes the
 * half-written pending row. **A cancelled or failed job must never leave output in the gallery** — that is
 * the whole quarantine promise, and it is enforced here rather than by the callers.
 */
internal object Publish {

    /**
     * `copyTo`'s 8 KiB default means ~200 000 read/write pairs for a 1.7 GB film (`plan-v2` §5.9 S5,
     * "the MediaStore copy is separately worth a larger buffer plus cancellation"). 1 MiB is one page-cache
     * batch per iteration and gives cancellation a granularity a user can feel — at 8 KiB the poll would
     * be free but pointless, and at 16 MiB the copy would be uncancellable for seconds at a time.
     */
    private const val COPY_BUFFER = 1 shl 20

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
     * Filtered video → `Movies/Naqi`, written **in place** by [write] instead of copied from a temp
     * (`plan-v2` §5.9 S5). Every muxing shape used to write `mux.mp4` and then copy it here: ~1.7 GB
     * written and read again per film, for bytes the muxer could have put in the right place first time.
     *
     * The descriptor is opened `"rw"`, not `"w"` — `MediaMuxer` seeks back over the file to write `moov`,
     * and a write-only descriptor fails at `stop()`, after the whole remux.
     *
     * Same contract as [into] and it is the reason this lives here rather than in `Remux`: the row is
     * created pending, [write] fills it, and only a clean return finalizes it. Any throw — including the
     * cancellation [write] is expected to raise — deletes the still-pending row, so a cancelled job can
     * never leave a truncated video in the gallery. That is strictly stronger than the temp-file path it
     * replaces, where a crash between mux and publish left an orphan on disk.
     */
    suspend fun muxedVideo(
        context: Context,
        displayName: String,
        write: suspend (FileDescriptor) -> Unit,
    ): Uri {
        val resolver = context.contentResolver
        val values = pendingValues(displayName, MIME_MP4, Environment.DIRECTORY_MOVIES)
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            (resolver.openFileDescriptor(item, "rw") ?: error("MediaStore openFileDescriptor failed"))
                .use { pfd -> write(pfd.fileDescriptor) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(item, values, null, null)
            return item
        } catch (t: Throwable) {
            runCatching { resolver.delete(item, null, null) } // drop the un-finalized (still-pending) row
            throw t
        }
    }

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
        val resolver = context.contentResolver
        val values = pendingValues(displayName, mime, publicDir)
        val item = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            (resolver.openOutputStream(item) ?: error("MediaStore openOutputStream failed")).use { out ->
                tempFile.inputStream().use { ins ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        // Polled per buffer, not once at the end: a film is ~1 700 buffers here and the
                        // copy used to run to completion after a cancel before anyone noticed.
                        if (isStopped()) throw CancellationException("cancelled during publish")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
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

    // MediaColumns, not Video.Media/Audio.Media: these four columns are declared on the shared
    // superinterface, so one set of puts serves both collections.
    private fun pendingValues(displayName: String, mime: String, publicDir: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDir/Naqi")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
}
