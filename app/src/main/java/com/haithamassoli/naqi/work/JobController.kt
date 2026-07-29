package com.haithamassoli.naqi.work

import android.content.Context
import android.util.Log
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
        queueId: String? = null,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<FilterWorker>()
            .apply { queueId?.let { addTag(itemTag(it)) } }
            .setInputData(
                workDataOf(
                    *ops.pairs(),
                    FilterWorker.KEY_INPUT_URI to inputUri,
                    FilterWorker.KEY_FORCE_INTERVALS to forceIntervalsMs,
                    FilterWorker.KEY_SEGMENT_MS to segmentMs,
                    FilterWorker.KEY_QUEUE_ID to queueId,
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
        sizeBytes: Long = 0L,
        queueId: String? = null,
    ): UUID {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            // Tagged with the URL so a second share of the same link can be recognised as a duplicate.
            // WorkInfo does not expose a request's input data, and tags are the only thing about a
            // request that survives process death and is queryable — see [isQueued].
            .addTag(urlTag(url))
            .apply { queueId?.let { addTag(itemTag(it)) } }
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    *ops.pairs(),
                    DownloadWorker.KEY_URL to url,
                    DownloadWorker.KEY_QUALITY to quality.name,
                    DownloadWorker.KEY_TITLE to title,
                    DownloadWorker.KEY_SIZE_BYTES to sizeBytes,
                    FilterWorker.KEY_QUEUE_ID to queueId,
                ),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DownloadWorker.UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }

    /**
     * Is this URL already downloading or waiting to? Sharing the same link twice must produce one item,
     * not two — the PRD's dedupe rule. Only non-terminal work counts: a finished or failed download of
     * the same URL should be re-shareable, which is how a retry is expressed.
     */
    fun isQueued(context: Context, url: String): Boolean =
        runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosByTag(urlTag(url)).get()
                .any { !it.state.isFinished }
        }.getOrDefault(false)

    private fun urlTag(url: String) = "naqi_url_${JobStore.keyOf(url)}"

    /** Ties every request belonging to one queue item together, so it can be cancelled as a unit. */
    private fun itemTag(queueId: String) = "naqi_item_$queueId"

    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(FilterWorker.UNIQUE_WORK)

    /** Downloads are a separate chain; the UI shows both. */
    fun observeDownloads(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(DownloadWorker.UNIQUE_WORK)

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FilterWorker.UNIQUE_WORK)
    }

    // ---------------------------------------------------------------------------------------------
    // Queue operations. Everything above is the scheduler; everything below keeps `queue.json` and the
    // WorkManager chains describing the same world.
    // ---------------------------------------------------------------------------------------------

    /** Add a shared item to the queue and enqueue the work that will drain it. */
    internal fun enqueue(context: Context, item: Queue.Item) {
        Queue.add(context, item)
        submit(context, item)
    }

    /**
     * Re-run a failed or cancelled item from wherever it got to.
     *
     * Retry is deliberately not a special code path: it re-submits the same (uri, ops), which lands on
     * the same [JobStore] key, finds whatever scratch and checkpoints the last attempt left, and
     * resumes from there. A failed download likewise still has its `.part`, which yt-dlp continues.
     */
    internal fun retry(context: Context, item: Queue.Item) {
        val restarted = item.copy(
            state = if (item.sourceUri == null) Queue.State.PENDING_DOWNLOAD else Queue.State.PENDING_FILTER,
            error = null,
        )
        Queue.update(context, item.id) { restarted }
        submit(context, restarted)
    }

    /**
     * Cancel one item, then repair the chain it was in.
     *
     * WorkManager cascades a cancellation to everything chained **behind** the cancelled request, so
     * cancelling item 2 of 5 silently kills 3, 4 and 5 as well. They are re-appended here. Only the
     * affected chain is repaired: the other one was never touched, and re-appending its items would
     * queue them twice.
     */
    internal fun cancelItem(context: Context, item: Queue.Item) {
        val wasDownloading = item.state == Queue.State.PENDING_DOWNLOAD || item.state == Queue.State.DOWNLOADING
        WorkManager.getInstance(context).cancelAllWorkByTag(itemTag(item.id))
        Queue.remove(context, item.id)

        val survivors = Queue.pending(context).filter {
            (it.state == Queue.State.PENDING_DOWNLOAD) == wasDownloading
        }
        if (survivors.isNotEmpty()) {
            Log.i(TAG, "cancel-repair: re-appending ${survivors.size} item(s) after cancelling ${item.id}")
            survivors.forEach { submit(context, it) }
        }
    }

    /** Enqueue whichever half of the pipeline this item is waiting on. */
    private fun submit(context: Context, item: Queue.Item) {
        when (item.state) {
            Queue.State.PENDING_DOWNLOAD -> item.url?.let {
                download(
                    context, it, Downloader.Quality.of(item.quality), item.ops,
                    title = item.title, queueId = item.id,
                )
            }

            Queue.State.PENDING_FILTER -> item.sourceUri?.let {
                start(
                    context, item.ops, it,
                    policy = ExistingWorkPolicy.APPEND_OR_REPLACE, queueId = item.id,
                )
            }

            // Anything else is already running or finished; re-submitting would duplicate it.
            else -> Log.w(TAG, "submit() ignored for ${item.id} in state ${item.state}")
        }
    }

    private const val TAG = "JobController"
}
