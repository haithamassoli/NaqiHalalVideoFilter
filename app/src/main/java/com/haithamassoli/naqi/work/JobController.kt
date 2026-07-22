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

/** Thin WorkManager facade for the (M0 no-op) filtering job. */
object JobController {

    fun start(context: Context, ops: FilterOps, inputUri: String?): UUID {
        val request = OneTimeWorkRequestBuilder<FilterWorker>()
            .setInputData(
                workDataOf(
                    FilterWorker.KEY_REMOVE_MUSIC to ops.removeMusic,
                    FilterWorker.KEY_CENSOR_WOMEN to ops.censorWomen,
                    FilterWorker.KEY_INPUT_URI to inputUri,
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
