package com.haithamassoli.naqi.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.haithamassoli.naqi.R
import java.util.UUID

/** Notification channel + ForegroundInfo for the filtering job. */
internal object JobNotifications {
    const val CHANNEL_ID = "filter_jobs"
    private const val NOTIF_ID = 1001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.job_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.job_channel_desc) },
            )
        }
    }

    /** @param progress 0..100, or negative for indeterminate. */
    fun foregroundInfo(context: Context, workId: UUID, stage: String, progress: Int): ForegroundInfo {
        ensureChannel(context)
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.job_notif_title))
            .setContentText(stage)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancel,
            )
        if (progress in 0..100) builder.setProgress(100, progress, false) else builder.setProgress(0, 0, true)

        val notification = builder.build()
        // foregroundServiceType must be a subset of what the manifest declares on SystemForegroundService.
        return when {
            Build.VERSION.SDK_INT >= 35 ->
                ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 34 ->
                ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(NOTIF_ID, notification)
        }
    }
}
