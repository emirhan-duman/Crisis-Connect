package com.auralis.crisisconnect.screens.Chat

import android.content.Context
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

private const val MILLIS_PER_DAY = 86_400_000L

internal fun formatElapsedDuration(millis: Long): String {
    val safeMillis = max(millis, 0L)
    val totalSeconds = safeMillis / 1_000L
    val hours = (totalSeconds / 3_600L).toInt()
    val minutes = ((totalSeconds % 3_600L) / 60L).toInt()
    val seconds = (totalSeconds % 60L).toInt()
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

internal fun formatCallDuration(
    context: Context,
    durationMillis: Long
): String {
    val totalSeconds = (durationMillis / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = (totalSeconds % 60).toInt()
    return if (minutes > 0) {
        context.getString(
            R.string.chat_call_duration_minutes_seconds,
            minutes,
            seconds
        )
    } else {
        context.getString(R.string.chat_call_duration_seconds, seconds)
    }
}

internal fun isSameLocalDay(
    previousTimestamp: Long,
    currentTimestamp: Long,
    timeZone: TimeZone
): Boolean {
    return localDayKey(previousTimestamp, timeZone) == localDayKey(currentTimestamp, timeZone)
}

private fun localDayKey(timestampMillis: Long, timeZone: TimeZone): Long {
    val offsetMillis = timeZone.getOffset(timestampMillis).toLong()
    return Math.floorDiv(timestampMillis + offsetMillis, MILLIS_PER_DAY)
}

internal fun formatLocationCoordinate(value: Double): String =
    String.format(Locale.US, "%.6f", value)

internal fun formatMessageTimestampLabel(
    formatter: SimpleDateFormat,
    displayTimestampMillis: Long,
    originalTimestampMillis: Long?,
    isLocal: Boolean
): String {
    val deliveredLabel = formatter.format(Date(displayTimestampMillis))
    if (isLocal) {
        return deliveredLabel
    }
    val originalMillis = originalTimestampMillis
        ?.takeIf { it > 0L && it < displayTimestampMillis }
        ?: return deliveredLabel
    val originalLabel = formatter.format(Date(originalMillis))
    return if (originalLabel == deliveredLabel) {
        deliveredLabel
    } else {
        "$originalLabel \u279E $deliveredLabel"
    }
}

internal fun replyPreviewText(
    message: ChatMessage,
    voiceLabel: String,
    imageLabel: String,
    fileLabel: String,
    locationLabel: String,
    unknownLabel: String
): String {
    val textPortion = message.text.takeIf { it.isNotBlank() }
    val typeLabel = when (message.messageType) {
        MessageType.TEXT -> when {
            parseSharedLocationPayload(message.text) != null -> locationLabel
            parseSharedFilePayload(message.text) != null -> fileLabel
            else -> textPortion
        }
        MessageType.AUDIO -> textPortion ?: voiceLabel
        MessageType.IMAGE -> textPortion ?: imageLabel
    }
    return typeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: unknownLabel
}

internal fun buildReplyFormattedMessage(
    context: Context,
    body: String,
    replyTo: ChatMessage?,
    replyAuthorLabel: String?
): String {
    val trimmedBody = body.trim()
    val target = replyTo ?: return trimmedBody
    val targetUuid = target.messageUuid.takeIf { it.isNotBlank() }
    val preview = when (target.messageType) {
        MessageType.TEXT -> when {
            parseSharedLocationPayload(target.text) != null ->
                context.getString(R.string.chat_location_preview_label)
            parseSharedFilePayload(target.text) != null ->
                context.getString(R.string.chat_file_preview_label)
            else -> target.text
        }
        MessageType.AUDIO -> context.getString(R.string.chat_reply_audio_placeholder)
        MessageType.IMAGE -> context.getString(R.string.chat_reply_image_placeholder)
    }
    val safePreview = preview?.trim()?.takeIf { it.isNotEmpty() }
        ?: context.getString(R.string.chat_reply_unknown_placeholder)
    val heading = replyAuthorLabel?.takeIf { it.isNotBlank() }
        ?.let { "${it.trim()}|$safePreview" }
        ?: safePreview
    val headerWithMetadata = buildString {
        append("↪")
        targetUuid?.let {
            append("[")
            append(it)
            append("]")
        }
        append(' ')
        append(heading)
    }
    return "$headerWithMetadata\n$trimmedBody"
}
