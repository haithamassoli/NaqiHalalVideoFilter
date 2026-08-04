package com.haithamassoli.naqi.work

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
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

    /** Ties every request belonging to one queue item together, so it can be cancelled as a unit. */
    private fun itemTag(queueId: String) = "naqi_item_$queueId"

    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(FilterWorker.UNIQUE_WORK)

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
     * resumes from there.
     */
    internal fun retry(context: Context, item: Queue.Item) {
        val restarted = item.copy(state = Queue.State.PENDING_FILTER, error = null)
        Queue.update(context, item.id) { restarted }
        submit(context, restarted)
    }

    /**
     * Cancel one item, then repair the chain.
     *
     * WorkManager cascades a cancellation to everything chained **behind** the cancelled request, so
     * cancelling item 2 of 5 silently kills 3, 4 and 5 as well. They are re-appended here.
     */
    internal fun cancelItem(context: Context, item: Queue.Item) {
        WorkManager.getInstance(context).cancelAllWorkByTag(itemTag(item.id))
        Queue.remove(context, item.id)

        val survivors = Queue.pending(context)
        if (survivors.isNotEmpty()) {
            Log.i(TAG, "cancel-repair: re-appending ${survivors.size} item(s) after cancelling ${item.id}")
            survivors.forEach { submit(context, it) }
        }
    }

    /** Append this item to the filter chain, unless it is already running or finished. */
    private fun submit(context: Context, item: Queue.Item) {
        if (item.state != Queue.State.PENDING_FILTER) {
            // Re-submitting a running or finished item would duplicate it.
            Log.w(TAG, "submit() ignored for ${item.id} in state ${item.state}")
            return
        }
        start(
            context, item.ops, item.sourceUri,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE, queueId = item.id,
        )
    }

    private const val TAG = "JobController"
}
