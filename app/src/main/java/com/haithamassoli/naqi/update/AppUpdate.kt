package com.haithamassoli.naqi.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Naqi ships as a GitHub Releases APK, not through Play, so nothing else would ever tell an
 * installed copy that a newer one exists. This is the daily check plus the download and hand-off to
 * Android's installer.
 *
 * Two decisions are load-bearing and both come from what the API actually returns:
 *
 * 1. **`/releases/latest` is not used.** It skips pre-releases, and every 1.2 build is tagged as
 *    one — so it answers "v1.1" and would offer everybody on 1.2-beta2 a downgrade. The list
 *    endpoint plus a real version compare is the only thing that gets this right.
 * 2. **Channel follows the installed build.** A pre-release build is offered pre-releases and
 *    stables; a stable build is offered stables only. Nobody on 1.1 gets pushed onto a beta, and it
 *    costs one predicate rather than a settings screen.
 *
 * Android has no silent self-install for a sideloaded app: [install] opens the system installer and
 * the user confirms. The APK must also be signed with the same key as the build it replaces, or the
 * installer refuses with a signature mismatch this code cannot catch, let alone explain.
 *
 * `HttpURLConnection` + `org.json`, matching [com.haithamassoli.naqi.ml.ModelDownloader] — one GET
 * does not justify an HTTP client dependency.
 */
object AppUpdate {
    private const val TAG = "AppUpdate"

    /** Ten is well past the horizon anyone updates across, and one page is one request. */
    private const val RELEASES_URL =
        "https://api.github.com/repos/haithamassoli/NaqiHalalVideoFilter/releases?per_page=10"

    private const val FILE_NAME = "naqi-update.apk"

    /** "1.2-beta2" split into the parts that decide order: `[1, 2]` and `"beta2"`. */
    data class Version(val nums: List<Int>, val pre: String?) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            repeat(maxOf(nums.size, other.nums.size)) { i ->
                val c = nums.getOrElse(i) { 0 }.compareTo(other.nums.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            // Same numbers: a release outranks any pre-release of it. 1.2 > 1.2-beta2 > 1.2-beta1.
            if (pre == null && other.pre == null) return 0
            if (pre == null) return 1
            if (other.pre == null) return -1
            // ponytail: letters compared alphabetically, which orders alpha < beta < rc for free.
            // A scheme that needs true semver precedence gets a real parser.
            val letters = pre.takeWhile { !it.isDigit() }.compareTo(other.pre.takeWhile { !it.isDigit() })
            if (letters != 0) return letters
            return preNumber(pre).compareTo(preNumber(other.pre))
        }

        /** Round-trips [parseVersion], so it doubles as the display name and the "skipped" key. */
        override fun toString(): String = nums.joinToString(".") + pre?.let { "-$it" }.orEmpty()

        private fun preNumber(s: String) = s.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
    }

    /** A published release newer than this build, with the one asset worth downloading. */
    data class Release(val version: Version, val apkUrl: String, val sizeBytes: Long)

    /** A snapshot of the running download. [total] is -1 until the server reports a length. */
    data class Progress(val status: Int, val done: Long, val total: Long)

    /** The running build's version, parsed. Never null: the tag is ours and it always parses. */
    fun installed(): Version = parseVersion(BuildConfig.VERSION_NAME) ?: Version(listOf(0), null)

    /** "v1.2-beta2" → `[1,2]` + "beta2". Null when the tag does not start with a number. */
    fun parseVersion(raw: String): Version? {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        if (s.firstOrNull()?.isDigit() != true) return null
        val parts = s.split("-", limit = 2)
        val nums = parts[0].split(".").map { it.toIntOrNull() ?: 0 }
        return Version(nums, parts.getOrNull(1)?.takeIf { it.isNotEmpty() })
    }

    /**
     * The best release in a `/releases` response that is strictly newer than [installed], or null.
     *
     * Pure and Context-free so the channel rule and the version compare are testable off-device —
     * they are the whole feature, and getting them wrong offers users a downgrade.
     */
    fun pickRelease(json: String, installed: Version): Release? {
        val arr = JSONArray(json)
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft")) continue
            if (o.optBoolean("prerelease") && installed.pre == null) continue
            val version = parseVersion(o.optString("tag_name")) ?: continue
            if (version <= installed) continue
            val current = best
            if (current != null && version <= current.version) continue
            val asset = apkAsset(o.optJSONArray("assets")) ?: continue
            best = Release(version, asset.first, asset.second)
        }
        return best
    }

    /**
     * Ask GitHub what is newer. Every failure — offline, rate limit, malformed body — is a null and a
     * log line: an update check that quietly does nothing when the network is gone is the correct
     * behaviour, and there is no message worth interrupting someone with.
     */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val body = get(RELEASES_URL) ?: return@runCatching null
            val found = pickRelease(body, installed())
                ?.takeIf { it.version.toString() != Prefs.appUpdateSkipped(context) }
            // The clock is only marked when there is nothing to offer. An update the user has not
            // acted on yet has to survive reopening the app, and the check that finds it again is
            // self-limiting: taking it or dismissing it is what makes the next check come up empty.
            if (found == null) Prefs.markAppUpdateChecked(context)
            found
        }.onFailure { Log.w(TAG, "update check failed", it) }.getOrNull()
    }

    /**
     * Hand the APK to [DownloadManager]: it survives the app being backgrounded or killed, resumes
     * by itself, and posts its own progress notification — which is what a ~220 MB transfer needs.
     * Lands in the app's external files dir, so it needs no permission and goes away with the app.
     */
    fun enqueue(context: Context, release: Release): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        cancel(context)
        val request = DownloadManager.Request(release.apkUrl.toUri())
            .setTitle(context.getString(R.string.update_downloading, release.version.toString()))
            .setDestinationInExternalFilesDir(context, null, FILE_NAME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // The user tapped Update with the size on screen — that is the informed decision, and a
            // second "not on mobile data" rule here would only strand them.
            .setAllowedOverMetered(true)
        val id = dm.enqueue(request)
        Prefs.setAppUpdateDownload(context, id, release.version.toString())
        return id
    }

    /** Drop the download and the file with it, so a retry starts clean rather than resuming junk. */
    fun cancel(context: Context) {
        val id = Prefs.appUpdateDownloadId(context)
        if (id != Prefs.NO_DOWNLOAD) {
            runCatching { context.getSystemService(DownloadManager::class.java).remove(id) }
        }
        Prefs.setAppUpdateDownload(context, Prefs.NO_DOWNLOAD, null)
    }

    /**
     * Bytes as they land, until the transfer ends.
     *
     * ponytail: polled, not a completion `BroadcastReceiver` — that only fires at the end and the
     * progress bar needs bytes the whole way through. Half a second is under the eye's threshold for
     * a bar that takes minutes to fill.
     */
    fun progress(context: Context, id: Long): Flow<Progress> = flow {
        val dm = context.getSystemService(DownloadManager::class.java)
        while (true) {
            val p = query(dm, id)
            emit(p)
            if (p.status == DownloadManager.STATUS_SUCCESSFUL || p.status == DownloadManager.STATUS_FAILED) return@flow
            delay(500)
        }
    }

    /**
     * Open the system installer on the finished APK. First run on a device lands on the "allow
     * installs from this source" screen instead — without that grant the installer is a dead end,
     * and sending the user somewhere they can fix it beats a button that does nothing.
     */
    fun install(context: Context, id: Long) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
            )
            return
        }
        // DownloadManager's own provider grants the read on this uri, which is why Naqi needs no
        // FileProvider of its own.
        val uri = context.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return
        context.launch(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun Context.launch(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "no activity for ${intent.action}", it) }
    }

    /** A missing row reads as failed: the user cleared it, so there is nothing left to install. */
    private fun query(dm: DownloadManager, id: Long): Progress {
        val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return failed()
        return cursor.use {
            if (!it.moveToFirst()) return@use failed()
            Progress(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    private fun failed() = Progress(DownloadManager.STATUS_FAILED, 0L, -1L)

    /** Releases have carried both `naqi-*.apk` and a bare `app-debug.apk`; prefer the named one. */
    private fun apkAsset(assets: JSONArray?): Pair<String, Long>? {
        if (assets == null) return null
        val apks = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .filter { it.optString("browser_download_url").isNotEmpty() }
        val pick = apks.firstOrNull { it.optString("name").contains("naqi", ignoreCase = true) }
            ?: apks.firstOrNull()
            ?: return null
        return pick.optString("browser_download_url") to pick.optLong("size")
    }

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "releases HTTP ${conn.responseCode}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }
}
