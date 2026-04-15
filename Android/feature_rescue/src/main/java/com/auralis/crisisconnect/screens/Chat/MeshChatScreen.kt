package com.auralis.crisisconnect.screens.Chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.ui.components.ContactAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshChatScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: MeshChatViewModel = viewModel()
    val meshState by viewModel.meshState.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val messageDraft by viewModel.messageDraft.collectAsState()
    val canChat = remember(meshState) { MeshChatViewModel.isSecureMeshChatReady(meshState) }
    val listState = rememberLazyListState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    DisposableEffect(Unit) {
        viewModel.onScreenStarted()
        onDispose {
            viewModel.onScreenStopped()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex + 1)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendFailureEvents.collect { messageId ->
            Toast.makeText(context, context.getString(messageId), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.mesh_chat_general_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        val participantCount = meshState.connectedPeerCount + 1
                        val subtitle = if (canChat) {
                            stringResource(R.string.mesh_chat_connected_count, participantCount)
                        } else {
                            stringResource(R.string.mesh_chat_waiting_for_peers)
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageDraft,
                        onValueChange = viewModel::updateDraft,
                        modifier = Modifier.weight(1f),
                        enabled = canChat,
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        placeholder = {
                            Text(text = stringResource(R.string.mesh_chat_message_placeholder))
                        }
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    IconButton(
                        onClick = { viewModel.sendMessage() },
                        enabled = canChat && messageDraft.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send_message)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            ChatTextureBackground(modifier = Modifier.fillMaxSize())
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "mesh_security_notice") {
                    MeshSecurityNoticeCard(canChat = canChat)
                }
                items(
                    items = messages,
                    key = MeshChatMessage::id
                ) { message ->
                    MeshChatMessageBubble(
                        message = message,
                        timeFormatter = timeFormatter
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshSecurityNoticeCard(
    canChat: Boolean
) {
    val text = if (canChat) {
        stringResource(R.string.mesh_chat_security_notice)
    } else {
        stringResource(R.string.mesh_chat_waiting_notice)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun MeshChatMessageBubble(
    message: MeshChatMessage,
    timeFormatter: SimpleDateFormat
) {
    val alignment = if (message.isLocal) Alignment.CenterEnd else Alignment.CenterStart
    val (bubbleColor, contentColor) = if (message.isLocal) {
        outgoingChatBubbleColors()
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp) to MaterialTheme.colorScheme.onSurface
    }
    val remoteDisplayName = message.senderLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.rescue_unknown_user)
    val senderLabel = message.senderLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            if (!message.isLocal) {
                ContactAvatar(
                    displayName = remoteDisplayName,
                    stableKey = "mesh-remote-${message.senderLabel ?: message.id}",
                    modifier = Modifier.size(28.dp),
                    textStyle = MaterialTheme.typography.labelMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier
                    .background(bubbleColor, RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (!message.isLocal && senderLabel != null) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = timeFormatter.format(Date(message.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
