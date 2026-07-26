package com.haithamassoli.naqi.work

import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.haithamassoli.naqi.MainActivity
import com.haithamassoli.naqi.R
import java.util.UUID

/** Notification channel + ForegroundInfo for the filtering job, and the done notification. */
internal object JobNotifications {
    const val CHANNEL_ID = "filter_jobs"
    private const val NOTIF_ID = 1001

    /** Separate id: the ongoing FGS notification is torn down when the worker returns. */
    private const val DONE_NOTIF_ID = 1002

    /** Extras on the MainActivity intent behind the "Delete original" action. */
    const val EXTRA_DELETE_ORIGINAL = "delete_original_uri"
    const val EXTRA_DELETE_NAME = "delete_original_name"

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

    /**
     * "Saved" notification with the PRD's three actions. Posted by the worker on success, after the
     * ongoing FGS notification is gone.
     *
     * Open/Share hand [outputUri] to another app, so both carry a read grant. **Delete original**
     * does NOT delete anything here — it opens the app to confirm first. Deleting the user's only
     * copy of a video is unrecoverable, and a notification action is one stray tap; the confirmation
     * is the whole point, not ceremony. [sourceUri] is the picked input, which we may not own.
     *
     * No-ops without POST_NOTIFICATIONS (API 33+) — the job still succeeded, the user just sees the
     * result in the app instead.
     */
    fun done(context: Context, displayName: String, outputUri: String?, sourceUri: String?) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.done_notif_title))
            .setContentText(displayName)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)

        // content:// only. Pre-Q publish() returns file://, and handing that to another app throws
        // FileUriExposedException at targetSdk 36 — those devices get the notification without the
        // two hand-off actions rather than a crash (the in-app library resolves them via MediaStore).
        val output = outputUri?.takeIf { it.isNotBlank() }?.toUri()?.takeIf { it.scheme == "content" }
        if (output != null) {
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(output, MIME_MP4)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(activity(context, 0, view))
            builder.addAction(0, context.getString(R.string.action_open), activity(context, 0, view))

            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType(MIME_MP4)
                    .putExtra(Intent.EXTRA_STREAM, output)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                context.getString(R.string.action_share),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.addAction(0, context.getString(R.string.action_share), activity(context, 1, share))
        }

        if (sourceUri?.isNotBlank() == true) {
            val confirm = Intent(context, MainActivity::class.java)
                .setAction(ACTION_CONFIRM_DELETE) // distinct action so the launcher intent isn't reused
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_DELETE_ORIGINAL, sourceUri)
                .putExtra(EXTRA_DELETE_NAME, displayName)
            builder.addAction(0, context.getString(R.string.action_delete_original), activity(context, 2, confirm))
        }

        runCatching { NotificationManagerCompat.from(context).notify(DONE_NOTIF_ID, builder.build()) }
    }

    const val ACTION_CONFIRM_DELETE = "com.haithamassoli.naqi.CONFIRM_DELETE_ORIGINAL"
    private const val MIME_MP4 = "video/mp4"

    // IMMUTABLE is required on API 31+; each action needs its own request code or they collide.
    private fun activity(context: Context, requestCode: Int, intent: Intent) =
        PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
