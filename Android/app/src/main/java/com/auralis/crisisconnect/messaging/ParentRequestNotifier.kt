package com.auralis.crisisconnect.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.security.ChildProfileManager
import com.auralis.crisisconnect.service.NotificationLocalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/**
 * Notifications for the child-profile parent approval flow:
 *  - on the ASKED device: "X wants to add you as their parent" with Accept/Decline actions
 *    (handled by [ParentRequestResponseReceiver]);
 *  - on the CHILD device: the outcome of a sent request.
 */
object ParentRequestNotifier {

    private const val MESSAGE_CHANNEL_ID = "messages"
    private const val REQUEST_NOTIFICATION_BASE_ID = 27000
    private const val RESPONSE_NOTIFICATION_BASE_ID = 28000

    @SuppressLint("MissingPermission")
    fun notifyParentRequest(context: Context, contact: Contact) {
        if (!hasPostNotificationsPermission(context)) return
        val localized = NotificationLocalization.localizedContext(context)
        ensureMessageChannel(localized)

        val notificationId = requestNotificationId(contact.sessionCode)
        val accept = actionPendingIntent(
            context,
            ParentRequestResponseReceiver.ACTION_ACCEPT,
            contact.sessionCode,
            notificationId
        )
        val decline = actionPendingIntent(
            context,
            ParentRequestResponseReceiver.ACTION_DECLINE,
            contact.sessionCode,
            notificationId
        )

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.child_profile_parent_request_notification_title))
            .setContentText(
                localized.getString(
                    R.string.child_profile_parent_request_notification_body,
                    displayName(localized, contact)
                )
            )
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(context, contact.sessionCode))
            .addAction(
                0,
                localized.getString(R.string.child_profile_parent_request_accept),
                accept
            )
            .addAction(
                0,
                localized.getString(R.string.child_profile_parent_request_decline),
                decline
            )
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    @SuppressLint("MissingPermission")
    fun notifyParentResponse(context: Context, contact: Contact, accepted: Boolean) {
        if (!hasPostNotificationsPermission(context)) return
        val localized = NotificationLocalization.localizedContext(context)
        ensureMessageChannel(localized)

        val body = localized.getString(
            if (accepted) {
                R.string.child_profile_parent_accepted_notification
            } else {
                R.string.child_profile_parent_declined_notification
            },
            displayName(localized, contact)
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.child_profile_title))
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openChatIntent(context, contact.sessionCode))
            .build()

        NotificationManagerCompat.from(context).notify(
            RESPONSE_NOTIFICATION_BASE_ID + stablePositiveHash(contact.sessionCode) % 1000,
            notification
        )
    }

    private fun displayName(context: Context, contact: Contact): String {
        return contact.name.trim().ifEmpty {
            context.getString(R.string.internet_message_unknown_sender)
        }
    }

    private fun openChatIntent(context: Context, sessionCode: String): PendingIntent {
        val launchIntent = MainActivity.createTrustedLaunchIntent(context).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, sessionCode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            sessionCode.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        sessionCode: String,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, ParentRequestResponseReceiver::class.java).apply {
            this.action = action
            putExtra(ParentRequestResponseReceiver.EXTRA_SESSION_CODE, sessionCode)
            putExtra(ParentRequestResponseReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context,
            (action + sessionCode).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestNotificationId(sessionCode: String): Int {
        return REQUEST_NOTIFICATION_BASE_ID + stablePositiveHash(sessionCode) % 1000
    }

    private fun stablePositiveHash(value: String): Int {
        val raw = value.hashCode()
        return if (raw == Int.MIN_VALUE) 0 else raw.absoluteValue
    }

    private fun ensureMessageChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            context.getString(R.string.chat_message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.chat_message_channel_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Handles the Accept/Decline actions of the parent-request notification: sends the E2E
 * [InternetChatTransport.PARENT_RESPONSE_TEMPLATE] answer back to the child device and
 * dismisses the notification.
 */
class ParentRequestResponseReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ACTION_ACCEPT && action != ACTION_DECLINE) return
        val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val accepted = action == ACTION_ACCEPT

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                getContact(appContext, sessionCode)?.let { contact ->
                    InternetChatTransport(appContext).sendParentResponse(contact, accepted)
                }
                // Accepting makes us this contact's parent → it shows in our "my children" list.
                if (accepted) {
                    ChildProfileManager.addChild(appContext, sessionCode)
                }
                if (notificationId != -1) {
                    NotificationManagerCompat.from(appContext).cancel(notificationId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.auralis.crisisconnect.action.PARENT_REQUEST_ACCEPT"
        const val ACTION_DECLINE = "com.auralis.crisisconnect.action.PARENT_REQUEST_DECLINE"
        const val EXTRA_SESSION_CODE = "session_code"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
