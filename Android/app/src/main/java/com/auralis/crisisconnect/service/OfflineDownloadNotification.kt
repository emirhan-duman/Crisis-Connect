package com.auralis.crisisconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import kotlin.math.roundToInt

/**
 * Creates and updates the foreground notification for offline downloads.
 */
class OfflineDownloadNotification(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        ensureChannel()
    }

    /** Builds a running notification for the supplied [region]. */
    fun build(region: OfflineRegionEntity, pauseIntent: PendingIntentFactory, resumeIntent: PendingIntentFactory, cancelIntent: PendingIntentFactory): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.offline_notification_title, region.name))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        val progress = region.progressFraction()
        val percent = (progress * 100).roundToInt().coerceIn(0, 100)
        builder.setContentText(context.getString(R.string.offline_notification_progress, percent))
        builder.setProgress(100, percent, progress <= 0f)

        if (region.status == OfflineRegionStatus.Downloading && !region.isPaused) {
            builder.addAction(
                R.drawable.ic_pause,
                context.getString(R.string.offline_notification_pause),
                pauseIntent(region.regionId ?: return builder.build())
            )
        } else {
            builder.addAction(
                R.drawable.ic_play,
                context.getString(R.string.offline_notification_resume),
                resumeIntent(region.regionId ?: return builder.build())
            )
        }
        builder.addAction(
            R.drawable.ic_cancel,
            context.getString(R.string.offline_notification_cancel),
            cancelIntent(region.regionId ?: return builder.build())
        )

        return builder.build()
    }

    /** Cancels the active notification. */
    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.offline_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.offline_notification_channel_description)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    /** Factory for lazily creating [android.app.PendingIntent] instances. */
    fun interface PendingIntentFactory {
        operator fun invoke(regionId: Long): android.app.PendingIntent
    }

    private fun OfflineRegionEntity.progressFraction(): Float {
        if (bytesRequired <= 0L) return 0f
        return (bytesDownloaded.toDouble() / bytesRequired.toDouble()).toFloat()
    }

    companion object {
        const val CHANNEL_ID = "offline_downloads"
        const val NOTIFICATION_ID = 42
    }
}
