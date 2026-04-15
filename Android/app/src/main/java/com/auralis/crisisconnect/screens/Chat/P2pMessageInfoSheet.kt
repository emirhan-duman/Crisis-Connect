package com.auralis.crisisconnect.screens.Chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.data.BleChatMessage
import com.auralis.crisisconnect.data.BleMessageStatus
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MessageType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun P2pChatMessageInfoSheet(
    message: ChatMessage,
    conversationDisplayName: String,
    onDismiss: () -> Unit
) {
    val normalizedStatus = when {
        message.isRead -> MessageDeliveryStatus.READ
        message.deliveryStatus != null -> message.deliveryStatus
        else -> MessageDeliveryStatus.DELIVERED
    }
    val statusLabelRes = when (normalizedStatus) {
        MessageDeliveryStatus.QUEUED -> R.string.chat_message_status_pending
        MessageDeliveryStatus.SENDING -> R.string.chat_message_status_sending
        MessageDeliveryStatus.SENT -> R.string.chat_message_status_sent
        MessageDeliveryStatus.DELIVERED -> R.string.chat_message_status_delivered
        MessageDeliveryStatus.READ -> R.string.chat_message_status_read
        MessageDeliveryStatus.FAILED -> R.string.chat_message_status_failed
    }
    val statusTint = when (normalizedStatus) {
        MessageDeliveryStatus.READ -> MaterialTheme.colorScheme.primary
        MessageDeliveryStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusContainer = when (normalizedStatus) {
        MessageDeliveryStatus.READ -> MaterialTheme.colorScheme.primaryContainer
        MessageDeliveryStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val body = rememberChatMessageInfoBody(message)
    val senderLabel = if (message.isLocal) {
        stringResource(R.string.gatt_mesh_message_info_you)
    } else {
        conversationDisplayName.trim().takeIf { it.isNotEmpty() }
            ?: stringResource(R.string.rescue_unknown_user)
    }
    val timeFormatter = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    val timestamp = remember(message.timestampMillis) {
        timeFormatter.format(Date(message.timestampMillis))
    }

    P2pMessageInfoSheetContent(
        statusLabelRes = statusLabelRes,
        statusTint = statusTint,
        statusContainer = statusContainer,
        messageBody = body,
        senderLabel = senderLabel,
        timestamp = timestamp,
        onDismiss = onDismiss
    )
}

@Composable
internal fun P2pBleMessageInfoSheet(
    message: BleChatMessage,
    conversationDisplayName: String,
    onDismiss: () -> Unit
) {
    val statusLabelRes = when (message.status) {
        BleMessageStatus.QUEUED -> R.string.chat_message_status_pending
        BleMessageStatus.SENDING -> R.string.chat_message_status_sending
        BleMessageStatus.SENT -> R.string.chat_message_status_sent
        BleMessageStatus.DELIVERED -> R.string.chat_message_status_delivered
        BleMessageStatus.READ -> R.string.chat_message_status_read
        BleMessageStatus.FAILED -> R.string.chat_message_status_failed
    }
    val statusTint = when (message.status) {
        BleMessageStatus.READ -> MaterialTheme.colorScheme.primary
        BleMessageStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusContainer = when (message.status) {
        BleMessageStatus.READ -> MaterialTheme.colorScheme.primaryContainer
        BleMessageStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val body = rememberBleMessageInfoBody(message)
    val senderLabel = if (message.isLocal) {
        stringResource(R.string.gatt_mesh_message_info_you)
    } else {
        conversationDisplayName.trim().takeIf { it.isNotEmpty() }
            ?: stringResource(R.string.rescue_unknown_user)
    }
    val timeFormatter = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    val timestamp = remember(message.timestampMillis) {
        timeFormatter.format(Date(message.timestampMillis))
    }

    P2pMessageInfoSheetContent(
        statusLabelRes = statusLabelRes,
        statusTint = statusTint,
        statusContainer = statusContainer,
        messageBody = body,
        senderLabel = senderLabel,
        timestamp = timestamp,
        onDismiss = onDismiss
    )
}

@Composable
private fun P2pMessageInfoSheetContent(
    statusLabelRes: Int,
    statusTint: androidx.compose.ui.graphics.Color,
    statusContainer: androidx.compose.ui.graphics.Color,
    messageBody: String,
    senderLabel: String,
    timestamp: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.gatt_mesh_action_message_info),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = statusContainer
                ) {
                    Text(
                        text = stringResource(statusLabelRes),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusTint,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = messageBody,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    P2pMessageInfoMetaPill(
                        label = stringResource(R.string.gatt_mesh_message_info_sender),
                        value = senderLabel,
                        modifier = Modifier.weight(1f)
                    )
                    P2pMessageInfoMetaPill(
                        label = stringResource(R.string.gatt_mesh_message_info_time),
                        value = timestamp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun P2pMessageInfoMetaPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun rememberChatMessageInfoBody(message: ChatMessage): String {
    val context = LocalContext.current
    return remember(message) {
        when (message.messageType) {
            MessageType.AUDIO -> context.getString(R.string.conversation_preview_voice_message)
            MessageType.IMAGE -> context.getString(R.string.conversation_preview_photo_message)
            MessageType.TEXT -> {
                val parsedBody = parseReplyMetadata(message.text)?.body?.takeIf { it.isNotBlank() }
                    ?: message.text
                parseSharedLocationPayload(parsedBody)?.let { location ->
                    context.getString(
                        R.string.chat_location_coordinates,
                        formatLocationCoordinate(location.latitude),
                        formatLocationCoordinate(location.longitude)
                    )
                } ?: parseSharedFilePayload(parsedBody)?.let { file ->
                    context.getString(R.string.chat_file_copied_template, file.displayName)
                } ?: parsedBody.ifBlank {
                    context.getString(R.string.chat_reply_unknown_placeholder)
                }
            }
        }
    }
}

@Composable
private fun rememberBleMessageInfoBody(message: BleChatMessage): String {
    val context = LocalContext.current
    return remember(message) {
        when (message.messageType) {
            MessageType.AUDIO -> context.getString(R.string.conversation_preview_voice_message)
            MessageType.IMAGE -> context.getString(R.string.conversation_preview_photo_message)
            MessageType.TEXT -> {
                val parsedBody = parseReplyMetadata(message.text)?.body?.takeIf { it.isNotBlank() }
                    ?: message.text
                parsedBody.ifBlank { context.getString(R.string.chat_reply_unknown_placeholder) }
            }
        }
    }
}
