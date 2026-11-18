package com.animo.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.animo.app.R
import com.animo.app.di.ServiceLocator

/**
 * Runs one download as a foreground job, so leaving the app (or locking the phone) does not stop it.
 * The progress notification is the only UI the system guarantees while the app is in the background.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        if (!ServiceLocator.isInstalled()) ServiceLocator.install(applicationContext)
        val center = ServiceLocator.downloads
        val title = center.entries.value.firstOrNull { it.id == id }?.let(::titleOf)
            ?: applicationContext.getString(R.string.ui_downloads_title)

        runCatching { setForeground(foregroundInfo(id, title, 0f)) }
        var lastShown = -1
        val ok = center.run(id) { entry ->
            val percent = (entry.progress * 100).toInt()
            if (percent != lastShown) {
                lastShown = percent
                notificationManager().notify(notificationId(id), notification(titleOf(entry), entry.progress))
            }
        }
        val finished = center.entries.value.firstOrNull { it.id == id }
        if (finished != null) {
            notificationManager().notify(notificationId(id) + 1, doneNotification(finished, ok))
        }
        return if (ok) Result.success() else Result.failure()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_ID).orEmpty(), applicationContext.getString(R.string.ui_downloads_title), 0f)

    private fun foregroundInfo(id: String, title: String, progress: Float): ForegroundInfo {
        val notification = notification(title, progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(id), notification)
        }
    }

    private fun notification(title: String, progress: Float) =
        NotificationCompat.Builder(applicationContext, ensureChannel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(applicationContext.getString(R.string.ui_downloads_running))
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun doneNotification(entry: DownloadEntry, ok: Boolean) =
        NotificationCompat.Builder(applicationContext, ensureChannel())
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(titleOf(entry))
            .setContentText(
                if (ok) {
                    applicationContext.getString(R.string.ui_downloads_done)
                } else {
                    entry.error ?: applicationContext.getString(R.string.ui_downloads_failed)
                },
            )
            .setAutoCancel(true)
            .build()

    private fun titleOf(entry: DownloadEntry) = "${entry.animeTitle} · E${entry.episodeNumber}"

    private fun notificationManager() =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(R.string.ui_downloads_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return CHANNEL
    }

    private fun notificationId(id: String) = NOTIFICATION_BASE + (id.hashCode() and 0xFFFF) * 2

    companion object {
        const val KEY_ID = "download_id"
        const val TAG = "animo-download"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_BASE = 40_000
    }
}
