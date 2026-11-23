package com.haithamassoli.naqi.work

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.media.containerDurationMs
import com.haithamassoli.naqi.media.firstTrackIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * Naqi's optional cloud for long sources (`server/naqi_modal.py`), two jobs that each stand in for one
 * the phone already knows how to do:
 *
 * - [removeMusic]: the audio track goes up, the same htdemucs runs on a GPU, and the kept stems come
 *   back as the AAC .m4a `AudioPipeline.removeMusic` would have written.
 * - [analyze]: body mode's pass 1 — the expensive part, YOLO11n-seg at 10 fps — comes back in the exact
 *   shape of one segment checkpoint, and the worker builds the EDL and renders the blur itself.
 *
 * The server runs on Modal's free monthly credit only, so **every failure answers "do it here"** — no
 * network, credit spent (429), a server error — and only a cancel propagates.
 */
internal object Cloud {

    private const val TAG = "Cloud"

    /** Shorter sources stay on the phone: the upload and a ~1 min GPU cold start would eat the win. */
    const val MIN_DURATION_MS = 10L * 60 * 1000

    private const val POLL_MS = 3_000L

    /** 8 MB: under the 150 s request cap down to ~0.5 Mbit/s of uplink. */
    private const val PART_BYTES = 8 shl 20

    private const val PARALLEL_PARTS = 4

    private fun eligible(context: Context, durationMs: Long) =
        BuildConfig.NAQI_CLOUD_URL.isNotEmpty() && Prefs.cloud(context) && durationMs >= MIN_DURATION_MS

    /** True when [out] holds the separated .m4a; false means separate on the phone. */
    suspend fun removeMusic(
        context: Context, uri: Uri, keepStems: String, out: File,
        onProgress: (Int) -> Unit, isCancelled: () -> Boolean,
    ): Boolean {
        val durationMs = context.containerDurationMs(uri)
        if (!eligible(context, durationMs)) return false
        val track = File(out.parentFile, "cloud-audio")
        return attempt("music", out) {
            // The audio track alone (~60 MB/h) when MediaMuxer can carry it; otherwise the whole source,
            // which the server's ffmpeg reads whatever it holds (AC-3, DTS, ...).
            val extracted = extractAudio(context, uri, track, isCancelled)
            run(context, "/v1/music?keep=$keepStems", durationMs, if (extracted) Upload.Of(track) else Upload.Source(uri), onProgress, isCancelled) { body, size ->
                out.outputStream().use { copy(body, it, size, 90, 8, onProgress, isCancelled) }
            }
            track.delete()
            if (out.length() == 0L) throw IOException("empty result")
        }.also { track.delete() }
    }

    /**
     * Body mode's pass 1 for the whole source, or null to run it on the phone. [cache] keeps the answer
     * across a resume, so a killed job does not upload the film twice.
     */
    suspend fun analyze(
        context: Context, uri: Uri, durationMs: Long, who: String, precise: Boolean, nsfw: Boolean, strictness: Int,
        cache: File, onProgress: (Int) -> Unit, isCancelled: () -> Boolean,
    ): Checkpoint.Analysis? {
        runCatching { return Checkpoint.parseAnalysis(cache.readText()) }
        if (!eligible(context, durationMs)) return null
        val query = "/v1/analyze?who=$who&precise=$precise&nsfw=$nsfw&strictness=$strictness"
        val ok = attempt("analyze", cache) {
            run(context, query, durationMs, Upload.Source(uri), onProgress, isCancelled) { body, _ ->
                val json = body.bufferedReader().readText()
                Checkpoint.parseAnalysis(json) // reject a malformed answer before it can be cached
                Checkpoint.atomicWrite(cache, json)
            }
        }
        return if (ok) Checkpoint.parseAnalysis(cache.readText()) else null
    }

    /** [block] or false; [out] is deleted on any failure, and a cancel still propagates. */
    private suspend fun attempt(job: String, out: File, block: suspend () -> Unit): Boolean = try {
        val t0 = System.nanoTime()
        withContext(Dispatchers.IO) { block() }
        Log.i(TAG, "$job ok in ${(System.nanoTime() - t0) / 1_000_000}ms")
        true
    } catch (c: CancellationException) {
        out.delete()
        throw c
    } catch (t: Throwable) {
        Log.w(TAG, "$job unavailable, doing it on the phone: ${t.message}")
        out.delete()
        false
    }

    private sealed interface Upload {
        class Of(val file: File) : Upload
        class Source(val uri: Uri) : Upload
    }

    /**
     * One server job: ask, upload, poll, then hand the 200 body to [sink]. Bands: upload 2..20, server
     * 20..90, download 90..98.
     */
    private suspend fun run(
        context: Context, path: String, durationMs: Long, upload: Upload,
        onProgress: (Int) -> Unit, isCancelled: () -> Boolean, sink: (InputStream, Long) -> Unit,
    ) {
        // Asked before a single byte goes up: a spent credit must not cost the user an upload to find out.
        val job = open("/v1/upload?seconds=${durationMs / 1000}", context).useConnection { c ->
            c.requestMethod = "POST"
            if (c.responseCode != 200) throw IOException("server refused: HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText()).getString("job")
        }
        onProgress(2)
        val size = when (upload) {
            is Upload.Of -> upload.file.length()
            is Upload.Source -> context.contentResolver.openAssetFileDescriptor(upload.uri, "r")?.use { it.length } ?: -1L
        }
        val input = when (upload) {
            is Upload.Of -> upload.file.inputStream()
            is Upload.Source -> context.contentResolver.openInputStream(upload.uri) ?: throw IOException("unreadable source")
        }
        // In parts: a Modal web request may not run past 150 s, and a whole film over a phone's uplink would.
        // A few in flight, because one request ingests at only ~3 MB/s on Modal's side.
        val sent = AtomicLong()
        val inFlight = Semaphore(PARALLEL_PARTS)
        input.use { src ->
            coroutineScope {
                var part = 0
                while (true) {
                    val buf = ByteArray(PART_BYTES)
                    var n = 0
                    while (n < buf.size) { val r = src.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                    if (n == 0) break
                    val index = part++
                    inFlight.acquire()
                    launch {
                        try {
                            putPart(job, index, buf, n, isCancelled)
                        } finally {
                            inFlight.release()
                        }
                        if (size > 0) onProgress(2 + (18 * sent.addAndGet(n.toLong()) / size).toInt())
                    }
                }
            }
        }
        val id = open("$path&job=$job", null).useConnection { c ->
            c.requestMethod = "POST"
            if (c.responseCode != 200) throw IOException("submit HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText()).getString("id")
        }
        while (true) {
            if (isCancelled()) throw CancellationException("cancelled")
            val done = open("/v1/jobs/$id", null).useConnection { c ->
                when (val code = c.responseCode) {
                    202 -> {
                        onProgress(20 + 70 * JSONObject(c.inputStream.bufferedReader().readText()).optInt("progress") / 100)
                        false
                    }
                    200 -> {
                        c.inputStream.use { sink(it, c.contentLengthLong) }
                        onProgress(98)
                        true
                    }
                    else -> throw IOException("result HTTP $code")
                }
            }
            if (done) return
            delay(POLL_MS)
        }
    }

    /** One upload part, retried: on a phone a dropped request is ordinary, and a part is cheap to resend. */
    private fun putPart(job: String, part: Int, buf: ByteArray, n: Int, isCancelled: () -> Boolean) {
        repeat(3) { attempt ->
            if (isCancelled()) throw CancellationException("cancelled")
            try {
                open("/v1/upload/$job/$part", null).useConnection { c ->
                    c.requestMethod = "PUT"
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(n)
                    c.outputStream.use { it.write(buf, 0, n) }
                    if (c.responseCode != 204) throw IOException("part HTTP ${c.responseCode}")
                }
                return
            } catch (e: IOException) {
                if (attempt == 2) throw e
            }
        }
    }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    private fun open(path: String, context: Context?): HttpURLConnection =
        (URL(BuildConfig.NAQI_CLOUD_URL + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("x-naqi-key", BuildConfig.NAQI_CLOUD_KEY)
            if (context != null) setRequestProperty("x-naqi-device", Prefs.deviceId(context))
        }

    /** Copy [uri]'s audio track sample-for-sample into [dst]; false when MediaMuxer cannot carry its codec. */
    private fun extractAudio(context: Context, uri: Uri, dst: File, isCancelled: () -> Boolean): Boolean {
        val ext = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ext.setDataSource(context, uri, null)
            val ix = ext.firstTrackIndex("audio/") ?: return false
            val format = ext.getTrackFormat(ix)
            val container = when (format.getString(MediaFormat.KEY_MIME)) {
                "audio/mp4a-latm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                "audio/opus", "audio/vorbis" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> return false
            }
            ext.selectTrack(ix)
            muxer = MediaMuxer(dst.path, container)
            val track = muxer.addTrack(format)
            muxer.start()
            val buf = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (isCancelled()) throw CancellationException("cancelled")
                val n = ext.readSampleData(buf, 0)
                if (n < 0) break
                // An AAC priming edit puts the first sample below 0, which MediaMuxer rejects. It is ~20 ms
                // of encoder delay, and the phone's own separator clamps the same epoch to 0.
                if (ext.sampleTime >= 0) {
                    info.set(0, n, ext.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, buf, info)
                }
                ext.advance()
            }
            muxer.stop()
            return true
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "audio track extract failed, uploading the source: ${t.message}")
            return false
        } finally {
            runCatching { muxer?.release() }
            ext.release()
        }
    }

    /** Stream [src] to [dst], reporting [base]..[base]+[span] by bytes when [size] is known. */
    private fun copy(
        src: InputStream, dst: OutputStream, size: Long, base: Int, span: Int,
        onProgress: (Int) -> Unit, isCancelled: () -> Boolean,
    ) {
        val buf = ByteArray(1 shl 16)
        var done = 0L
        var last = -1
        while (true) {
            if (isCancelled()) throw CancellationException("cancelled")
            val n = src.read(buf)
            if (n < 0) break
            dst.write(buf, 0, n)
            done += n
            if (size > 0) {
                val p = base + (span * done / size).toInt()
                if (p != last) { onProgress(p); last = p }
            }
        }
    }
}
