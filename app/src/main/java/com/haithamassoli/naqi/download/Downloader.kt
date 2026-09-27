package com.haithamassoli.naqi.download

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.work.JobStore
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

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
     * The quality choices the sheet offers, as a resolution cap; [formatArgs] turns it into yt-dlp's
     * `-f`/`-S`. The PRD forbids ever rendering yt-dlp's raw format table at the user. The names are a
     * wire format (queue JSON, Prefs).
     */
    enum class Quality(val maxHeight: Int?) {
        BEST(null),
        P1080(1080),
        P720(720),
        P480(480),

        /** Paired with `--extract-audio --audio-format m4a` in [download]; the result is an `.m4a`. */
        AUDIO(null),
        ;

        companion object {
            fun of(name: String?): Quality = entries.firstOrNull { it.name == name } ?: BEST
        }
    }

    @Volatile
    private var ready = false

    /**
     * An update replaces the zipapp every yt-dlp process is running from, so it must never overlap one;
     * two processes (a download and the share sheet's [probe]) may overlap each other freely. That is a
     * read/write lock: [sharing] is the read side, [exclusive] the write side. Holding [gate] while
     * waiting for [running] to drain is what stops new readers from starving an update.
     *
     * Not a JDK ReentrantReadWriteLock: that one is owned by a thread, and these holders suspend.
     */
    private val gate = Mutex()
    private val running = AtomicInteger()

    private suspend fun <T> sharing(block: suspend () -> T): T {
        gate.withLock { running.incrementAndGet() }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }

    private suspend fun <T> exclusive(block: () -> T): T = gate.withLock {
        while (running.get() > 0) delay(250)
        block()
    }

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
        // Unzips aria2c's shared libs next to ffmpeg's; without it aria2c cannot start.
        Aria2c.getInstance().init(app)
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
        exclusive { updateLocked(context) }
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
     * success, so a week offline does not silently consume the interval. Checked before taking the gate,
     * so the six days out of seven it isn't due never make a share-sheet probe wait on a download.
     */
    suspend fun updateIfDue(context: Context) = withContext(Dispatchers.IO) {
        if (!Prefs.updateDue(context)) return@withContext
        exclusive {
            if (Prefs.updateDue(context)) {
                runCatching { updateLocked(context) }
                    .onFailure { Log.w(TAG, "weekly yt-dlp check failed; will retry next launch", it) }
            }
        }
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

    /** What the share sheet shows about a link before it is queued. -1 = the extractor didn't say. */
    data class Info(val title: String?, val durationSec: Long, val sizeBytes: Long)

    /**
     * Title, duration and size of [url] for this [quality], and the extraction the download will reuse.
     *
     * The first call per URL goes to the network (~5 s on YouTube, the whole of a download's startup)
     * and saves yt-dlp's info JSON to the quarantine directory; [download] then starts from it instead
     * of extracting again. Every later call — the user changing quality or filters — re-runs only the
     * local format selection against that JSON (0.3 s, no network), so the size always matches the
     * choice on screen. Null on any failure: the sheet never blocks on this.
     */
    suspend fun probe(context: Context, url: String, quality: Quality, filtered: Boolean): Info? =
        withContext(Dispatchers.IO) {
            runCatching {
                sharing {
                    ensureInit(context)
                    val saved = freshInfoJson(context, url)
                    val request = YoutubeDLRequest(url).apply {
                        formatArgs(quality, filtered, DeviceCodecs.hwDecodeHeights).chunked(2).forEach { (k, v) -> addOption(k, v) }
                        addOption("--dump-single-json")
                        addOption("--no-playlist")
                        if (saved != null) addOption("--load-info-json", saved.absolutePath) else addOption("--socket-timeout", 10)
                    }
                    val json = runInterruptible { YoutubeDL.getInstance().execute(request).out }
                    // Only a network extraction is worth keeping; a local re-selection is derived from it.
                    if (saved == null) {
                        val tmp = File(quarantineDir(context, url), "$INFO_JSON.tmp")
                        tmp.writeText(json)
                        tmp.renameTo(File(tmp.parentFile, INFO_JSON))
                    }
                    parseInfo(json)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "probe failed for $url", it)
            }.getOrNull()
        }

    /** The saved extraction, if young enough that its stream URLs are still valid (YouTube's last ~6 h). */
    private fun freshInfoJson(context: Context, url: String): File? =
        File(quarantineDir(context, url), INFO_JSON)
            .takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < INFO_MAX_AGE_MS }

    internal fun parseInfo(json: String): Info {
        val o = JSONObject(json)
        fun long(key: String) = o.optDouble(key, -1.0).takeIf { !it.isNaN() }?.toLong() ?: -1L
        // A merged video+audio selection reports only the estimate; a single stream has the exact size.
        val size = long("filesize").takeIf { it > 0 } ?: long("filesize_approx")
        return Info(o.optString("title").takeIf { it.isNotBlank() }, long("duration"), size)
    }

    /**
     * Fetch [url] into its quarantine directory and return the finished file.
     *
     * The output template names the file after the **video title**, truncated to 80 bytes
     * (`%(title).80B` — bytes, not chars, so a fully Arabic title cannot overrun a filesystem's byte
     * limit). That is the whole reason the existing naming seam needs no change: [FilterWorker]'s
     * `outputName` takes the source filename and appends `-naqi-<ts>`, so the published file is already
     * `<video title>-naqi-<ts>.mp4`.
     *
     * One attempt, then at most one retry whose shape depends on what failed ([recoveryFor]).
     *
     * @param filtered a filter will run on the result: caps "Best" at 1080p and prefers ≤30 fps, because
     *   filter time scales with pixels × frames and a 4K60 source costs ~8× a 1080p30 one.
     * @param onProgress bytes, speed and ETA for the whole download, video and audio streams summed.
     *   Called from the reader thread of the yt-dlp process, not from a coroutine.
     * @param onSpaceCheck called on each progress tick; returning false aborts the download. This is the
     *   PRD's "abort early once yt-dlp reports total bytes" for sources whose size was unknown up front.
     */
    suspend fun download(
        context: Context,
        url: String,
        quality: Quality,
        filtered: Boolean,
        processId: String,
        onSpaceCheck: () -> Boolean = { true },
        onProgress: (DlProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // A user can live entirely in the translucent share flow and never open MainActivity, so
        // this is the one route that can guarantee the weekly check precedes the actual process.
        updateIfDue(context)
        ensureInit(context)
        val dir = quarantineDir(context, url)
        val output = File(dir, "%(title).80B.%(ext)s").absolutePath
        val hw = DeviceCodecs.hwDecodeHeights
        val started = SystemClock.elapsedRealtime()
        var firstProgressMs = -1L
        var outOfSpace = false

        fun runAttempt(attempt: Attempt) {
            // aria2c writes segments at scattered offsets, so its .part is not a prefix the native
            // downloader can append to. Resuming one natively would stitch a corrupt file.
            if (attempt.aria2 == null) dropAria2Partials(dir)
            val request = buildRequest(url, output, quality, filtered, hw, attempt)
            val streams = StreamTotals()
            // Called for every output line, not just progress; parseProgress picks the progress out.
            YoutubeDL.getInstance().execute(request, processId) { _, _, line ->
                val p = parseProgress(line) ?: run {
                    // What yt-dlp said that wasn't progress: the first thing to read when stats go missing.
                    if (BuildConfig.DEBUG_HOOKS) Log.d(TAG, "yt-dlp: $line")
                    return@execute
                }
                if (firstProgressMs < 0) firstProgressMs = SystemClock.elapsedRealtime() - started
                if (!outOfSpace && !onSpaceCheck()) {
                    // The only way to stop a running yt-dlp: kill the process by the id we passed in.
                    Log.w(TAG, "aborting download: out of space")
                    outOfSpace = true
                    YoutubeDL.getInstance().destroyProcessById(processId)
                }
                onProgress(streams.add(p))
            }
        }

        val first = Attempt(
            aria2 = if (useAria2(url)) aria2Paths(context) else null,
            infoJson = freshInfoJson(context, url)?.absolutePath,
            ipv4 = false,
        )
        // runInterruptible, not a bare blocking call: execute() waits on the process, and a cancelled
        // worker used to leave yt-dlp running to completion — every download queued behind it sat
        // waiting on an orphan. Interrupting the waiting thread makes the library destroy the process
        // and rethrow, which surfaces here as a CancellationException.
        try {
            sharing { runInterruptible { runAttempt(first) } }
        } catch (e: YoutubeDLException) {
            // A kill we asked for is not a yt-dlp failure, and retrying would only fill the disk again.
            if (outOfSpace) throw IOException("No space left on device (download aborted)", e)
            val error = classify(e.message.orEmpty())
            val retry = recoveryFor(error, first, updateAllowed = updateAllowed(context))
            Log.w(TAG, "yt-dlp failed ($error); ${retry ?: "not retrying"}", e)
            if (retry == null) throw e
            // A stale saved extraction is the likeliest cause of whatever just failed; never reuse it.
            File(dir, INFO_JSON).delete()
            if (retry.update) {
                exclusive {
                    runCatching { updateLocked(context) }
                        .onFailure { Log.w(TAG, "yt-dlp recovery update failed; retrying installed version", it) }
                }
            }
            sharing { runInterruptible { runAttempt(retry.attempt) } }
        }
        if (outOfSpace) throw IOException("No space left on device (download aborted)")

        // The per-URL directory has one completed file; partials use an in-flight extension.
        val file = dir.listFiles()
            ?.filter { it.isFile && it.extension !in IN_FLIGHT && !it.name.startsWith(INFO_JSON) }
            ?.maxByOrNull { it.length() }
            ?: error("download reported success but produced no file")
        logSummary(url, file, started, firstProgressMs, first)
        file
    }

    /** How one run of yt-dlp is shaped, beyond the user's choices. */
    internal data class Attempt(val aria2: Aria2?, val infoJson: String?, val ipv4: Boolean)

    /** The retry after a failed first [Attempt]: always the plain native downloader from a fresh extraction. */
    internal data class Retry(val update: Boolean, val attempt: Attempt)

    /**
     * What a download failure was, from yt-dlp's `ERROR:` lines. Each class gets its own message in
     * [DownloadWorker] and its own recovery in [recoveryFor]; the point is that no class gets another's.
     */
    internal enum class DlError { NO_SPACE, UNAVAILABLE, GEO, RATE_LIMITED, FORBIDDEN, EXTRACTOR, NETWORK, UNKNOWN }

    /**
     * Only the `ERROR:` lines are read when there are any: yt-dlp's warnings mention things like
     * "unable to download" on fragments it then retried successfully, and would misclassify the real
     * error. Order matters — the first match wins, most specific first.
     */
    internal fun classify(message: String): DlError {
        val errors = message.lines().filter { "ERROR:" in it }
        val t = (errors.ifEmpty { listOf(message) }).joinToString("\n").lowercase()
        fun has(vararg s: String) = s.any { it in t }
        return when {
            has("no space left", "errno 28", "enospc") -> DlError.NO_SPACE
            // "Sign in to confirm you're not a bot" is YouTube's anti-bot wall, which a newer yt-dlp is
            // the fix for — not a login the user could provide.
            has("not a bot") -> DlError.EXTRACTOR
            has(
                "private video", "video unavailable", "is unavailable", "has been removed", "no longer available",
                "members-only", "join this channel", "confirm your age", "age-restricted", "sign in",
                "logged-in", "log in", "login", "--cookies", "unsupported url", "is not a valid url",
                "does not exist", "http error 404", "http error 410",
            ) -> DlError.UNAVAILABLE
            has("in your country", "geo restrict", "geo-restrict", "in your location", "geo-blocked") -> DlError.GEO
            has("http error 429", "too many requests", "try again later", "rate-limit", "rate limit") -> DlError.RATE_LIMITED
            has("http error 403", "forbidden") -> DlError.FORBIDDEN
            has(
                "requested format is not available", "unable to extract", "nsig", "signature",
                "challenge", "no video formats", "unable to decrypt", "player response",
            ) -> DlError.EXTRACTOR
            has(
                "timed out", "connection reset", "connection refused", "connection aborted",
                "network is unreachable", "name resolution", "failed to resolve", "no address associated",
                "unable to download", "incompleteread", "http error 5", "remote end closed", "ssl",
                "errno 7", "errno 101", "errno 104", "errno 110", "errno 111",
            ) -> DlError.NETWORK
            else -> DlError.UNKNOWN
        }
    }

    /**
     * The one retry a failure earns, or null when retrying cannot help.
     *
     * - UNAVAILABLE / GEO / NO_SPACE: nothing a second run changes. Fail now, with the right message.
     * - EXTRACTOR / UNKNOWN: a newer yt-dlp is the usual fix — update (at most every 6 h, see
     *   [updateAllowed], so a burst of failures doesn't hammer GitHub's 60/h anonymous limit) and retry.
     * - FORBIDDEN: an expired or IP-bound stream URL. Re-extract, over IPv4 — IPv6 ranges get 403 more
     *   often on some carriers. No update: that isn't what's wrong.
     * - NETWORK / RATE_LIMITED: yt-dlp already retried with backoff, and WorkManager's CONNECTED
     *   constraint restarts the worker when the network returns. A second run now would fail the same
     *   way — unless the first used aria2c or the saved extraction, either of which can be the cause
     *   (aria2c's 16 connections trip rate limits that one connection doesn't).
     */
    internal fun recoveryFor(error: DlError, first: Attempt, updateAllowed: Boolean): Retry? {
        val plain = Attempt(aria2 = null, infoJson = null, ipv4 = first.ipv4)
        val usedShortcut = first.aria2 != null || first.infoJson != null
        return when (error) {
            DlError.UNAVAILABLE, DlError.GEO, DlError.NO_SPACE -> null
            DlError.EXTRACTOR, DlError.UNKNOWN -> Retry(update = updateAllowed, attempt = plain)
            DlError.FORBIDDEN -> Retry(update = false, attempt = plain.copy(ipv4 = true))
            DlError.NETWORK, DlError.RATE_LIMITED -> if (usedShortcut) Retry(update = false, attempt = plain) else null
        }
    }

    private fun updateAllowed(context: Context) =
        System.currentTimeMillis() - Prefs.lastUpdateCheck(context) > RECOVERY_UPDATE_MIN_MS

    /**
     * Everything yt-dlp is told for one download. Pure (no process, no Android), so the flag set is
     * pinned by a unit test rather than rediscovered on a device.
     */
    internal fun buildRequest(
        url: String,
        output: String,
        quality: Quality,
        filtered: Boolean,
        hw: Map<String, Int>,
        attempt: Attempt,
    ): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        val aria2 = attempt.aria2
        formatArgs(quality, filtered, hw).chunked(2).forEach { (k, v) -> addOption(k, v) }
        // Start from the share sheet's extraction instead of repeating it (~5 s on YouTube). yt-dlp
        // re-runs format selection on it with the -f/-S above and ignores the URL, with a warning.
        attempt.infoJson?.let { addOption("--load-info-json", it) }
        if (attempt.ipv4) addOption("--force-ipv4")
        addOption("-o", output)
        // A shared link often carries a playlist id; without this a single share downloads 200 videos.
        addOption("--no-playlist")
        // Keep the download timestamp rather than the upload date, so the gallery sorts it as new.
        addOption("--no-mtime")
        if (aria2 != null) {
            // Plain https through aria2c's parallel connections. An absolute path on purpose: for the bare
            // `libaria2c.so` token the library appends two `aria2c:` downloader-args of its own, and yt-dlp
            // keeps only the last per key, so --summary-interval was dropped and aria2c printed no
            // progress at all. Passing the path skips that, and both options go in one entry here.
            addOption("--downloader", aria2.bin)
            addOption("--downloader-args", "aria2c:--summary-interval=1 --ca-certificate=${aria2.caCert}")
            // HLS/DASH stay native: -N below already parallelises them.
            addOption("--downloader", "dash,m3u8:native")
        }
        // Fragments fetched in parallel. 4, not more: some CDNs answer 429 beyond ~5.
        addOption("-N", 4)
        // yt-dlp retries 10× by default but back-to-back; spread them so a 30 s network blip survives.
        addOption("--retry-sleep", "http:exp=1:30")
        addOption("--retry-sleep", "fragment:exp=1:30")
        // Default is to skip a lost fragment and "succeed" with a hole in the video. Fail instead; the
        // .part/.ytdl stays in quarantine and the next attempt resumes it.
        addOption("--abort-on-unavailable-fragments")
        // Below this yt-dlp assumes throttling (typically an unsolved YouTube n-challenge) and re-extracts.
        addOption("--throttled-rate", "100K")
        // One progress line a second instead of one per network chunk: less stdout for Python to write.
        addOption("--progress-delta", 1)
        addOption("--progress-template", PROGRESS_TEMPLATE)
        if (quality == Quality.AUDIO) {
            addOption("--extract-audio").addOption("--audio-format", "m4a")
        }
    }

    /**
     * aria2c everywhere except YouTube. Measured 2026-09-27 on the same Wi-Fi: archive.org 7.07 MiB/s
     * with aria2c vs 2.24 native (3.2×); YouTube 32 KiB/s with aria2c vs full speed native — YouTube
     * throttles anything that doesn't fetch in yt-dlp's ranged chunks.
     */
    internal fun useAria2(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return YOUTUBE_HOSTS.none { host == it || host.endsWith(".$it") }
    }

    private val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    /** Where the library installs aria2c and the CA bundle its Python uses (youtubedl-android 0.18.1 layout). */
    internal class Aria2(val bin: String, val caCert: String)

    private fun aria2Paths(context: Context) = Aria2(
        bin = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so").absolutePath,
        caCert = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/etc/tls/cert.pem").absolutePath,
    )

    private fun dropAria2Partials(dir: File) {
        dir.listFiles { f -> f.extension == "aria2" }?.forEach { control ->
            File(control.parentFile, control.nameWithoutExtension).delete()
            control.delete()
        }
    }

    /**
     * One progress reading. -1 = yt-dlp didn't say (unknown size, speed not measured yet).
     * [stream] tells consecutive streams apart: yt-dlp's format id, or aria2c's download gid.
     */
    data class DlProgress(val done: Long, val total: Long, val bytesPerSec: Long, val etaSec: Long, val stream: String?) {
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
    }

    /**
     * `bv*+ba` downloads the video, then the audio, and each restarts at 0 bytes. Adding each finished
     * stream's size keeps the byte count climbing instead of resetting.
     *
     * ponytail: the grand total is only known once the audio starts, so the bar dips from 100 % to ~90 %
     * at the switch (audio is ~10 % of a video's bytes). Prefetch both sizes (plan Phase 5) to remove it.
     */
    internal class StreamTotals {
        private var stream: String? = null
        private var carried = 0L
        private var last: DlProgress? = null

        fun add(p: DlProgress): DlProgress {
            val prev = last
            if (prev != null && p.stream != stream) carried += if (prev.total > 0) prev.total else prev.done
            stream = p.stream
            last = p
            return p.copy(done = carried + p.done, total = if (p.total > 0) carried + p.total else -1)
        }
    }

    /** Machine-readable progress; the `[download] NN.N% … ETA` prefix is kept for the library's own regex. */
    private const val PROGRESS_TEMPLATE =
        "download:[download] %(progress._percent_str)s ETA %(progress._eta_str)s |naqi|%(progress.downloaded_bytes)s" +
            "|%(progress.total_bytes,progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.format_id)s"

    /**
     * aria2c's `--summary-interval` line, e.g. `[#642275 1.1MiB/59MiB(1%) CN:16 DL:0.9MiB ETA:1m]`. The
     * `(N%)` is absent while the total is still unknown, so it is optional.
     */
    private val ARIA2 = Regex(
        """\[#(\w+) ([\d.]+)([KMG]?i?B)/([\d.]+)([KMG]?i?B)(?:\(\d+%\))?.*?DL:([\d.]+)([KMG]?i?B)(?: ETA:(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?)?]""",
    )

    /** A yt-dlp `--progress-template` line or an aria2c summary line; null for any other output. */
    internal fun parseProgress(line: String): DlProgress? {
        val tail = line.substringAfter("|naqi|", "")
        if (tail.isNotEmpty()) {
            val f = tail.split('|')
            if (f.size < 5) return null
            fun num(s: String) = s.trim().toDoubleOrNull()?.toLong() ?: -1L
            val done = num(f[0]).takeIf { it >= 0 } ?: return null
            return DlProgress(done, num(f[1]), num(f[2]), num(f[3]), f[4].trim())
        }
        val m = ARIA2.find(line) ?: return null
        val g = m.groupValues
        fun bytes(n: String, unit: String) = (n.toDouble() * when (unit) {
            "KiB" -> 1024.0
            "MiB" -> 1024.0 * 1024
            "GiB" -> 1024.0 * 1024 * 1024
            else -> 1.0
        }).toLong()
        val eta = if (g[8] + g[9] + g[10] == "") -1L else
            (g[8].toLongOrNull() ?: 0) * 3600 + (g[9].toLongOrNull() ?: 0) * 60 + (g[10].toLongOrNull() ?: 0)
        return DlProgress(bytes(g[2], g[3]), bytes(g[4], g[5]), bytes(g[6], g[7]), eta, g[1])
    }

    /**
     * `-f` and `-S` for [quality] on this device. `-S res:H` rather than `-f [height<=H]`: `res` is the
     * *smaller* side, so a portrait Short at 1080×1920 counts as 1080p instead of being filtered out.
     *
     * `+vcodec:avc` orders h264 > h265 > vp9 > av01 (yt-dlp README): MP4-muxable codecs first, which is
     * what lets a music-only job pass the video through instead of rendering a VP9 WebM first. `res`
     * comes before it, so the codec preference never costs resolution below the cap.
     *
     * ponytail: one height for all codecs (the best HW decoder's). Split per codec if a device ever
     * decodes VP9 in hardware at a lower size than H.264 — no such device has shown up.
     */
    internal fun formatArgs(quality: Quality, filtered: Boolean, hw: Map<String, Int>): List<String> {
        // AAC taken as-is, rather than Opus re-encoded to m4a by --extract-audio.
        if (quality == Quality.AUDIO) return listOf("-f", "ba/b", "-S", "+acodec:m4a")
        val cap = quality.maxHeight ?: if (filtered) FILTERED_MAX_HEIGHT else null
        val height = listOfNotNull(cap, hw.values.maxOrNull()).minOrNull()
        // An empty map means the probe failed, not "no hardware"; exclude nothing then.
        val selector = if (hw.isNotEmpty() && "av1" !in hw) "bv*[vcodec!^=av01]+ba/b/bv*+ba" else "bv*+ba/b"
        val sort = listOfNotNull(
            height?.let { "res:$it" },
            // After res on purpose: YouTube only serves a 60 fps source at 60 fps from 720p up, so fps
            // first would trade 1080p60 for 480p30.
            if (filtered) "fps:30" else null,
            "+vcodec:avc",
            "+acodec:m4a",
        ).joinToString(",")
        return listOf("-f", selector, "-S", sort)
    }

    /**
     * The measurement line every later speed change is judged by (plan Phase 0): time to first progress
     * is extraction + JS challenge solving, the rest is transfer. Codec/height come from the file, not
     * from what we asked for.
     */
    private fun logSummary(url: String, file: File, started: Long, firstProgressMs: Long, first: Attempt) {
        val totalMs = SystemClock.elapsedRealtime() - started
        val video = runCatching {
            val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
            try {
                (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?.let { "${it.getString(MediaFormat.KEY_MIME)} ${it.getInteger(MediaFormat.KEY_WIDTH)}x${it.getInteger(MediaFormat.KEY_HEIGHT)}" }
            } finally {
                ex.release()
            }
        }.getOrNull() ?: "none"
        val bytes = file.length()
        val transferMs = (totalMs - firstProgressMs.coerceAtLeast(0)).coerceAtLeast(1)
        Log.i(
            "NaqiDl",
            "host=${Uri.parse(url).host} first_progress_ms=$firstProgressMs total_ms=$totalMs bytes=$bytes " +
                "transfer_kBps=${bytes / transferMs} ext=${file.extension} video=$video " +
                "aria2=${first.aria2 != null} reused_info=${first.infoJson != null}",
        )
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
    // `aria2` is aria2c's resume control file, left next to its `.part` until the transfer completes.
    private val IN_FLIGHT = setOf("part", "ytdl", "temp", "aria2")
    private const val FILTERED_MAX_HEIGHT = 1080
    private const val INFO_JSON = "info.json"
    private const val INFO_MAX_AGE_MS = 60L * 60 * 1000
    private const val RECOVERY_UPDATE_MIN_MS = 6L * 60 * 60 * 1000
}
