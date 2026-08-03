package com.haithamassoli.naqi.work

import android.content.Context
import androidx.annotation.StringRes
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.haithamassoli.naqi.model.FilterOps

/**
 * What [FilterWorker] and [com.haithamassoli.naqi.download.DownloadWorker] share: the link back to the
 * queue item that spawned them, and the failure rule that link forces.
 *
 * **A queue-driven run never returns failure.** WorkManager fails every request chained behind a failed
 * one, so a single unfilterable item or dead link would take the rest of the queue with it. The real
 * outcome is recorded in `queue.json`, where the UI reads it; the chain only ever learns that this
 * request finished. The picker/debug path (no [FilterWorker.KEY_QUEUE_ID]) keeps real failures — that is
 * what `JobsScreen` reads, and nothing is chained behind it to kill.
 *
 * This lived twice, once per worker, with the invariant restated in both. Two copies of a rule that must
 * hold in both places is one edit away from holding in neither.
 */
// Public, not internal: WorkManager instantiates the two subclasses reflectively, so they must stay
// public — and a public class may not expose an internal supertype. The members below carry the
// module scoping instead, which is what actually matters (Queue.Item is internal too).
abstract class QueuedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    /** Non-null when this run came from the share queue rather than the picker or the debug intent. */
    internal val queueId: String? = inputData.getString(FilterWorker.KEY_QUEUE_ID)

    internal fun queued(transform: (Queue.Item) -> Queue.Item) {
        queueId?.let { Queue.update(applicationContext, it, transform) }
    }

    /**
     * Record [message] against the queue item and end the run — success for a queued run (see above),
     * a real failure otherwise. [resumable] tells the UI completed segments are still on disk.
     */
    internal fun fail(@StringRes message: Int, resumable: Boolean = false): Result {
        queued { it.copy(state = Queue.State.FAILED, error = message) }
        val data = workDataOf(
            FilterWorker.KEY_OUTPUT_MESSAGE to message,
            FilterWorker.KEY_RESUMABLE to resumable,
        )
        return if (queueId != null) Result.success(data) else Result.failure(data)
    }
}

/**
 * The [FilterOps] wire format, in one place.
 *
 * These fields were spelled out three times — twice being written in [JobController] and once
 * being read back in `DownloadWorker` — so adding an option reached whichever copies you remembered.
 * The read and the write now cannot disagree.
 */
internal fun FilterOps.pairs(): Array<Pair<String, Any?>> = arrayOf(
    FilterWorker.KEY_REMOVE_MUSIC to removeMusic,
    FilterWorker.KEY_CENSOR_WOMEN to censorWomen,
    FilterWorker.KEY_STRICTNESS to strictness,
    FilterWorker.KEY_BLUR_AMOUNT to blurAmount,
    FilterWorker.KEY_GRAYSCALE to grayscale,
    FilterWorker.KEY_SOLID_COLOR to solidColor,
    FilterWorker.KEY_KEEP_STEMS to keepStems,
)

/** Inverse of [pairs]; the defaults are the ones [FilterOps] itself declares. */
internal fun Data.filterOps(): FilterOps = FilterOps(
    removeMusic = getBoolean(FilterWorker.KEY_REMOVE_MUSIC, false),
    censorWomen = getBoolean(FilterWorker.KEY_CENSOR_WOMEN, false),
    strictness = getInt(FilterWorker.KEY_STRICTNESS, 50),
    blurAmount = getInt(FilterWorker.KEY_BLUR_AMOUNT, 60),
    grayscale = getBoolean(FilterWorker.KEY_GRAYSCALE, false),
    solidColor = getInt(FilterWorker.KEY_SOLID_COLOR, FilterOps.BLUR),
    keepStems = getString(FilterWorker.KEY_KEEP_STEMS) ?: "vocals",
)
