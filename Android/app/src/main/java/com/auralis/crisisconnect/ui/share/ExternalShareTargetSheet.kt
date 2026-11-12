package com.auralis.crisisconnect.ui.share

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.media.DEFAULT_CHAT_IMAGE_TRANSFER_PROFILE
import com.auralis.crisisconnect.core.media.prepareImageAttachmentForTransfer
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.buildStoreForwardMessageUuid
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.observeContacts
import com.auralis.crisisconnect.data.saveLocalAudioMessage
import com.auralis.crisisconnect.data.saveLocalImageMessage
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.upsertLocalTextMessage
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.messaging.E2eEnvelope
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.screens.Chat.buildSharedFileMessage
import com.auralis.crisisconnect.screens.Chat.prepareDocumentAttachment
import com.auralis.crisisconnect.ui.components.ContactAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Metadata for a single incoming shared item copied into our app's cache.
 */
data class SharedIncomingItem(
    val file: File,
    val originalName: String,
    val mimeType: String,
    val sizeBytes: Long
)

/**
 * Container for the full payload received from an external app's ACTION_SEND or ACTION_SEND_MULTIPLE.
 */
data class IncomingSharePayload(
    val initialText: String = "",
    val items: List<SharedIncomingItem> = emptyList()
) {
    val isImagesOnly: Boolean get() = items.isNotEmpty() && items.all { it.mimeType.startsWith("image/") }
    val isAudioOnly: Boolean get() = items.isNotEmpty() && items.all { it.mimeType.startsWith("audio/") }
    val isDocument: Boolean get() = items.isNotEmpty() && !isImagesOnly && !isAudioOnly
    val isTextOnly: Boolean get() = items.isEmpty() && initialText.isNotBlank()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalShareTargetSheet(
    payload: IncomingSharePayload,
    onDismiss: () -> Unit,
    onShareSent: (List<String>) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val contacts by remember(context) { observeContacts(context) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCodes by rememberSaveable { mutableStateOf(setOf<String>()) }
    var captionText by rememberSaveable { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val filteredContacts = remember(contacts, searchQuery) {
        val query = searchQuery.trim().lowercase(Locale.getDefault())
        if (query.isEmpty()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.name.lowercase(Locale.getDefault()).contains(query) ||
                    contact.sessionCode.lowercase(Locale.getDefault()).contains(query)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!isSending) onDismiss()
        },
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_target_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            payload.isImagesOnly -> stringResource(R.string.conversation_preview_photo_message) + " (${payload.items.size})"
                            payload.isAudioOnly -> stringResource(R.string.share_target_audio_note)
                            payload.isDocument -> stringResource(R.string.share_target_file) + " (${payload.items.size})"
                            else -> stringResource(R.string.conversation_preview_text_placeholder)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = {
                        if (!isSending) onDismiss()
                    },
                    enabled = !isSending
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp
            )

            // Content Preview Box
            ShareContentPreview(
                payload = payload,
                caption = captionText,
                onCaptionChange = { captionText = it },
                isSending = isSending
            )

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.share_target_search_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            // Contact Recipient List
            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonOff,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Text(
                            text = stringResource(R.string.share_target_no_contacts),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.share_target_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    items(filteredContacts, key = { it.sessionCode }) { contact ->
                        val isSelected = selectedCodes.contains(contact.sessionCode)
                        RecipientRow(
                            contact = contact,
                            isSelected = isSelected,
                            onToggle = {
                                if (!isSending) {
                                    selectedCodes = if (isSelected) {
                                        selectedCodes - contact.sessionCode
                                    } else {
                                        selectedCodes + contact.sessionCode
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Bottom Action Area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                tonalElevation = 3.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    val singleSelectedContact = remember(selectedCodes, contacts) {
                        if (selectedCodes.size == 1) {
                            contacts.firstOrNull { it.sessionCode == selectedCodes.first() }
                        } else null
                    }

                    Button(
                        onClick = {
                            if (selectedCodes.isNotEmpty() && !isSending) {
                                isSending = true
                                val targetCodes = selectedCodes.toList()
                                val rawCaption = captionText.trim()
                                scope.launch(Dispatchers.IO) {
                                    dispatchSharePayload(
                                        context = context,
                                        payload = payload,
                                        targetCodes = targetCodes,
                                        caption = rawCaption
                                    )
                                    withContext(Dispatchers.Main) {
                                        isSending = false
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.share_target_sent_success),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onShareSent(targetCodes)
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = selectedCodes.isNotEmpty() && !isSending,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.share_target_sending),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when {
                                    selectedCodes.isEmpty() -> stringResource(R.string.share_target_select_recipients)
                                    singleSelectedContact != null -> stringResource(
                                        R.string.share_target_send_to_single,
                                        singleSelectedContact.name
                                    )
                                    else -> stringResource(
                                        R.string.share_target_send_to_multiple,
                                        selectedCodes.size
                                    )
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareContentPreview(
    payload: IncomingSharePayload,
    caption: String,
    onCaptionChange: (String) -> Unit,
    isSending: Boolean
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            when {
                // Images Preview
                payload.isImagesOnly -> {
                    if (payload.items.size == 1) {
                        val item = payload.items.first()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(item.file)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.originalName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatFileSize(item.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(payload.items) { item ->
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(item.file)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = caption,
                        onValueChange = onCaptionChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(R.string.share_target_caption_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        enabled = !isSending,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                // Audio Preview
                payload.isAudioOnly -> {
                    val item = payload.items.firstOrNull()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Audiotrack,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item?.originalName ?: stringResource(R.string.share_target_audio_note),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item != null) {
                                Text(
                                    text = formatFileSize(item.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = caption,
                        onValueChange = onCaptionChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(R.string.share_target_caption_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        enabled = !isSending,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                // Document / File Preview
                payload.isDocument -> {
                    val item = payload.items.firstOrNull()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item?.originalName ?: stringResource(R.string.share_target_file),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (item != null) {
                                Text(
                                    text = formatFileSize(item.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = caption,
                        onValueChange = onCaptionChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(R.string.share_target_caption_hint),
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        enabled = !isSending,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }

                // Text Preview
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = payload.initialText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientRow(
    contact: Contact,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContactAvatar(
                displayName = contact.name,
                stableKey = contact.sessionCode,
                modifier = Modifier.size(46.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = contact.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (contact.supportsInternet) {
                        "CrisisConnect • " + contact.sessionCode.take(8).uppercase(Locale.getDefault())
                    } else {
                        "Bluetooth • " + contact.sessionCode.take(8).uppercase(Locale.getDefault())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Selection Circle Indicator
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Dispatches the shared content to each target contact.
 */
private suspend fun dispatchSharePayload(
    context: Context,
    payload: IncomingSharePayload,
    targetCodes: List<String>,
    caption: String
) {
    val now = System.currentTimeMillis()
    val allContacts = runCatching { getContacts(context) }.getOrDefault(emptyList())
    val targets = targetCodes.mapNotNull { code ->
        allContacts.firstOrNull { it.sessionCode == code } ?: Contact(name = code, aesKey = "", sessionCode = code)
    }
    val internetTransport = InternetChatTransport(context)
    val internetAvailable = internetTransport.isAvailable()

    for ((idx, target) in targets.withIndex()) {
        val targetCode = target.sessionCode.trim()
        if (targetCode.isEmpty()) continue

        when {
            // Text Share
            payload.isTextOnly -> {
                val finalMsg = if (caption.isNotEmpty() && caption != payload.initialText) {
                    "${payload.initialText}\n\n$caption"
                } else {
                    payload.initialText.ifBlank { caption }
                }
                val msgUuid = buildStoreForwardMessageUuid(createdAtMillis = now + idx)
                upsertLocalTextMessage(
                    context = context,
                    sessionCode = targetCode,
                    uuid = msgUuid,
                    text = finalMsg,
                    deliveryStatus = MessageDeliveryStatus.QUEUED,
                    retryCount = 0,
                    nextRetryAtMillis = null,
                    lastAttemptAtMillis = null,
                    lastError = null,
                    outboundRoute = null
                )
                if (target.supportsInternet && internetAvailable) {
                    val sent = internetTransport.sendText(
                        messageId = msgUuid,
                        text = finalMsg,
                        createdAtMs = now,
                        contact = target
                    )
                    if (sent) {
                        updateLocalMessageDeliveryState(
                            context = context,
                            uuid = msgUuid,
                            deliveryStatus = MessageDeliveryStatus.SENT,
                            lastAttemptAtMillis = System.currentTimeMillis()
                        )
                    }
                }
            }

            // Image Share
            payload.isImagesOnly -> {
                for ((imgIdx, item) in payload.items.withIndex()) {
                    val imgUuid = UUID.randomUUID().toString()
                    val prepared = prepareImageAttachmentForTransfer(
                        context = context,
                        uuid = imgUuid,
                        uri = Uri.fromFile(item.file),
                        mimeType = item.mimeType,
                        fallbackWidth = 800,
                        fallbackHeight = 600,
                        profile = DEFAULT_CHAT_IMAGE_TRANSFER_PROFILE
                    )
                    if (prepared != null) {
                        saveLocalImageMessage(
                            context = context,
                            sessionCode = targetCode,
                            uuid = imgUuid,
                            fileName = prepared.fileName,
                            thumbnailName = prepared.thumbnailName,
                            width = prepared.width,
                            height = prepared.height,
                            mimeType = prepared.mimeType,
                            deliveryStatus = MessageDeliveryStatus.QUEUED,
                            text = if (imgIdx == 0) caption else ""
                        )
                        if (target.supportsInternet && internetAvailable) {
                            val sent = internetTransport.sendAttachment(
                                transferId = imgUuid,
                                kind = E2eEnvelope.ATTACHMENT_KIND_IMAGE,
                                mime = prepared.mimeType,
                                name = prepared.fileName,
                                durationMs = 0,
                                data = prepared.bytes,
                                createdAtMs = now,
                                contact = target
                            )
                            if (sent) {
                                updateLocalMessageDeliveryState(
                                    context = context,
                                    uuid = imgUuid,
                                    deliveryStatus = MessageDeliveryStatus.SENT,
                                    lastAttemptAtMillis = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                }
            }

            // Audio Share
            payload.isAudioOnly -> {
                for ((audIdx, item) in payload.items.withIndex()) {
                    val audUuid = UUID.randomUUID().toString()
                    val fileName = voiceMessageFileName(audUuid, item.mimeType)
                    val destination = voiceMessageFile(context, fileName)
                    runCatching { item.file.copyTo(destination, overwrite = true) }
                    if (destination.exists()) {
                        saveLocalAudioMessage(
                            context = context,
                            sessionCode = targetCode,
                            uuid = audUuid,
                            fileName = fileName,
                            audioDurationMillis = null,
                            deliveryStatus = MessageDeliveryStatus.QUEUED,
                            text = if (audIdx == 0) caption else ""
                        )
                        if (target.supportsInternet && internetAvailable) {
                            val bytes = runCatching { destination.readBytes() }.getOrNull()
                            if (bytes != null) {
                                val sent = internetTransport.sendAttachment(
                                    transferId = audUuid,
                                    kind = E2eEnvelope.ATTACHMENT_KIND_AUDIO,
                                    mime = item.mimeType.ifBlank { "audio/ogg" },
                                    name = fileName,
                                    durationMs = 0,
                                    data = bytes,
                                    createdAtMs = now,
                                    contact = target
                                )
                                if (sent) {
                                    updateLocalMessageDeliveryState(
                                        context = context,
                                        uuid = audUuid,
                                        deliveryStatus = MessageDeliveryStatus.SENT,
                                        lastAttemptAtMillis = System.currentTimeMillis()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Document Share
            else -> {
                for (item in payload.items) {
                    val docUuid = UUID.randomUUID().toString()
                    val contentUri = runCatching {
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            item.file
                        )
                    }.getOrDefault(Uri.fromFile(item.file))

                    val prepared = prepareDocumentAttachment(
                        context = context,
                        messageUuid = docUuid,
                        uri = contentUri
                    )
                    if (prepared != null) {
                        val msgText = buildSharedFileMessage(prepared)
                        upsertLocalTextMessage(
                            context = context,
                            sessionCode = targetCode,
                            uuid = docUuid,
                            text = msgText,
                            deliveryStatus = MessageDeliveryStatus.QUEUED,
                            retryCount = 0,
                            nextRetryAtMillis = null,
                            lastAttemptAtMillis = null,
                            lastError = null,
                            outboundRoute = null
                        )
                        if (target.supportsInternet && internetAvailable) {
                            val sent = internetTransport.sendAttachment(
                                transferId = docUuid,
                                kind = E2eEnvelope.ATTACHMENT_KIND_FILE,
                                mime = prepared.mimeType ?: "application/octet-stream",
                                name = prepared.displayName,
                                durationMs = 0,
                                data = prepared.payloadBytes,
                                createdAtMs = now,
                                contact = target
                            )
                            if (sent) {
                                updateLocalMessageDeliveryState(
                                    context = context,
                                    uuid = docUuid,
                                    deliveryStatus = MessageDeliveryStatus.SENT,
                                    lastAttemptAtMillis = System.currentTimeMillis()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val formatted = String.format(Locale.getDefault(), "%.1f", bytes / Math.pow(1024.0, digitGroups.toDouble()))
    return "$formatted ${units[digitGroups]}"
}
