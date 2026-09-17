package fi.ak.smartvoice.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import fi.ak.smartvoice.MainActivity
import fi.ak.smartvoice.R
import java.util.UUID

/**
 * The download notifications: one ongoing with live progress and a Cancel action, then a
 * dismissible "ready" or "failed" follow-up.
 *
 * It has its own channel, separate from the pill's, so a user can silence model downloads without
 * losing the pill's ongoing notification — or the other way round.
 */
class DownloadNotifications(private val context: Context) {

    fun foregroundInfo(workId: UUID, label: String, progress: DownloadProgress?): ForegroundInfo {
        ensureChannel()
        return ForegroundInfo(
            PROGRESS_ID,
            progressNotification(workId, label, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun notifyComplete(label: String) {
        ensureChannel()
        post(
            DONE_ID,
            base("$label ready")
                .setContentText("The model finished downloading.")
                .build(),
        )
    }

    fun notifyFailed(label: String, reason: String) {
        ensureChannel()
        post(
            FAILED_ID,
            base("$label failed to download")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .build(),
        )
    }

    private fun progressNotification(workId: UUID, label: String, progress: DownloadProgress?): Notification {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return base("Downloading $label")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(PROGRESS_MAX, percent(progress), progress == null)
            .setContentText(
                if (progress == null) {
                    "Starting…"
                } else {
                    "${progress.downloadedBytes / 1_000_000} / ${progress.totalBytes / 1_000_000} MB"
                },
            )
            .addAction(NotificationCompat.Action(0, "Cancel", cancel))
            .build()
    }

    private fun base(title: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())

    private fun percent(progress: DownloadProgress?): Int =
        ((progress?.overall ?: 0f) * PROGRESS_MAX).toInt().coerceIn(0, PROGRESS_MAX)

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Progress while a voice model downloads in the background"
                setShowBadge(false)
            },
        )
    }

    /**
     * A denied notification permission is not an error: the download runs, the user just cannot see
     * it from outside the app. The progress notification goes through WorkManager regardless.
     */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!canNotify()) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "model-downloads"
        const val PROGRESS_ID = 4201
        const val DONE_ID = 4202
        const val FAILED_ID = 4203
        const val PROGRESS_MAX = 100
    }
}
