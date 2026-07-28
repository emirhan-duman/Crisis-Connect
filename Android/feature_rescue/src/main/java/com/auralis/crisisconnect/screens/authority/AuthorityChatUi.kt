package com.auralis.crisisconnect.screens.authority

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Chat.incomingChatBubbleColors
import com.auralis.crisisconnect.screens.Chat.outgoingChatBubbleColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One decrypted chat bubble shared by the agency + hierarchy authority screens.
 * Styled to match the peer-to-peer chat bubble ([com.auralis.crisisconnect.screens.Chat.ChatBubble]):
 * tail-cornered surface, the same outgoing/incoming bubble colors, and a trailing timestamp.
 */
@Composable
fun AuthorityMessageBubble(text: String, senderName: String?, isSelf: Boolean, atMillis: Long) {
    val (bubbleColor, contentColor) = if (isSelf) {
        outgoingChatBubbleColors()
    } else {
        incomingChatBubbleColors()
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = if (isSelf) 4.dp else 16.dp,
        bottomStart = if (isSelf) 16.dp else 4.dp
    )
    val formattedTimestamp = remember(atMillis) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(atMillis))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            contentColor = contentColor,
            shape = bubbleShape,
            border = if (isSelf) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            } else {
                null
            }
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (!isSelf && !senderName.isNullOrBlank()) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = formattedTimestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.align(if (isSelf) Alignment.End else Alignment.Start)
                )
            }
        }
    }
}

/**
 * The bottom compose row shared by the agency + hierarchy authority screens.
 * Mirrors the peer-to-peer [com.auralis.crisisconnect.screens.Chat.MessageComposer]: a rounded pill
 * text field with a filled circular send button.
 */
@Composable
fun AuthorityMessageInput(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    val hasText = draft.isNotBlank()
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp),
                placeholder = { Text(stringResource(R.string.authority_msg_input_hint)) },
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (hasText) onSend() })
            )
            IconButton(
                onClick = onSend,
                enabled = hasText,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = stringResource(R.string.authority_msg_send)
                )
            }
        }
    }
}
