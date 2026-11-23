package com.haithamassoli.naqi.ml

import android.content.Context
import android.util.Log
import com.haithamassoli.naqi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/** Why a download produced no usable file — one value per message the UI has to show. */
enum class DownloadError {
    /** No public URL and no `NAQI_MODEL_BASE_URL`: a build-config gap, nothing the user can fix. */
    NO_SOURCE,

    /** DNS/connect/read failure — airplane mode, no signal, host down. Retrying later is the fix. */
    OFFLINE,

    /** Reached the server and it refused: 404 stale URL, 403 expired link, 5xx. */
    HTTP,

    /** Whole file transferred, wrong bytes. The partial is deleted, so a retry starts clean. */
    HASH_MISMATCH,

    /** Not enough free space for the remaining bytes. The partial is kept for a later resume. */
    NO_SPACE,

    /** Catch-all local I/O failure, so a broken download is always a message and never a crash. */
    IO,
}

sealed interface DownloadResult {
    data class Done(val file: File) : DownloadResult
    data class Failed(val error: DownloadError, val detail: String) : DownloadResult
}

/**
 * M3 first-use model fetch into app-private storage (`filesDir/models/`).
 *
 * htdemucs alone is ~88 MB, so models are fetched rather than shipped in the APK. Downloads land at
 * exactly the path [ModelSmoke]'s asset copy uses, so [Infer] never has to know which source
 * installed a file. `HttpURLConnection` only — three GETs do not justify an HTTP client dependency.
 *
 * Invariants worth keeping: bytes stream into `<name>.part`; the SHA-256 of the *whole* part is
 * checked before the rename into place, so the path ORT loads never holds unverified bytes; and a
 * part that fails the check is deleted, so corrupt bytes can never wedge every later resume.
 */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val BUFFER = 64 * 1024

    /** Headroom over the transfer size — fetching a model must not be what fills the data partition. */
    private const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024

    /** Shared with [ModelSmoke]'s asset copy: one directory, one file name per model. */
    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /** The installed model, or null when it still has to be fetched (or copied from assets). */
    fun installed(context: Context, model: NaqiModel): File? =
        File(modelsDir(context), model.assetName).takeIf { it.length() > 0 }

    /**
     * Fetch [model] into `filesDir/models`, resuming an earlier partial transfer when one exists.
     *
     * Blocking network + hashing, hence [Dispatchers.IO]; cancelling the calling coroutine stops the
     * read loop and *keeps* the `.part`, so the next call resumes instead of restarting.
     * [onProgress] gets (bytesDone, totalBytes), total `-1` while the server sends no length.
     */
    suspend fun download(
        context: Context,
        model: NaqiModel,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DownloadResult = withContext(Dispatchers.IO) {
        installed(context, model)?.let { return@withContext DownloadResult.Done(it) }

        val url = try {
            sourceUrl(model)
        } catch (e: MalformedURLException) {
            Log.w(TAG, "unusable model URL for ${model.assetName}", e)
            null
        }
        if (url == null) {
            val detail = "no download source configured for ${model.assetName}"
            return@withContext DownloadResult.Failed(DownloadError.NO_SOURCE, detail)
        }

        val dir = modelsDir(context)
        val part = File(dir, "${model.assetName}.part")
        try {
            fetchToPart(url, dir, part, onProgress)?.let { return@withContext it }

            // Hash the whole part, not a running digest: on a resume this process never saw the bytes
            // already on disk, so only a full re-read can prove the file.
            val actual = sha256(part)
            if (!actual.equals(model.sha256, ignoreCase = true)) {
                part.delete()
                val detail = "sha256 $actual, expected ${model.sha256}"
                return@withContext DownloadResult.Failed(DownloadError.HASH_MISMATCH, detail)
            }

            val dest = File(dir, model.assetName)
            if (!part.renameTo(dest)) {
                return@withContext DownloadResult.Failed(DownloadError.IO, "could not move ${part.name} into place")
            }
            Log.i(TAG, "installed ${model.assetName} (${dest.length()} bytes)")
            DownloadResult.Done(dest)
        } catch (e: IOException) {
            Log.w(TAG, "download failed for ${model.assetName}", e)
            when {
                // ENOSPC has no dedicated exception on Android — it arrives as an errno in the message.
                e.message?.contains("ENOSPC") == true ->
                    DownloadResult.Failed(DownloadError.NO_SPACE, "out of space")
                e is UnknownHostException || e is SocketException || e is SocketTimeoutException ->
                    DownloadResult.Failed(DownloadError.OFFLINE, "cannot reach ${url.host}")
                else -> DownloadResult.Failed(DownloadError.IO, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Stream the response body into [part], appending after whatever it already holds. Returns null
     * on a complete transfer, else the failure to report; exceptions bubble to [download]'s classifier.
     */
    private suspend fun fetchToPart(
        url: URL,
        dir: File,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): DownloadResult.Failed? {
        // The one source of truth for the resume offset: the bytes already on disk.
        var have = part.length()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> Unit // 206: range honoured, body continues where we stopped
                HttpURLConnection.HTTP_OK -> have = 0 // 200: range ignored, body is the whole file — overwrite
                416 -> { // Requested Range Not Satisfiable
                    // The part is at or past the object's real length (server file changed, or junk part).
                    // Drop it and retry once — with have == 0 no Range is sent, so 416 cannot recur.
                    conn.disconnect()
                    part.delete()
                    return fetchToPart(url, dir, part, onProgress)
                }
                else -> {
                    val detail = "HTTP $code ${conn.responseMessage.orEmpty()}".trim()
                    return DownloadResult.Failed(DownloadError.HTTP, detail)
                }
            }

            val remaining = conn.contentLengthLong // length of THIS body, i.e. what is still missing
            val total = if (remaining >= 0) have + remaining else -1L
            // Fail up front rather than dying mid-write with ENOSPC and leaving a part we can't finish.
            if (remaining >= 0 && dir.usableSpace < remaining + SPACE_MARGIN_BYTES) {
                return DownloadResult.Failed(DownloadError.NO_SPACE, "needs ${remaining / 1_000_000} MB free")
            }

            var done = have
            conn.inputStream.use { input ->
                FileOutputStream(part, /* append = */ have > 0).use { output ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive() // cancelling keeps the part for a resume
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            // A short body means the connection dropped. The part is valid up to `done`, so resume next call.
            if (total > 0 && done < total) {
                return DownloadResult.Failed(DownloadError.OFFLINE, "connection lost at $done of $total bytes")
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Public artifacts carry their own URL; the locally converted ones resolve against
     * `BuildConfig.NAQI_MODEL_BASE_URL`, which is empty until a host exists — null then, so an
     * unconfigured build reports [DownloadError.NO_SOURCE] instead of requesting "null/htdemucs…".
     */
    private fun sourceUrl(model: NaqiModel): URL? {
        model.downloadUrl?.let { return URL(it) }
        val base = BuildConfig.NAQI_MODEL_BASE_URL.trim()
        if (base.isEmpty()) return null
        return URL(if (base.endsWith("/")) base + model.assetName else "$base/${model.assetName}")
    }
}
