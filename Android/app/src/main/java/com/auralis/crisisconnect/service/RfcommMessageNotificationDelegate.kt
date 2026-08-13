package com.auralis.crisisconnect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.core.chat.stripReplyMetadata
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.loadRecentMessages
import java.util.UUID
import kotlin.math.absoluteValue

internal class RfcommMessageNotificationDelegate(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val replyAction: String,
    private val extraSessionCodeKey: String,
    private val hasPostNotificationsPermission: () -> Boolean,
    private val notifyNotification: (Int, Notification) -> Unit,
    private val sendMessage: (String, String, String, (Boolean) -> Unit) -> Unit
) {
    fun maybeNotifyIncomingMessage(
        sessionCode: String,
        messageType: MessageType,
        body: String?
    ) {
        if (body?.trimStart()?.startsWith(AUTHORITY_MLS_PREFIX) == true) return
        if (ActiveChatTracker.isSessionActive(sessionCode)) {
            return
        }
        if (!hasPostNotificationsPermission()) {
            return
        }
        val localizedContext = NotificationLocalization.localizedContext(context)
        ensureMessageChannel(localizedContext)
        val timestamp = System.currentTimeMillis()
        val contactName = getContact(context.applicationContext, sessionCode)?.name ?: sessionCode
        val messageText = notificationPreviewText(localizedContext, messageType, body)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
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
            contactName = contactName
        )
        val selfAvatarBitmap = NotificationAvatarFormatter.resolveLocalProfileAvatarBitmap(
            context.applicationContext
        )
        val contactPerson = buildContactPerson(contactName, avatarBitmap)
        val selfPerson = buildSelfPerson(localizedContext, selfAvatarBitmap)
        val messagingStyle = NotificationCompat.MessagingStyle(selfPerson)
            .setConversationTitle(contactName)
            .setGroupConversation(false)

        val historyLimit = MESSAGING_STYLE_HISTORY_LIMIT - 1
        if (historyLimit > 0) {
            val history = loadRecentMessages(context.applicationContext, sessionCode, historyLimit)
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
                messageText,
                timestamp,
                contactPerson
            )
        )

        val notificationBuilder = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setWhen(timestamp)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(ContextCompat.getColor(context, R.color.purple_500))
            .addPerson(contactPerson)
            .setStyle(messagingStyle)
            .addAction(buildDirectReplyAction(localizedContext, sessionCode, contactName))

        if (avatarBitmap != null) {
            notificationBuilder.setLargeIcon(avatarBitmap)
        }

        notifyNotification(chatNotificationId(sessionCode), notificationBuilder.build())
    }

    fun handleDirectReplyIntent(intent: Intent) {
        val sessionCode = intent.getStringExtra(extraSessionCodeKey)?.takeIf { it.isNotBlank() } ?: return
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val messageUuid = UUID.randomUUID().toString()
        sendMessage(sessionCode, messageUuid, replyText) { success ->
            if (success && hasPostNotificationsPermission()) {
                NotificationManagerCompat.from(context).cancel(chatNotificationId(sessionCode))
            }
        }
    }

    private fun ensureMessageChannel(localizedContext: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = NotificationManagerCompat.from(context)
            val channel = NotificationChannel(
                MESSAGE_CHANNEL_ID,
                localizedContext.getString(R.string.chat_message_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = localizedContext.getString(R.string.chat_message_channel_description)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 140, 180)
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.purple_500)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }
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

    private fun notificationPreviewText(
        localizedContext: Context,
        messageType: MessageType,
        body: String?
    ): String {
        val normalizedBody = body?.trim()?.takeIf { it.isNotEmpty() }
        if (messageType == MessageType.TEXT) {
            val replyMetadata = normalizedBody?.let(::parseReplyMetadata)
            val replyBody = replyMetadata?.body?.takeIf { it.isNotEmpty() }
            if (replyBody != null) {
                return localizedContext.getString(R.string.notification_replied_to_you, replyBody)
            }
        }

        val visibleBody = if (messageType == MessageType.TEXT) {
            stripReplyMetadata(normalizedBody)
        } else {
            normalizedBody
        }
        return when (messageType) {
            MessageType.TEXT -> {
                val text = visibleBody
                    ?: return localizedContext.getString(R.string.conversation_preview_text_placeholder)
                if (text.contains(CHAT_LOCATION_PREFIX, ignoreCase = true)) {
                    localizedContext.getString(R.string.chat_location_preview_label)
                } else {
                    val fileName = parseChatFileName(text)
                    if (fileName != null) {
                        localizedContext.getString(R.string.chat_file_preview_with_name, fileName)
                    } else {
                        text
                    }
                }
            }

            MessageType.AUDIO -> localizedContext.getString(R.string.notification_voice_message_body)
            MessageType.IMAGE -> localizedContext.getString(R.string.notification_photo_message_body)
            MessageType.SOS_ALERT ->
                visibleBody ?: localizedContext.getString(R.string.conversation_preview_sos)
        }
    }

    private fun parseChatFileName(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(CHAT_FILE_PREFIX)) {
            return null
        }
        val nameToken = trimmed
            .removePrefix(CHAT_FILE_PREFIX)
            .substringBefore(',')
            .takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            String(Base64.decode(nameToken, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
                .trim()
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun buildDirectReplyAction(
        localizedContext: Context,
        sessionCode: String,
        contactName: String
    ): NotificationCompat.Action {
        val replyLabel = if (contactName.isBlank()) {
            localizedContext.getString(R.string.notification_reply_action)
        } else {
            localizedContext.getString(R.string.notification_reply_placeholder, contactName)
        }
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(replyLabel)
            .build()
        val pendingIntent = createReplyPendingIntent(sessionCode)
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            localizedContext.getString(R.string.notification_reply_action),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun createReplyPendingIntent(sessionCode: String): PendingIntent {
        val intent = Intent(context, serviceClass).apply {
            action = replyAction
            putExtra(extraSessionCodeKey, sessionCode)
        }
        return PendingIntent.getService(
            context,
            replyRequestCode(sessionCode),
            intent,
            replyPendingIntentFlags()
        )
    }

    private fun replyPendingIntentFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return flags
    }

    private fun replyRequestCode(sessionCode: String): Int {
        val hash = stablePositiveHash(sessionCode)
        val range = Int.MAX_VALUE - REPLY_PENDING_INTENT_BASE
        val normalized = if (hash > range) hash % range else hash
        return REPLY_PENDING_INTENT_BASE + normalized
    }

    private fun buildSelfPerson(localizedContext: Context, avatar: Bitmap?): Person =
        Person.Builder()
            .setName(localizedContext.getString(R.string.notification_self_display_name))
            .setImportant(false)
            .apply {
                if (avatar != null) {
                    setIcon(IconCompat.createWithBitmap(avatar))
                }
            }
            .build()

    private fun buildContactPerson(contactName: String, avatar: Bitmap?): Person {
        val builder = Person.Builder()
            .setName(contactName)
            .setImportant(true)
        if (avatar != null) {
            builder.setIcon(IconCompat.createWithBitmap(avatar))
        }
        return builder.build()
    }

    private companion object {
        private const val KEY_TEXT_REPLY = "KEY_TEXT_REPLY"
        private const val MESSAGE_CHANNEL_ID = "messages"
        private const val MESSAGE_NOTIFICATION_BASE_ID = 20000
        private const val REPLY_PENDING_INTENT_BASE = 22000
        private const val MESSAGING_STYLE_HISTORY_LIMIT = 5
        private const val CHAT_LOCATION_PREFIX = "CC_LOC:"
        private const val CHAT_FILE_PREFIX = "CC_FILE:"
        private const val AUTHORITY_MLS_PREFIX = "CC_AMLS2:"
    }
}
