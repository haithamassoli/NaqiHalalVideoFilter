package com.haithamassoli.naqi.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.haithamassoli.naqi.download.DownloadWorker
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Thin WorkManager facade for the filtering job. */
object JobController {

    /**
     * @param forceIntervalsMs debug-only "startMs-endMs,startMs-endMs" forced censor spans (E2E hook).
     * @param segmentMs debug-only segment-length override; any positive value forces the Phase 2 segmented
     *   route so it can be exercised on a clip short enough to iterate on.
     */
    fun start(
        context: Context,
        ops: FilterOps,
        inputUri: String?,
        forceIntervalsMs: String? = null,
        segmentMs: Long = 0L,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<FilterWorker>()
            .setInputData(
                workDataOf(
                    FilterWorker.KEY_REMOVE_MUSIC to ops.removeMusic,
                    FilterWorker.KEY_CENSOR_WOMEN to ops.censorWomen,
                    FilterWorker.KEY_INPUT_URI to inputUri,
                    FilterWorker.KEY_STRICTNESS to ops.strictness,
                    FilterWorker.KEY_BLUR_AMOUNT to ops.blurAmount,
                    FilterWorker.KEY_GRAYSCALE to ops.grayscale,
                    FilterWorker.KEY_BLUR_UNKNOWN to ops.blurUnknownFaces,
                    FilterWorker.KEY_KEEP_STEMS to ops.keepStems,
                    FilterWorker.KEY_FORCE_INTERVALS to forceIntervalsMs,
                    FilterWorker.KEY_SEGMENT_MS to segmentMs,
                ),
            )
            .build()
        // KEEP by default (`long-film-plan.md` Phase 1): REPLACE meant one stray tap on Start cancelled
        // a job that could be four hours in and threw away every temp on the way out. KEEP makes that
        // tap a no-op. The UI also disables Start while a job runs — this is the half that cannot be
        // raced, since a tap can land between the flow emitting and the button recomposing.
        //
        // The queue passes APPEND_OR_REPLACE instead: a shared item must never be *dropped* because
        // something else happens to be running, which is exactly what KEEP would silently do. Appending
        // makes the unique work a FIFO chain that survives process death for free.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FilterWorker.UNIQUE_WORK, policy, request)
        return request.id
    }

    /**
     * Enqueue a download. Its own unique work name, so it runs concurrently with any filter job —
     * one is network-bound and the other CPU-bound, and serializing them would halve throughput for
     * nothing. [DownloadWorker] appends the filter request itself once the bytes are on disk.
     *
     * `NetworkType.CONNECTED` rather than UNMETERED: the user asked for this video, and refusing to
     * fetch it on mobile data is a policy they did not choose.
     */
    fun download(
        context: Context,
        url: String,
        quality: Downloader.Quality,
        ops: FilterOps,
        title: String? = null,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_QUALITY to quality.name,
                    DownloadWorker.KEY_TITLE to title,
                    FilterWorker.KEY_REMOVE_MUSIC to ops.removeMusic,
                    FilterWorker.KEY_CENSOR_WOMEN to ops.censorWomen,
                    FilterWorker.KEY_STRICTNESS to ops.strictness,
                    FilterWorker.KEY_BLUR_AMOUNT to ops.blurAmount,
                    FilterWorker.KEY_GRAYSCALE to ops.grayscale,
                    FilterWorker.KEY_BLUR_UNKNOWN to ops.blurUnknownFaces,
                    FilterWorker.KEY_KEEP_STEMS to ops.keepStems,
                ),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWorker.UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }

    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(FilterWorker.UNIQUE_WORK)

    /** Downloads are a separate chain; the UI shows both. */
    fun observeDownloads(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(DownloadWorker.UNIQUE_WORK)

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FilterWorker.UNIQUE_WORK)
    }
}
