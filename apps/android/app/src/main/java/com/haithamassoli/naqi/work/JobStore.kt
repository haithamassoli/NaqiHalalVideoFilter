package com.haithamassoli.naqi.work

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Where one job's working files live on disk (`long-film-plan.md` Phase 1, wall #4).
 *
 * Temps used to sit loose in `cacheDir`, which the system is explicitly allowed to reclaim under
 * storage pressure — during a multi-hour job that is itself filling the disk. They now live in
 * `noBackupFilesDir/naqi-work/<key>/`, one directory per job, which the system never reclaims.
 *
 * `noBackupFilesDir` and not `filesDir`: several GB of job-local scratch, keyed to a source Uri that need
 * not exist on a restoring device, has no business in a cloud backup or a device transfer. That directory
 * is excluded by the platform, which beats an `<exclude>` in two backup XMLs that a later edit can forget.
 *
 * [keyOf] is content-derived rather than taken from the WorkManager request id, because Phase 2's
 * resume has to find the same directory again after process death — and a user-triggered Resume
 * enqueues a brand new request with a brand new id.
 *
 * **The sweep is age-based on purpose.** "Delete every temp at startup" is the obvious reading of the
 * Phase 1 item and it is the one change here that could silently destroy hours of work: Phase 2 needs a
 * killed job's rendered segments to still be there when the user taps Resume. So the sweep only ever
 * descends into `naqi-work`, and only into entries untouched for [STALE_MS] — never a live or resumable
 * job, and never anything outside its own directory.
 * A finished or cancelled job deletes its own directory immediately ([delete]); the sweep exists purely
 * for the orphans process death leaves behind and the user never resumes.
 */
internal object JobStore {

    private const val TAG = "JobStore"
    private const val ROOT = "naqi-work"

    /** Long enough that a film someone means to resume tomorrow survives; short enough to not hoard GBs. */
    private const val STALE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Stable key for one (source, options) pair. Everything that changes the OUTPUT goes in, so
     * changing strictness or the kept stems starts a fresh job instead of resuming into work rendered
     * under different settings. A real digest rather than [String.hashCode]: a collision here would
     * resume the wrong job's segments into a user's video.
     */
    fun keyOf(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length-delimited so ("a","bc") and ("ab","c") cannot hash alike.
        for (p in parts) digest.update("${p.toString().length}:$p|".toByteArray())
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    /** This job's working directory, created if needed. */
    fun dir(context: Context, key: String): File =
        File(File(context.noBackupFilesDir, ROOT), key).apply { mkdirs() }

    /** Drop everything this job wrote. Called on success and on cancellation — never on a resumable failure. */
    fun delete(context: Context, key: String) {
        dir(context, key).deleteRecursively()
    }

    /**
     * Delete abandoned job directories. Safe to call on every app start and at the head of every job:
     * it looks only inside `naqi-work`, and only at entries whose newest file is older than [STALE_MS].
     */
    fun sweep(context: Context) {
        val root = File(context.noBackupFilesDir, ROOT)
        val cutoff = System.currentTimeMillis() - STALE_MS
        val dirs = root.listFiles() ?: return
        for (d in dirs) {
            // A directory's own mtime does not track writes to files inside it on every filesystem,
            // so age the job by its newest FILE — a job actively writing segments is never stale.
            val newest = (d.listFiles()?.maxOfOrNull { it.lastModified() } ?: d.lastModified())
            if (newest >= cutoff) continue
            val freed = d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (d.deleteRecursively()) Log.i(TAG, "swept stale job ${d.name}, freed $freed bytes")
        }
    }
}
