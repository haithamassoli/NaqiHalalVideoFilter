package com.haithamassoli.naqi.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.haithamassoli.naqi.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * M0 skeleton: a no-op two-pass job that promotes to a foreground service, reports staged
 * progress, and honors cancellation. The real analysis (pass 1) and render/encode (pass 2)
 * pipelines land in M1/M2. Nothing is written to disk yet, so cancellation needs no cleanup.
 */
class FilterWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val removeMusic = inputData.getBoolean(KEY_REMOVE_MUSIC, false)
        val censorWomen = inputData.getBoolean(KEY_CENSOR_WOMEN, false)
        if (!removeMusic && !censorWomen) return Result.failure()

        // Promote immediately so the ongoing notification appears at job start.
        setForeground(foregroundInfo(applicationContext.getString(R.string.stage_analyzing), 0))

        // Pass 1 — analysis (simulated). Pass 2 — render/encode (simulated).
        simulateStage(R.string.stage_analyzing, from = 0, to = 50)
        simulateStage(R.string.stage_rendering, from = 50, to = 100)
        return Result.success()
    }

    private suspend fun simulateStage(stageRes: Int, from: Int, to: Int) {
        val stage = applicationContext.getString(stageRes)
        var p = from
        while (p <= to) {
            coroutineContext.ensureActive() // cooperative cancel: throws when the work is stopped
            setProgress(workDataOf(KEY_PROGRESS to p, KEY_STAGE to stage))
            setForeground(foregroundInfo(stage, p))
            delay(90)
            p += 2
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.stage_analyzing), 0)

    private fun foregroundInfo(stage: String, progress: Int) =
        JobNotifications.foregroundInfo(applicationContext, id, stage, progress)

    companion object {
        const val KEY_REMOVE_MUSIC = "remove_music"
        const val KEY_CENSOR_WOMEN = "censor_women"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"
        const val UNIQUE_WORK = "naqi_filter_job"
    }
}
