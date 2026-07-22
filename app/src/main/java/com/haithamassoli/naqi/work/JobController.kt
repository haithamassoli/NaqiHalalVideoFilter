package com.haithamassoli.naqi.work

import android.content.Context
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

    /** @param forceIntervalsMs debug-only "startMs-endMs,startMs-endMs" forced censor spans (E2E hook). */
    fun start(context: Context, ops: FilterOps, inputUri: String?, forceIntervalsMs: String? = null): UUID {
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
                    FilterWorker.KEY_FORCE_INTERVALS to forceIntervalsMs,
                ),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FilterWorker.UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    fun observe(context: Context): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(FilterWorker.UNIQUE_WORK)

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FilterWorker.UNIQUE_WORK)
    }
}
