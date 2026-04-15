package com.auralis.crisisconnect.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import java.util.Locale
import kotlin.math.absoluteValue

internal class RfcommCallNotificationDelegate(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val callChannelId: String,
    private val ongoingCallChannelId: String,
    private val callNotificationBaseId: Int,
    private val actionMute: String,
    private val actionUnmute: String,
    private val actionSpeaker: String,
    private val actionHangup: String,
    private val hasPostNotificationsPermission: () -> Boolean
) {
    fun callNotificationId(sessionCode: String): Int {
        return callNotificationBaseId + (sessionCode.hashCode().absoluteValue % 1000)
    }

    fun startRingtone(existing: Ringtone?): Ringtone? {
        if (existing?.isPlaying == true) {
            return existing
        }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val ringtoneAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val ringtone = existing ?: RingtoneManager.getRingtone(context.applicationContext, uri)?.apply {
            setAudioAttributes(ringtoneAttributes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
            }
        }
        runCatching { ringtone?.play() }
        return ringtone
    }

    fun stopRingtone(ringtone: Ringtone?) {
        runCatching { ringtone?.stop() }
    }

    fun buildIncomingCallNotification(
        name: String,
        caller: Person,
        contentIntent: PendingIntent,
        acceptPending: PendingIntent,
        rejectPending: PendingIntent,
        useCallStyle: Boolean
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, callChannelId)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(context.getString(R.string.chat_call_incoming_title, name))
            .setContentText(context.getString(R.string.chat_call_incoming_message))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            // Keep the FSI attached even on Android 14+.
            // If access is denied, the system can still downgrade this into its lockscreen/HUN fallback.
            .setFullScreenIntent(contentIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addPerson(caller)
        if (useCallStyle) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    rejectPending,
                    acceptPending
                )
            )
        } else {
            builder
                .addAction(R.drawable.ic_sos, context.getString(R.string.chat_call_reject), rejectPending)
                .addAction(R.drawable.ic_sos, context.getString(R.string.chat_call_accept), acceptPending)
        }
        return builder
    }

    fun buildOngoingCallNotification(
        sessionCode: String,
        callId: String,
        muted: Boolean,
        speakerEnabled: Boolean,
        encrypted: Boolean,
        startedAt: Long,
        connectedAt: Long?,
        remoteUserName: String,
        useCallStyle: Boolean
    ): Notification {
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val muteAction = if (muted) actionUnmute else actionMute
        val muteTitle = if (muted) {
            context.getString(R.string.chat_call_unmute)
        } else {
            context.getString(R.string.chat_call_mute)
        }
        val muteIntent = createCallActionPendingIntent(muteAction, sessionCode, callId, pendingIntentFlags, 11)
        val speakerIntent = createCallActionPendingIntent(actionSpeaker, sessionCode, callId, pendingIntentFlags, 13)
        val hangUpPendingIntent = createCallActionPendingIntent(actionHangup, sessionCode, callId, pendingIntentFlags, 14)
        val contentIntent = createCallScreenPendingIntent(
            sessionCode = sessionCode,
            callId = callId,
            flags = pendingIntentFlags,
            requestOffset = 10
        )
        val avatarBitmap = NotificationAvatarFormatter.resolveContactAvatarBitmap(
            context = context.applicationContext,
            sessionCode = sessionCode,
            contactName = remoteUserName
        )
        val callerPerson = Person.Builder()
            .setName(remoteUserName)
            .setIcon(resolveCallPersonIcon(avatarBitmap))
            .build()
        val speakerTitle = if (speakerEnabled) {
            context.getString(R.string.chat_call_speaker_off)
        } else {
            context.getString(R.string.chat_call_speaker_on)
        }
        val securityState = if (encrypted) {
            context.getString(R.string.chat_call_secure)
        } else {
            context.getString(R.string.chat_call_unsecured)
        }
        val effectiveStartAt = connectedAt ?: startedAt
        val builder = NotificationCompat.Builder(context, ongoingCallChannelId)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(context.getString(R.string.chat_call_ongoing_banner))
            .setContentText(remoteUserName)
            .setSubText(securityState)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVibrate(LongArray(0))
            .setSound(null)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(effectiveStartAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addPerson(callerPerson)
            .addAction(R.drawable.ic_sos, muteTitle, muteIntent)
            .addAction(R.drawable.ic_sos, speakerTitle, speakerIntent)
            .apply {
                if (avatarBitmap != null) {
                    setLargeIcon(avatarBitmap)
                }
            }
        requestPromotedOngoingIfEligible(builder)
        if (!useCallStyle) {
            builder
                .setColorized(true)
                .setColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
            builder.addAction(R.drawable.ic_sos, context.getString(R.string.chat_call_hangup), hangUpPendingIntent)
        }
        if (useCallStyle) {
            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    callerPerson,
                    hangUpPendingIntent
                ).setIsVideo(false)
            )
        }
        return builder.build()
    }

    fun createCallScreenPendingIntent(
        sessionCode: String,
        callId: String,
        flags: Int,
        requestOffset: Int
    ): PendingIntent {
        val launchIntent = MainActivity.createTrustedLaunchIntent(context).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, sessionCode)
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_CALL, callId)
            putExtra(MainActivity.EXTRA_WAKE_FOR_INCOMING_CALL, requestOffset == INCOMING_CALL_INTENT_OFFSET)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            sessionCode.hashCode() + requestOffset,
            launchIntent,
            flags,
            callScreenPendingIntentOptions()
        )
    }

    fun createCallActionPendingIntent(
        action: String,
        sessionCode: String,
        callId: String,
        flags: Int,
        requestOffset: Int
    ): PendingIntent {
        val intent = Intent(context, serviceClass).apply {
            this.action = action
            putExtra(RfcommForegroundService.EXTRA_SESSION_CODE, sessionCode)
            putExtra(RfcommForegroundService.EXTRA_CALL_ID, callId)
        }
        return PendingIntent.getService(
            context,
            sessionCode.hashCode() + requestOffset,
            intent,
            flags
        )
    }

    fun formatElapsed(elapsedSeconds: Long): String {
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun callScreenPendingIntentOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return null
        }
        return ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
            .toBundle()
    }

    private fun requestPromotedOngoingIfEligible(builder: NotificationCompat.Builder) {
        if (!shouldRequestPromotedOngoing()) {
            return
        }
        val appliedByCompatMethod = runCatching {
            val method = builder.javaClass.getMethod(
                "setRequestPromotedOngoing",
                java.lang.Boolean.TYPE
            )
            method.invoke(builder, true)
            true
        }.getOrDefault(false)
        if (!appliedByCompatMethod) {
            builder.addExtras(Bundle().apply {
                putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING_KEY, true)
            })
        }
    }

    private fun shouldRequestPromotedOngoing(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL) {
            return false
        }
        if (!hasPostNotificationsPermission()) {
            return false
        }
        if (!hasPromotedNotificationsPermission()) {
            return false
        }
        return canPostPromotedNotificationsCompat()
    }

    private fun hasPromotedNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL) {
            return false
        }
        return ContextCompat.checkSelfPermission(
            context,
            POST_PROMOTED_NOTIFICATIONS_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canPostPromotedNotificationsCompat(): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_16_API_LEVEL) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching {
            val method = manager.javaClass.getMethod("canPostPromotedNotifications")
            (method.invoke(manager) as? Boolean) == true
        }.getOrDefault(false)
    }

    private fun resolveCallPersonIcon(avatarBitmap: Bitmap?): IconCompat {
        return if (avatarBitmap != null) {
            IconCompat.createWithBitmap(avatarBitmap)
        } else {
            IconCompat.createWithResource(context, R.drawable.ic_splash_plain)
        }
    }

    private companion object {
        // Offset 0 is reserved by RfcommCallUiDelegate for incoming-call launch intents.
        private const val INCOMING_CALL_INTENT_OFFSET = 0
        private const val ANDROID_16_API_LEVEL = 36
        private const val POST_PROMOTED_NOTIFICATIONS_PERMISSION = "android.permission.POST_PROMOTED_NOTIFICATIONS"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING_KEY = "android.requestPromotedOngoing"
    }
}
