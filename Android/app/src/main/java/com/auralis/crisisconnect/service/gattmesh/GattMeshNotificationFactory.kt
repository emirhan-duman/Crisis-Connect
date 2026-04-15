package com.auralis.crisisconnect.service.gattmesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R

internal object GattMeshNotificationFactory {
    fun build(
        context: Context,
        connectedCount: Int,
        notificationChannelId: String,
        requestCode: Int,
        sessionCode: String
    ): Notification {
        val meshDeviceCount = (connectedCount + 1).coerceAtLeast(1)
        val contentText = if (connectedCount > 0) {
            context.getString(
                R.string.gatt_mesh_notification_text_connected,
                meshDeviceCount
            )
        } else {
            context.getString(R.string.gatt_mesh_notification_text_waiting, meshDeviceCount)
        }
        val openGeneralChatIntent = MainActivity.createTrustedLaunchIntent(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, sessionCode)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            openGeneralChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, notificationChannelId)
            .setContentTitle(context.getString(R.string.gatt_mesh_notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun ensureChannel(
        context: Context,
        notificationChannelId: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            notificationChannelId,
            context.getString(R.string.gatt_mesh_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.gatt_mesh_channel_description)
        }
        manager.createNotificationChannel(channel)
    }
}
