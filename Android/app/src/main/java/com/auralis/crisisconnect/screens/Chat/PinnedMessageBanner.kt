package com.auralis.crisisconnect.screens.Chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageType

@Composable
internal fun PinnedMessageBanner(
    message: ChatMessage,
    conversationDisplayName: String,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewText = remember(message) {
        when (message.messageType) {
            MessageType.AUDIO -> context.getString(R.string.conversation_preview_voice_message)
            MessageType.IMAGE -> context.getString(R.string.conversation_preview_photo_message)
            MessageType.TEXT -> {
                val parsed = parseReplyMetadata(message.text)?.body?.takeIf { it.isNotBlank() } ?: message.text
                parseSharedLocationPayload(parsed)?.let { loc ->
                    context.getString(
                        R.string.chat_location_coordinates,
                        formatLocationCoordinate(loc.latitude),
                        formatLocationCoordinate(loc.longitude)
                    )
                } ?: parseSharedFilePayload(parsed)?.let { file ->
                    context.getString(R.string.chat_file_copied_template, file.displayName)
                } ?: parsed.ifBlank {
                    context.getString(R.string.chat_reply_unknown_placeholder)
                }
            }
            MessageType.SOS_ALERT -> message.text.ifBlank {
                context.getString(R.string.chat_reply_unknown_placeholder)
            }
        }
    }

    val authorLabel = if (message.isLocal) {
        stringResource(R.string.gatt_mesh_message_info_you)
    } else {
        conversationDisplayName.trim().takeIf { it.isNotEmpty() }
            ?: stringResource(R.string.rescue_unknown_user)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Pin icon with slight rotation (like WhatsApp)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(35f)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.chat_pinned_message_title),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "• $authorLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Unpin (X) button
            IconButton(
                onClick = onUnpin,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_action_unpin),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
