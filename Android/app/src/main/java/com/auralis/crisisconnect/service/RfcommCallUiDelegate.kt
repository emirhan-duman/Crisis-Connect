package com.auralis.crisisconnect.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.R
import java.util.concurrent.atomic.AtomicReference

internal class RfcommCallUiDelegate(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val handler: Handler,
    private val callStyleForegroundSession: AtomicReference<String?>,
    private val callEventChannelId: String,
    private val actionAcceptCall: String,
    private val actionRejectCall: String,
    private val extraSessionCodeKey: String,
    private val extraCallIdKey: String,
    private val extraCallFlagKey: String,
    private val callAcceptedFlag: String,
    private val hasPostNotificationsPermission: () -> Boolean,
    private val canUseForegroundCallStyle: () -> Boolean,
    private val updateCallStyleForeground: (String, Notification) -> Boolean,
    private val callNotificationId: (String) -> Int,
    private val callScreenPendingIntent: (String, String, Int, Int) -> PendingIntent,
    private val buildIncomingCallNotification: (
        String,
        Person,
        PendingIntent,
        PendingIntent,
        PendingIntent,
        Boolean
    ) -> Notification,
    private val buildOngoingCallNotification: (CallSession, Boolean) -> Notification,
    private val resolveCallerName: (CallSession) -> String,
    private val formatElapsed: (Long) -> String,
    private val startRingtone: (CallSession) -> Unit,
    private val notifyNotification: (Int, Notification) -> Unit,
    private val cancelNotification: (Int) -> Unit,
    private val restoreBaseNotification: () -> Unit
) {
    fun showIncomingCallNotification(session: CallSession) {
        val sessionCode = session.sessionCode
        val name = resolveCallerName(session)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val contentIntent = callScreenPendingIntent(sessionCode, session.callId, pendingIntentFlags, 0)
        val acceptIntent = Intent(context, serviceClass).apply {
            action = actionAcceptCall
            putExtra(extraSessionCodeKey, sessionCode)
            putExtra(extraCallIdKey, session.callId)
            putExtra(extraCallFlagKey, callAcceptedFlag)
        }
        val acceptPending = PendingIntent.getService(
            context,
            sessionCode.hashCode() + 1,
            acceptIntent,
            pendingIntentFlags
        )
        val rejectIntent = Intent(context, serviceClass).apply {
            action = actionRejectCall
            putExtra(extraSessionCodeKey, sessionCode)
            putExtra(extraCallIdKey, session.callId)
        }
        val rejectPending = PendingIntent.getService(
            context,
            sessionCode.hashCode() + 2,
            rejectIntent,
            pendingIntentFlags
        )
        val avatarBitmap = NotificationAvatarFormatter.resolveContactAvatarBitmap(
            context = context.applicationContext,
            sessionCode = sessionCode,
            contactName = name
        )
        val caller = Person.Builder()
            .setName(name)
            .setImportant(true)
            .setIcon(
                avatarBitmap?.let { IconCompat.createWithBitmap(it) }
                    ?: IconCompat.createWithResource(context, R.drawable.ic_splash_plain)
            )
            .build()
        val useCallStyle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val notification = buildIncomingCallNotification(
            name,
            caller,
            contentIntent,
            acceptPending,
            rejectPending,
            useCallStyle
        )

        if (hasPostNotificationsPermission()) {
            // Post incoming calls as a dedicated high-priority notification.
            // Replacing the foreground service notification here weakens OEM/system incoming-call affordances.
            notifyNotification(callNotificationId(sessionCode), notification)
        } else {
            Log.w(TAG, "Skipping incoming call notification: POST_NOTIFICATIONS not granted")
        }
        // Keep the app ringtone independent from notification delivery.
        // Incoming calls need a looping ringtone even on devices/OEMs that only chirp or stay silent for the posted notification.
        startRingtone(session)
    }

    fun notifyCallEnded(
        session: CallSession,
        direction: RfcommForegroundService.CallDirection,
        result: RfcommForegroundService.CallResult,
        durationMillis: Long?
    ) {
        if (!hasPostNotificationsPermission()) {
            return
        }
        val title = when (result) {
            RfcommForegroundService.CallResult.ANSWERED -> {
                if (direction == RfcommForegroundService.CallDirection.OUTGOING) {
                    context.getString(R.string.chat_call_event_outgoing_answered)
                } else {
                    context.getString(R.string.chat_call_event_incoming_answered)
                }
            }

            RfcommForegroundService.CallResult.MISSED -> context.getString(R.string.chat_call_event_missed)
            RfcommForegroundService.CallResult.REJECTED -> context.getString(R.string.chat_call_event_rejected)
            RfcommForegroundService.CallResult.CANCELED -> context.getString(R.string.chat_call_event_canceled)
        }
        val remoteName = resolveCallerName(session)
        val durationText = durationMillis
            ?.takeIf { it > 0L }
            ?.let { duration -> formatElapsed((duration / 1000L).coerceAtLeast(0L)) }
        val contentText = listOfNotNull(remoteName, durationText).joinToString(" • ")
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val contentIntent = callScreenPendingIntent(
            session.sessionCode,
            session.callId,
            pendingIntentFlags,
            20
        )
        val avatarBitmap = NotificationAvatarFormatter.resolveContactAvatarBitmap(
            context = context.applicationContext,
            sessionCode = session.sessionCode,
            contactName = remoteName
        )
        val caller = Person.Builder()
            .setName(remoteName)
            .setIcon(
                avatarBitmap?.let { IconCompat.createWithBitmap(it) }
                    ?: IconCompat.createWithResource(context, R.drawable.ic_splash_plain)
            )
            .build()
        val notification = NotificationCompat.Builder(context, callEventChannelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(contentText)
            )
            .addPerson(caller)
            .apply {
                if (avatarBitmap != null) {
                    setLargeIcon(avatarBitmap)
                }
            }
            .build()
        notifyNotification(callNotificationId(session.sessionCode), notification)
    }

    fun clearCallStyleForeground(sessionCode: String) {
        val shouldRestore = callStyleForegroundSession.compareAndSet(sessionCode, null)
        if (shouldRestore) {
            handler.post { restoreBaseNotification() }
        }
    }

    fun cancelCallNotification(sessionCode: String) {
        clearCallStyleForeground(sessionCode)
        cancelPostedCallNotification(sessionCode)
    }

    fun cancelPostedCallNotification(sessionCode: String) {
        if (hasPostNotificationsPermission()) {
            try {
                cancelNotification(callNotificationId(sessionCode))
            } catch (securityException: SecurityException) {
                Log.w(TAG, "Failed to cancel call notification", securityException)
            }
        }
    }

    fun startOngoingCallNotification(session: CallSession) {
        if (session.state.value != CallState.InCall) {
            return
        }
        postOngoingCallNotification(session)
    }

    fun postOngoingCallNotification(session: CallSession) {
        if (session.state.value != CallState.InCall) {
            return
        }
        val useCallStyle = canUseForegroundCallStyle()
        val notification = buildOngoingCallNotification(session, useCallStyle)
        if (useCallStyle) {
            val appliedToForeground = updateCallStyleForeground(session.sessionCode, notification)
            if (appliedToForeground) {
                cancelPostedCallNotification(session.sessionCode)
            }
        } else if (hasPostNotificationsPermission()) {
            notifyNotification(callNotificationId(session.sessionCode), notification)
        }
    }

    fun stopOngoingCallNotification(session: CallSession) {
        clearCallStyleForeground(session.sessionCode)
    }

    private companion object {
        private const val TAG = "RfcommService"
    }
}
