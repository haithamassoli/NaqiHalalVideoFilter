package com.haithamassoli.naqi.work

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.analysis.FaceTracker
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.analysis.NsfwGate
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.ml.Infer
import com.haithamassoli.naqi.render.RenderPipeline
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * M1 censor job. Two passes over one source video, promoted to a foreground service with staged
 * progress and cancellation:
 *
 * - Pass 1 (0..50) decodes at 10 fps; every frame feeds ML Kit face tracking, every 2nd frame (5 fps)
 *   the NSFW gate. Firings become hysteresis censor intervals; face tracks vote gender. Together they
 *   form the [Edl].
 * - Pass 2 (50..100) renders the EDL with [RenderPipeline] into a cache temp, then publishes it into
 *   `Movies/Naqi` (MediaStore on API 29+, public dir + media scan below).
 *
 * Music removal is M2: any request for it fails fast with a message rather than silently succeeding.
 * Cancel/failure delete the temp and any un-published MediaStore row; the ORT sessions and the face
 * detector always close. Cancellation propagates through the suspend calls untouched.
 */
class FilterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (inputData.getBoolean(KEY_REMOVE_MUSIC, false)) {
            return Result.failure(workDataOf(KEY_OUTPUT_MESSAGE to MUSIC_M2_MESSAGE))
        }
        if (!inputData.getBoolean(KEY_CENSOR_WOMEN, false)) return Result.failure()
        val inputUri = (inputData.getString(KEY_INPUT_URI) ?: return Result.failure()).toUri()

        val strictness = inputData.getInt(KEY_STRICTNESS, 50)
        val blurAmount = inputData.getInt(KEY_BLUR_AMOUNT, 60)
        val grayscale = inputData.getBoolean(KEY_GRAYSCALE, false)
        val blurUnknownFaces = inputData.getBoolean(KEY_BLUR_UNKNOWN, false)

        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val tempFile = File(applicationContext.cacheDir, "naqi-render-$id.mp4")
        val faceTracker = FaceTracker(blurUnknownFaces)
        try {
            val edl = analyze(inputUri, strictness, faceTracker)
            render(inputUri, tempFile, edl, blurAmount, grayscale)
            val displayName = outputName(inputUri)
            val outputUri = publish(tempFile, displayName)
            return Result.success(workDataOf(KEY_OUTPUT_NAME to displayName, KEY_OUTPUT_URI to outputUri.toString()))
        } catch (c: CancellationException) {
            throw c // WorkManager cancellation — never swallow it
        } catch (t: Throwable) {
            Log.e(TAG, "filter job failed", t)
            return Result.failure(workDataOf(KEY_OUTPUT_MESSAGE to (t.message ?: "Filtering failed.")))
        } finally {
            runCatching { faceTracker.closeDetector() }
            runCatching { Infer.close() }
            runCatching { if (tempFile.exists()) tempFile.delete() }
        }
    }

    /** Pass 1: sample once, run faces every frame + the gate every 2nd frame, build the EDL. */
    private suspend fun analyze(uri: Uri, strictness: Int, faceTracker: FaceTracker): Edl {
        val durationMs = FrameSampler.probe(applicationContext, uri).durationMs.coerceAtLeast(1L)
        val stage = stage(R.string.stage_analyzing)
        val firings = ArrayList<Long>()
        var index = 0
        var lastPct = -1
        FrameSampler.sample(applicationContext, uri, fps = 10f, maxDim = 640) { bitmap, ptsMs ->
            faceTracker.onFrame(bitmap, ptsMs)
            if (index % 2 == 0 && NsfwGate.fires(Infer.nsfw(applicationContext, bitmap), strictness)) firings += ptsMs
            index++
            val pct = ((ptsMs.toFloat() / durationMs) * 50f).toInt().coerceIn(0, 50)
            if (pct != lastPct) { lastPct = pct; report(stage, pct) }
        }

        var intervals = NsfwGate.intervals(firings, durationMs)
        // Debug E2E hook: force full-frame spans so SFW test assets still exercise pass 2 censoring.
        if (BuildConfig.DEBUG) {
            val forced = parseForceIntervals(inputData.getString(KEY_FORCE_INTERVALS))
            if (forced.isNotEmpty()) intervals = intervals + forced
        }
        val faceTracks = faceTracker.finish(applicationContext)
        Log.i(TAG, "pass1: gateFirings=${firings.size} intervals=$intervals censorFaceTracks=${faceTracks.size}")
        return Edl(intervals, faceTracks)
    }

    /** Pass 2: render the EDL into [tempFile]; progress maps the transformer's 0..100 onto 50..100. */
    private suspend fun render(uri: Uri, tempFile: File, edl: Edl, blurAmount: Int, grayscale: Boolean) {
        val stage = stage(R.string.stage_rendering)
        report(stage, 50)
        val meta = FrameSampler.probe(applicationContext, uri)
        RenderPipeline.renderCensor(applicationContext, uri, tempFile, edl, blurAmount, grayscale, meta) { p ->
            val overall = 50 + p / 2
            // onProgress is non-suspend (runs on the transformer's Looper) — use the async variants.
            setProgressAsync(workDataOf(KEY_PROGRESS to overall, KEY_STAGE to stage))
            setForegroundAsync(foregroundInfo(stage, overall))
        }
    }

    /**
     * Copy the temp into `Movies/Naqi` and return its uri. The blocking copy has no suspension point,
     * so a cancel that arrives mid-copy only surfaces after it: [isStopped] is re-checked before the
     * output is finalized, and any failure or cancel deletes the half-written row/file — a cancelled or
     * failed job must never leave output in `Movies/`.
     */
    private fun publish(tempFile: File, displayName: String): Uri {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, MIME_MP4)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Naqi")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: error("MediaStore insert failed")
            try {
                (resolver.openOutputStream(item) ?: error("MediaStore openOutputStream failed"))
                    .use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                if (isStopped) throw CancellationException("cancelled during publish")
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(item, values, null, null)
                return item
            } catch (t: Throwable) {
                runCatching { resolver.delete(item, null, null) } // drop the un-finalized (still-pending) row
                throw t
            }
        }
        // API 26-28: write into the public Movies/Naqi dir (needs WRITE_EXTERNAL_STORAGE) and hand it to the scanner.
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Naqi").apply { mkdirs() }
        val dest = File(dir, displayName)
        try {
            tempFile.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            if (isStopped) throw CancellationException("cancelled during publish")
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(MIME_MP4), null)
            return Uri.fromFile(dest)
        } catch (t: Throwable) {
            dest.delete() // remove the partial/complete copy before the scanner ever sees it
            throw t
        }
    }

    /** `<sourceNameNoExt>-naqi-<epochMillis>.mp4`; source name queried, falling back to "video". */
    private fun outputName(uri: Uri): String {
        val source = applicationContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "video"
        return "${source.substringBeforeLast('.')}-naqi-${System.currentTimeMillis()}.mp4"
    }

    private suspend fun report(stage: String, pct: Int) {
        setProgress(workDataOf(KEY_PROGRESS to pct, KEY_STAGE to stage))
        setForeground(foregroundInfo(stage, pct))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(stage(R.string.stage_analyzing), 0)

    private fun stage(resId: Int): String = applicationContext.getString(resId)

    private fun foregroundInfo(stage: String, progress: Int) =
        JobNotifications.foregroundInfo(applicationContext, id, stage, progress)

    companion object {
        const val KEY_REMOVE_MUSIC = "remove_music"
        const val KEY_CENSOR_WOMEN = "censor_women"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_STRICTNESS = "strictness"
        const val KEY_BLUR_AMOUNT = "blur_amount"
        const val KEY_GRAYSCALE = "grayscale"
        const val KEY_BLUR_UNKNOWN = "blur_unknown_faces"
        const val KEY_FORCE_INTERVALS = "force_intervals"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_MESSAGE = "output_message"
        const val UNIQUE_WORK = "naqi_filter_job"

        private const val TAG = "FilterWorker"
        private const val MIME_MP4 = "video/mp4"
        private const val MUSIC_M2_MESSAGE = "Music removal lands in M2. Use “Censor women” for now."

        /** Parse "startMs-endMs,startMs-endMs"; bad segments are skipped. */
        private fun parseForceIntervals(spec: String?): List<LongRange> {
            if (spec.isNullOrBlank()) return emptyList()
            return spec.split(",").mapNotNull { part ->
                val nums = part.split("-")
                val start = nums.getOrNull(0)?.trim()?.toLongOrNull()
                val end = nums.getOrNull(1)?.trim()?.toLongOrNull()
                if (start != null && end != null) start..end else null
            }
        }
    }
}
