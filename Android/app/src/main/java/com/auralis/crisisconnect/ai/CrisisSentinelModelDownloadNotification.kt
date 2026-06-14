package com.auralis.crisisconnect.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import java.util.UUID

internal enum class CrisisSentinelDownloadNotificationPhase {
    Preparing,
    Downloading,
    Verifying
}

internal class CrisisSentinelModelDownloadNotification(
    private val context: Context
) {
    init {
        ensureChannel()
    }

    fun foregroundInfo(
        workerId: UUID,
        release: CrisisSentinelModelRelease,
        bytesDownloaded: Long,
        bytesTotal: Long?,
        phase: CrisisSentinelDownloadNotificationPhase
    ): ForegroundInfo {
        val notification = build(
            workerId = workerId,
            release = release,
            bytesDownloaded = bytesDownloaded,
            bytesTotal = bytesTotal,
            phase = phase
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun build(
        workerId: UUID,
        release: CrisisSentinelModelRelease,
        bytesDownloaded: Long,
        bytesTotal: Long?,
        phase: CrisisSentinelDownloadNotificationPhase
    ): Notification {
        val cancelIntent = WorkManager
            .getInstance(context.applicationContext)
            .createCancelPendingIntent(workerId)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.crisis_sentinel_notification_title))
            .setSubText(release.displayName)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_cancel,
                context.getString(R.string.crisis_sentinel_notification_cancel),
                cancelIntent
            )

        when (phase) {
            CrisisSentinelDownloadNotificationPhase.Preparing -> {
                builder
                    .setContentText(context.getString(R.string.crisis_sentinel_notification_preparing))
                    .setProgress(0, 0, true)
            }
            CrisisSentinelDownloadNotificationPhase.Verifying -> {
                builder
                    .setContentText(context.getString(R.string.crisis_sentinel_notification_verifying))
                    .setProgress(0, 0, true)
            }
            CrisisSentinelDownloadNotificationPhase.Downloading -> {
                if (bytesTotal != null && bytesTotal > 0L) {
                    val percent = ((bytesDownloaded.toDouble() / bytesTotal.toDouble()) * 100)
                        .toInt()
                        .coerceIn(0, 100)
                    builder
                        .setContentText(
                            context.getString(
                                R.string.crisis_sentinel_notification_progress_known,
                                formatBytes(bytesDownloaded),
                                formatBytes(bytesTotal)
                            )
                        )
                        .setProgress(100, percent, false)
                } else {
                    builder
                        .setContentText(
                            context.getString(
                                R.string.crisis_sentinel_notification_progress_unknown,
                                formatBytes(bytesDownloaded)
                            )
                        )
                        .setProgress(0, 0, true)
                }
            }
        }

        return builder.build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.crisis_sentinel_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.crisis_sentinel_notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatBytes(bytes: Long): String {
        return android.text.format.Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))
    }

    companion object {
        private const val CHANNEL_ID = "crisis_sentinel_model_downloads"
        private const val CONTENT_REQUEST_CODE = 4301
        const val NOTIFICATION_ID = 4302
    }
}
