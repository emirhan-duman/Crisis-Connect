package com.auralis.crisisconnect.screens.Chat

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
    val context = LocalContext.current
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
    val dateTimeFormatter = remember { SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()) }
    val sentTimeFormatted = remember(message.timestampMillis, message.originalTimestampMillis, message.isLocal) {
        val targetMillis = if (message.isLocal) {
            message.timestampMillis
        } else {
            message.originalTimestampMillis ?: message.timestampMillis
        }
        dateTimeFormatter.format(Date(targetMillis))
    }

    val isDelivered = normalizedStatus in listOf(MessageDeliveryStatus.DELIVERED, MessageDeliveryStatus.READ)
    val deliveredTimeFormatted = remember(isDelivered, message.deliveredAtMillis, message.lastAttemptAtMillis, message.timestampMillis) {
        if (isDelivered) {
            val ts = message.deliveredAtMillis ?: message.lastAttemptAtMillis ?: message.timestampMillis
            dateTimeFormatter.format(Date(ts))
        } else null
    }

    val isRead = normalizedStatus == MessageDeliveryStatus.READ
    val readTimeFormatted = remember(isRead, message.readAtMillis, message.deliveredAtMillis, message.timestampMillis) {
        if (isRead) {
            val ts = message.readAtMillis ?: message.deliveredAtMillis ?: message.timestampMillis
            dateTimeFormatter.format(Date(ts))
        } else null
    }

    val receivedTimeFormatted = remember(message.isLocal, message.timestampMillis) {
        if (!message.isLocal) {
            dateTimeFormatter.format(Date(message.timestampMillis))
        } else null
    }

    val transport = remember(message.outboundRoute) {
        resolveChatTransport(message.outboundRoute)
    }
    val transportName = when (transport) {
        ChatTransport.Bluetooth -> stringResource(R.string.chat_message_info_transport_bluetooth)
        ChatTransport.Internet -> stringResource(R.string.chat_message_info_transport_internet)
        ChatTransport.Satellite -> stringResource(R.string.chat_message_info_transport_satellite)
    }
    val transportDetail = remember(message.outboundRoute, transport) {
        resolveTransportDetail(message.outboundRoute, transport, context)
    }

    val retryCount = message.retryCount
    val queueDelayMillis: Long? = remember(message.timestampMillis, message.originalTimestampMillis, message.deliveredAtMillis, message.isLocal) {
        if (!message.isLocal) {
            val original = message.originalTimestampMillis
            if (original != null && message.timestampMillis > original) {
                message.timestampMillis - original
            } else null
        } else {
            val delivered = message.deliveredAtMillis
            if (delivered != null && delivered > message.timestampMillis) {
                delivered - message.timestampMillis
            } else null
        }
    }
    val queueDelayFormatted = remember(queueDelayMillis) {
        queueDelayMillis?.takeIf { it >= 3000L }?.let { formatDurationLocalized(it) }
    }
    val storeForwardExplanation = remember(normalizedStatus, retryCount, queueDelayFormatted, message.isLocal) {
        when {
            normalizedStatus in listOf(MessageDeliveryStatus.QUEUED, MessageDeliveryStatus.SENDING) -> {
                context.getString(R.string.chat_message_info_sf_queued)
            }
            retryCount > 0 -> {
                context.getString(R.string.chat_message_info_sf_retried, retryCount)
            }
            !message.isLocal && queueDelayFormatted != null -> {
                context.getString(R.string.chat_message_info_sf_delayed_inbound, queueDelayFormatted)
            }
            message.isLocal && queueDelayFormatted != null -> {
                context.getString(R.string.chat_message_info_sf_delayed_outbound, queueDelayFormatted)
            }
            else -> {
                context.getString(R.string.chat_message_info_sf_direct)
            }
        }
    }

    P2pMessageInfoSheetContent(
        isLocal = message.isLocal,
        statusLabelRes = statusLabelRes,
        statusTint = statusTint,
        statusContainer = statusContainer,
        messageBody = body,
        senderLabel = senderLabel,
        transport = transport,
        transportName = transportName,
        transportDetail = transportDetail,
        sentTimeFormatted = sentTimeFormatted,
        deliveredTimeFormatted = deliveredTimeFormatted,
        readTimeFormatted = readTimeFormatted,
        receivedTimeFormatted = receivedTimeFormatted,
        storeForwardExplanation = storeForwardExplanation,
        retryCount = retryCount,
        queueDelayFormatted = queueDelayFormatted,
        lastError = message.lastError.takeIf { normalizedStatus == MessageDeliveryStatus.FAILED },
        onDismiss = onDismiss
    )
}

@Composable
internal fun P2pBleMessageInfoSheet(
    message: BleChatMessage,
    conversationDisplayName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
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
    val dateTimeFormatter = remember { SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()) }
    val sentTimeFormatted = remember(message.timestampMillis, message.originalTimestampMillis, message.isLocal) {
        val targetMillis = if (message.isLocal) {
            message.timestampMillis
        } else {
            message.originalTimestampMillis ?: message.timestampMillis
        }
        dateTimeFormatter.format(Date(targetMillis))
    }

    val isDelivered = message.status in listOf(BleMessageStatus.DELIVERED, BleMessageStatus.READ)
    val deliveredTimeFormatted = remember(isDelivered, message.deliveredAtMillis, message.timestampMillis) {
        if (isDelivered) {
            val ts = message.deliveredAtMillis ?: message.timestampMillis
            dateTimeFormatter.format(Date(ts))
        } else null
    }

    val isRead = message.status == BleMessageStatus.READ
    val readTimeFormatted = remember(isRead, message.readAtMillis, message.deliveredAtMillis, message.timestampMillis) {
        if (isRead) {
            val ts = message.readAtMillis ?: message.deliveredAtMillis ?: message.timestampMillis
            dateTimeFormatter.format(Date(ts))
        } else null
    }

    val receivedTimeFormatted = remember(message.isLocal, message.timestampMillis) {
        if (!message.isLocal) {
            dateTimeFormatter.format(Date(message.timestampMillis))
        } else null
    }

    val transport = remember(message.outboundRoute) {
        resolveChatTransport(message.outboundRoute)
    }
    val transportName = when (transport) {
        ChatTransport.Bluetooth -> stringResource(R.string.chat_message_info_transport_bluetooth)
        ChatTransport.Internet -> stringResource(R.string.chat_message_info_transport_internet)
        ChatTransport.Satellite -> stringResource(R.string.chat_message_info_transport_satellite)
    }
    val transportDetail = remember(message.outboundRoute, transport) {
        resolveTransportDetail(message.outboundRoute, transport, context)
    }

    val retryCount = message.retryCount
    val queueDelayMillis: Long? = remember(message.timestampMillis, message.originalTimestampMillis, message.deliveredAtMillis, message.isLocal) {
        if (!message.isLocal) {
            val original = message.originalTimestampMillis
            if (original != null && message.timestampMillis > original) {
                message.timestampMillis - original
            } else null
        } else {
            val delivered = message.deliveredAtMillis
            if (delivered != null && delivered > message.timestampMillis) {
                delivered - message.timestampMillis
            } else null
        }
    }
    val queueDelayFormatted = remember(queueDelayMillis) {
        queueDelayMillis?.takeIf { it >= 3000L }?.let { formatDurationLocalized(it) }
    }
    val storeForwardExplanation = remember(message.status, retryCount, queueDelayFormatted, message.isLocal) {
        when {
            message.status in listOf(BleMessageStatus.QUEUED, BleMessageStatus.SENDING) -> {
                context.getString(R.string.chat_message_info_sf_queued)
            }
            retryCount > 0 -> {
                context.getString(R.string.chat_message_info_sf_retried, retryCount)
            }
            !message.isLocal && queueDelayFormatted != null -> {
                context.getString(R.string.chat_message_info_sf_delayed_inbound, queueDelayFormatted)
            }
            message.isLocal && queueDelayFormatted != null -> {
                context.getString(R.string.chat_message_info_sf_delayed_outbound, queueDelayFormatted)
            }
            else -> {
                context.getString(R.string.chat_message_info_sf_direct)
            }
        }
    }

    P2pMessageInfoSheetContent(
        isLocal = message.isLocal,
        statusLabelRes = statusLabelRes,
        statusTint = statusTint,
        statusContainer = statusContainer,
        messageBody = body,
        senderLabel = senderLabel,
        transport = transport,
        transportName = transportName,
        transportDetail = transportDetail,
        sentTimeFormatted = sentTimeFormatted,
        deliveredTimeFormatted = deliveredTimeFormatted,
        readTimeFormatted = readTimeFormatted,
        receivedTimeFormatted = receivedTimeFormatted,
        storeForwardExplanation = storeForwardExplanation,
        retryCount = retryCount,
        queueDelayFormatted = queueDelayFormatted,
        lastError = message.lastError.takeIf { message.status == BleMessageStatus.FAILED },
        onDismiss = onDismiss
    )
}

@Composable
private fun P2pMessageInfoSheetContent(
    isLocal: Boolean,
    statusLabelRes: Int,
    statusTint: Color,
    statusContainer: Color,
    messageBody: String,
    senderLabel: String,
    transport: ChatTransport,
    transportName: String,
    transportDetail: String,
    sentTimeFormatted: String,
    deliveredTimeFormatted: String?,
    readTimeFormatted: String?,
    receivedTimeFormatted: String?,
    storeForwardExplanation: String,
    retryCount: Int,
    queueDelayFormatted: String?,
    lastError: String?,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
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

        // 1. Message Preview Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
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
                        text = senderLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = messageBody,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )

                if (!lastError.isNullOrBlank()) {
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 2. Transport (İletim Yolu) Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when (transport) {
                            ChatTransport.Bluetooth -> Icon(
                                imageVector = Icons.Filled.Bluetooth,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            ChatTransport.Internet -> Icon(
                                imageVector = Icons.Filled.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            ChatTransport.Satellite -> Icon(
                                painter = painterResource(R.drawable.ic_satellite),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.chat_message_info_transport),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = transportName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = transportDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 3. Store & Forward (Depola ve İlet) Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Sync,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.chat_message_info_store_forward),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_message_info_sf_badge),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = storeForwardExplanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (retryCount > 0 || queueDelayFormatted != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (retryCount > 0) {
                            P2pMessageInfoMetaPill(
                                label = stringResource(R.string.chat_message_info_sf_retries_label),
                                value = "$retryCount",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (queueDelayFormatted != null) {
                            P2pMessageInfoMetaPill(
                                label = stringResource(R.string.chat_message_info_sf_delay_label),
                                value = queueDelayFormatted,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 4. Status Breakdown / Timeline Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLocal) {
                    // READ (Görüldü)
                    val readCompleted = readTimeFormatted != null
                    val readIndicatorColor = Color(0xFF34B7F1)
                    MessageInfoTimelineRow(
                        icon = Icons.Filled.DoneAll,
                        iconTint = if (readCompleted) readIndicatorColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        title = stringResource(R.string.chat_message_info_read),
                        subtitle = readTimeFormatted ?: stringResource(R.string.chat_message_info_not_read_yet),
                        isCompleted = readCompleted
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // DELIVERED (İletildi)
                    val deliveredCompleted = deliveredTimeFormatted != null
                    MessageInfoTimelineRow(
                        icon = Icons.Filled.DoneAll,
                        iconTint = if (deliveredCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        title = stringResource(R.string.chat_message_info_delivered),
                        subtitle = deliveredTimeFormatted ?: stringResource(R.string.chat_message_info_not_delivered_yet),
                        isCompleted = deliveredCompleted
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // SENT (Gönderildi)
                    MessageInfoTimelineRow(
                        icon = Icons.Filled.Done,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = stringResource(R.string.chat_message_info_sent),
                        subtitle = sentTimeFormatted,
                        isCompleted = true
                    )
                } else {
                    // SENT (Gönderilme)
                    MessageInfoTimelineRow(
                        icon = Icons.Outlined.Schedule,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.chat_message_info_sent),
                        subtitle = sentTimeFormatted,
                        isCompleted = true
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // RECEIVED (Alınma)
                    MessageInfoTimelineRow(
                        icon = Icons.Filled.DoneAll,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.chat_message_info_received),
                        subtitle = receivedTimeFormatted ?: sentTimeFormatted,
                        isCompleted = true
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInfoTimelineRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    isCompleted: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isCompleted) iconTint.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
            )
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

internal fun resolveChatTransport(route: String?): ChatTransport {
    val clean = route?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        clean.contains("satellite") -> ChatTransport.Satellite
        clean.contains("internet") || clean.contains("cloud") || clean.contains("firebase") -> ChatTransport.Internet
        else -> ChatTransport.Bluetooth
    }
}

internal fun resolveTransportDetail(route: String?, transport: ChatTransport, context: Context): String {
    val clean = route?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when (transport) {
        ChatTransport.Bluetooth -> {
            when {
                clean == "rfcomm" -> "Bluetooth (RFCOMM)"
                clean == "p2p_ble_gatt" || clean == "ble_gatt_fallback" -> "Bluetooth (BLE)"
                clean.isNotEmpty() && clean != "bluetooth" -> "Bluetooth ($clean)"
                else -> context.getString(R.string.chat_message_info_transport_bluetooth_desc)
            }
        }
        ChatTransport.Internet -> context.getString(R.string.chat_message_info_transport_internet_desc)
        ChatTransport.Satellite -> context.getString(R.string.chat_message_info_transport_satellite_desc)
    }
}

private fun formatDurationLocalized(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000).coerceAtLeast(1)
    val isTurkish = Locale.getDefault().language.startsWith("tr")
    return when {
        totalSeconds < 60 -> if (isTurkish) "$totalSeconds sn" else "${totalSeconds}s"
        totalSeconds < 3600 -> {
            val mins = totalSeconds / 60
            val remSec = totalSeconds % 60
            if (remSec == 0L) {
                if (isTurkish) "$mins dk" else "${mins}m"
            } else {
                if (isTurkish) "$mins dk $remSec sn" else "${mins}m ${remSec}s"
            }
        }
        else -> {
            val hours = totalSeconds / 3600
            val mins = (totalSeconds % 3600) / 60
            if (isTurkish) "$hours sa $mins dk" else "${hours}h ${mins}m"
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
            MessageType.SOS_ALERT -> message.text.ifBlank {
                context.getString(R.string.chat_reply_unknown_placeholder)
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
            MessageType.SOS_ALERT -> message.text.ifBlank {
                context.getString(R.string.chat_reply_unknown_placeholder)
            }
        }
    }
}
