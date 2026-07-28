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
        profile: MeshProfile,
        connectedCount: Int,
        requestCode: Int,
        sessionCode: String,
        // When a telsiz call is active these override the body and add inline controls so the user can
        // toggle talk / leave from the lock screen without opening the app.
        telsizText: String? = null,
        actions: List<NotificationCompat.Action> = emptyList()
    ): Notification {
        val meshDeviceCount = (connectedCount + 1).coerceAtLeast(1)
        val contentText = telsizText ?: if (connectedCount > 0) {
            context.getString(
                profile.notificationTextConnectedRes,
                meshDeviceCount
            )
        } else {
            context.getString(profile.notificationTextWaitingRes, meshDeviceCount)
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
        val builder = NotificationCompat.Builder(context, profile.notificationChannelId)
            .setContentTitle(context.getString(profile.notificationTitleRes))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
        actions.forEach { builder.addAction(it) }
        return builder.build()
    }

    fun ensureChannel(
        context: Context,
        profile: MeshProfile
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            profile.notificationChannelId,
            context.getString(profile.channelNameRes),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(profile.channelDescriptionRes)
        }
        manager.createNotificationChannel(channel)
    }
}
