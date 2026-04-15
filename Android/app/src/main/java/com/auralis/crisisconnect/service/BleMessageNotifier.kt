package com.auralis.crisisconnect.service

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.loadRecentMessages
import kotlin.math.absoluteValue

/**
 * Shows simple message notifications for BLE chats, mirroring the classic chat behavior.
 */
object BleMessageNotifier {
    private const val MESSAGE_CHANNEL_ID = "messages"
    private const val MESSAGE_NOTIFICATION_BASE_ID = 20000
    private const val MESSAGING_STYLE_HISTORY_LIMIT = 5
    private const val CHAT_LOCATION_PREFIX = "CC_LOC:"
    private const val CHAT_FILE_PREFIX = "CC_FILE:"

    @SuppressLint("MissingPermission")
    fun notifyIncoming(
        context: Context,
        sessionCode: String,
        contactName: String?,
        body: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (ActiveChatTracker.isSessionActive(sessionCode)) return
        if (!hasPostNotificationsPermission(context)) return

        val localizedContext = NotificationLocalization.localizedContext(context)
        ensureMessageChannel(localizedContext)

        val safeBody = body.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { incoming ->
                if (isChatLocationPayload(incoming)) {
                    localizedContext.getString(R.string.chat_location_preview_label)
                } else if (isChatFilePayload(incoming)) {
                    val fileName = parseChatFileName(incoming)
                    if (fileName.isNullOrBlank()) {
                        localizedContext.getString(R.string.chat_file_preview_label)
                    } else {
                        localizedContext.getString(R.string.chat_file_preview_with_name, fileName)
                    }
                } else {
                    incoming
                }
            }
            ?: localizedContext.getString(R.string.conversation_preview_text_placeholder)
        val title = resolveNotificationTitle(localizedContext, sessionCode, contactName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val launchIntent = MainActivity.createTrustedLaunchIntent(context).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, sessionCode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionCode.hashCode(),
            launchIntent,
            flags
        )
        val avatarBitmap = NotificationAvatarFormatter.resolveContactAvatarBitmap(
            context = context.applicationContext,
            sessionCode = sessionCode,
            contactName = title
        )
        val selfAvatarBitmap = NotificationAvatarFormatter.resolveLocalProfileAvatarBitmap(
            context.applicationContext
        )

        val selfPerson = Person.Builder()
            .setName(localizedContext.getString(R.string.notification_self_display_name))
            .setImportant(false)
            .apply {
                if (selfAvatarBitmap != null) {
                    setIcon(IconCompat.createWithBitmap(selfAvatarBitmap))
                }
            }
            .build()
        val contactPerson = Person.Builder()
            .setName(title)
            .setImportant(true)
            .apply {
                if (avatarBitmap != null) {
                    setIcon(IconCompat.createWithBitmap(avatarBitmap))
                }
            }
            .build()

        val historyLimit = MESSAGING_STYLE_HISTORY_LIMIT - 1
        val messagingStyle = NotificationCompat.MessagingStyle(selfPerson)
            .setConversationTitle(title)
            .setGroupConversation(false)

        if (historyLimit > 0) {
            val history = loadRecentMessages(context, sessionCode, historyLimit)
                .sortedBy { it.timestampMillis }
            history.forEach { message ->
                val text = notificationPreviewText(localizedContext, message.messageType, message.text)
                val person = if (message.isLocal) selfPerson else contactPerson
                messagingStyle.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        text,
                        message.timestampMillis,
                        person
                    )
                )
            }
        }
        messagingStyle.addMessage(
            NotificationCompat.MessagingStyle.Message(
                safeBody,
                timestamp,
                contactPerson
            )
        )

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(messagingStyle)
            .apply {
                if (avatarBitmap != null) {
                    setLargeIcon(avatarBitmap)
                }
            }
            .build()

        NotificationManagerCompat.from(context).notify(
            chatNotificationId(sessionCode),
            notification
        )
    }

    private fun notificationPreviewText(
        context: Context,
        messageType: MessageType,
        body: String?
    ): String {
        val normalizedBody = body?.trim()?.takeIf { it.isNotEmpty() }
        return when (messageType) {
            MessageType.TEXT -> {
                if (normalizedBody == null) {
                    context.getString(R.string.conversation_preview_text_placeholder)
                } else if (isChatLocationPayload(normalizedBody)) {
                    context.getString(R.string.chat_location_preview_label)
                } else if (isChatFilePayload(normalizedBody)) {
                    val fileName = parseChatFileName(normalizedBody)
                    if (fileName.isNullOrBlank()) {
                        context.getString(R.string.chat_file_preview_label)
                    } else {
                        context.getString(R.string.chat_file_preview_with_name, fileName)
                    }
                } else {
                    normalizedBody
                }
            }
            MessageType.AUDIO -> context.getString(R.string.notification_voice_message_body)
            MessageType.IMAGE -> context.getString(R.string.notification_photo_message_body)
        }
    }

    private fun isChatLocationPayload(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.contains(CHAT_LOCATION_PREFIX, ignoreCase = true)) {
            return true
        }
        return Regex("""https?://maps\.google\.com/\?q=([-0-9.]+),([-0-9.]+)""")
            .containsMatchIn(trimmed)
    }

    private fun isChatFilePayload(text: String): Boolean =
        text.trim().startsWith(CHAT_FILE_PREFIX)

    private fun parseChatFileName(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(CHAT_FILE_PREFIX)) return null
        val raw = trimmed.removePrefix(CHAT_FILE_PREFIX)
        val namePart = raw.substringBefore(',').takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            String(Base64.decode(namePart, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun ensureMessageChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = NotificationManagerCompat.from(context)
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            context.getString(R.string.chat_message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.chat_message_channel_description)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 140, 180)
            enableLights(true)
            lightColor = ContextCompat.getColor(context, R.color.purple_500)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun chatNotificationId(sessionCode: String): Int {
        val hash = stablePositiveHash(sessionCode)
        val range = Int.MAX_VALUE - MESSAGE_NOTIFICATION_BASE_ID
        val normalized = if (hash > range) hash % range else hash
        return MESSAGE_NOTIFICATION_BASE_ID + normalized
    }

    private fun stablePositiveHash(value: String): Int {
        val raw = value.hashCode()
        if (raw == Int.MIN_VALUE) {
            return 0
        }
        return raw.absoluteValue
    }

    private fun resolveNotificationTitle(
        context: Context,
        sessionCode: String,
        contactName: String?
    ): String {
        val trimmedName = contactName?.trim().orEmpty()
        if (trimmedName.isNotEmpty() && !BlePeerIdentityUtils.looksLikeBleIdentifier(trimmedName, sessionCode)) {
            return trimmedName
        }
        if (BleSessionResolver.isBleSession(sessionCode)) {
            return context.getString(R.string.rescue_unknown_user)
        }
        return trimmedName.ifEmpty { sessionCode }
    }
}
