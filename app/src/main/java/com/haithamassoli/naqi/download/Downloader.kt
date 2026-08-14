package com.haithamassoli.naqi.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.work.JobStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything the app knows about yt-dlp, in one file.
 *
 * The library is a process launcher around a bundled CPython + a yt-dlp zipapp: every call here forks a
 * native process and blocks the calling thread until it exits, so all of them are `withContext(IO)` and
 * none may be touched from the main thread.
 *
 * **Quarantine, not the gallery.** Downloads land in `noBackupFilesDir/naqi-downloads/<urlKey>/` and
 * are published only after filtering — the PRD's core promise is that no unfiltered file is ever visible
 * to the gallery or any other app. `noBackupFilesDir` for the same reason [JobStore] uses it: multi-GB
 * job-local scratch has no business in a cloud backup.
 */
object Downloader {

    private const val TAG = "Downloader"
    private const val ROOT = "naqi-downloads"

    /**
     * The quality choices the sheet offers, and the yt-dlp format selectors behind them. Fixed
     * strings on purpose — the PRD forbids ever rendering yt-dlp's raw format table at the user.
     */
    enum class Quality(val selector: String) {
        BEST("bv*+ba/b"),
        P1080("bv*[height<=1080]+ba/b[height<=1080]"),
        P720("bv*[height<=720]+ba/b[height<=720]"),
        P480("bv*[height<=480]+ba/b[height<=480]"),

        /** Paired with `--extract-audio --audio-format m4a` in [download]; the result is an `.m4a`. */
        AUDIO("ba/b"),
        ;

        companion object {
            fun of(name: String?): Quality = entries.firstOrNull { it.name == name } ?: BEST
        }
    }

    @Volatile
    private var ready = false

    // ponytail: one lock because yt-dlp updates replace files used by every active process. Split only
    // if the library gains an atomic updater that is safe while a download is running.
    private val processMutex = Mutex()

    /**
     * Unzip CPython, the yt-dlp zipapp and ffmpeg out of the extracted native libs. First call costs a
     * few seconds; later ones are a no-op inside the library too, but the flag saves the lock.
     *
     * Both inits are required and ordered: `FFmpeg.init` writes into the directory `YoutubeDL.init`
     * creates, and without it `--extract-audio` and any `bv*+ba` merge fail at the very end of a
     * download that otherwise looked fine.
     */
    @Synchronized
    private fun ensureInit(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
        Log.i(TAG, "yt-dlp ready, version=${version(app)}")
    }

    /** Installed yt-dlp version, or null until [update] has run once. Shown on the About screen. */
    fun version(context: Context): String? =
        runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()

    /**
     * Replace the bundled yt-dlp with the current nightly release.
     *
     * **Not optional, and not a nicety.** The zipapp inside the AAR is frozen at whatever the library
     * was released with (0.18.1 ships 2025.11.12), and YouTube rotates the JS challenge that gates its
     * format URLs on the order of weeks — a stock install fails every YouTube link with "Requested
     * format is not available" until this has run once. Verified on an S23, 2026-07-29.
     *
     * Nightly matches Seal's default and gets extractor fixes before the next stable release.
     */
    suspend fun update(context: Context): YoutubeDL.UpdateStatus? = withContext(Dispatchers.IO) {
        processMutex.withLock { updateLocked(context) }
    }

    private fun updateLocked(context: Context): YoutubeDL.UpdateStatus? {
        ensureInit(context)
        val status = YoutubeDL.getInstance()
            .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        Prefs.markUpdateChecked(context)
        Log.i(TAG, "yt-dlp update: $status, version=${version(context)}")
        return status
    }

    /**
     * The weekly auto-check (PRD: "weekly auto-check on app open + manual button").
     *
     * Fire-and-forget and deliberately failure-tolerant: this runs on a cold start, the user has not
     * asked for anything yet, and a failed check must cost them nothing. The clock is only advanced on
     * success, so a week offline does not silently consume the interval.
     */
    suspend fun updateIfDue(context: Context) = withContext(Dispatchers.IO) {
        processMutex.withLock { updateIfDueLocked(context) }
    }

    private fun updateIfDueLocked(context: Context) {
        if (!Prefs.updateDue(context)) return
        runCatching { updateLocked(context) }
            .onFailure { Log.w(TAG, "weekly yt-dlp check failed; will retry next launch", it) }
    }

    /**
     * One quarantine directory per URL, so a retry of the same link finds its own `.part` file and yt-dlp
     * resumes it rather than restarting the transfer.
     *
     * ponytail: per-URL directory instead of the PRD's "id suffix on collision". A fresh directory makes
     * a collision impossible by construction, which is less code than detecting one, and it makes the
     * orphan sweep a directory delete. Revisit if two different URLs ever need to share a directory.
     */
    fun quarantineDir(context: Context, url: String): File =
        File(File(context.noBackupFilesDir, ROOT), JobStore.keyOf(url)).apply { mkdirs() }

    /**
     * Fetch [url] into its quarantine directory and return the finished file.
     *
     * The output template names the file after the **video title**, truncated to 80 bytes
     * (`%(title).80B` — bytes, not chars, so a fully Arabic title cannot overrun a filesystem's byte
     * limit). That is the whole reason the existing naming seam needs no change: [FilterWorker]'s
     * `outputName` takes the source filename and appends `-naqi-<ts>`, so the published file is already
     * `<video title>-naqi-<ts>.mp4`.
     *
     * @param onProgress percent 0..100 and yt-dlp's own ETA in seconds (-1 when it hasn't said yet).
     *   Called from the reader thread of the yt-dlp process, not from a coroutine.
     * @param onSpaceCheck called on each progress tick; returning false aborts the download. This is the
     *   PRD's "abort early once yt-dlp reports total bytes" for sources whose size was unknown up front.
     */
    suspend fun download(
        context: Context,
        url: String,
        quality: Quality,
        processId: String,
        onSpaceCheck: () -> Boolean = { true },
        onProgress: (Int, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        processMutex.withLock {
            // A user can live entirely in the translucent share flow and never open MainActivity, so
            // this is the one route that can guarantee the nightly check precedes the actual process.
            updateIfDueLocked(context)
            ensureInit(context)
            val dir = quarantineDir(context, url)
            val request = YoutubeDLRequest(url)
                .addOption("-f", quality.selector)
                .addOption("-o", File(dir, "%(title).80B.%(ext)s").absolutePath)
                // A shared link often carries a playlist id; without this a single share downloads 200 videos.
                .addOption("--no-playlist")
                // Keep the download timestamp rather than the upload date, so the gallery sorts it as new.
                .addOption("--no-mtime")
            if (quality == Quality.AUDIO) {
                request.addOption("--extract-audio").addOption("--audio-format", "m4a")
            }

            YoutubeDL.getInstance().execute(request, processId) { progress, etaSeconds, _ ->
                if (!onSpaceCheck()) {
                    // The only way to stop a running yt-dlp: kill the process by the id we passed in.
                    Log.w(TAG, "aborting download: out of space")
                    YoutubeDL.getInstance().destroyProcessById(processId)
                }
                onProgress(progress.toInt().coerceIn(0, 100), etaSeconds)
            }

            // The per-URL directory has one completed file; partials use an in-flight extension.
            dir.listFiles()
                ?.filter { it.isFile && it.extension !in IN_FLIGHT }
                ?.maxByOrNull { it.length() }
                ?: error("download reported success but produced no file")
        }
    }

    /** Kill a running download by the id its [download] call was given. */
    fun cancel(processId: String) {
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
    }

    /**
     * Is this the app's own quarantined download rather than a file the user picked or shared?
     *
     * Two behaviours hang off this and both matter: a quarantined original is deleted once its filtered
     * output is published (the PRD's "zero unfiltered files visible" promise), and it is never offered
     * as a "Delete original" notification action — the user never had that file to begin with.
     */
    fun isQuarantined(context: Context, uri: Uri): Boolean =
        uri.scheme == "file" &&
            uri.path?.startsWith(File(context.noBackupFilesDir, ROOT).absolutePath + File.separator) == true

    /** Drop a quarantined original and the per-URL directory around it. No-op for anything else. */
    fun discard(context: Context, uri: Uri) {
        if (!isQuarantined(context, uri)) return
        runCatching { File(uri.path!!).parentFile?.deleteRecursively() }
            .onSuccess { Log.i(TAG, "quarantine cleared for ${uri.lastPathSegment}") }
    }

    /**
     * Delete abandoned quarantine directories, on the same 7-day rule [JobStore.sweep] uses for job
     * scratch: a failed download keeps its `.part` so a retry resumes it, and only a download the user
     * never retries should be reclaimed.
     */
    fun sweep(context: Context) {
        val root = File(context.noBackupFilesDir, ROOT)
        val cutoff = System.currentTimeMillis() - STALE_MS
        for (d in root.listFiles().orEmpty()) {
            val newest = d.listFiles()?.maxOfOrNull { it.lastModified() } ?: d.lastModified()
            if (newest >= cutoff) continue
            val freed = d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (d.deleteRecursively()) Log.i(TAG, "swept stale download ${d.name}, freed $freed bytes")
        }
    }

    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000
    private val IN_FLIGHT = setOf("part", "ytdl", "temp")
}
