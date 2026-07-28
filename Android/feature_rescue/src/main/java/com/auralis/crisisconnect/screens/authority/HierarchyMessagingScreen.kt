package com.auralis.crisisconnect.screens.authority

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.HierarchyChannel
import com.auralis.crisisconnect.messaging.HierarchyMessage
import com.auralis.crisisconnect.messaging.HierarchyPeer
import com.auralis.crisisconnect.screens.Chat.ChatTextureBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HierarchyMessagingScreen(
    onBack: () -> Unit,
    viewModel: HierarchyMessagingViewModel = viewModel()
) {
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startCall()
    }
    val onCall: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startCall()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendError.collect { snackbarHostState.showSnackbar(viewModel.appErrorText(it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(active?.peer?.name ?: stringResource(R.string.hierarchy_msg_title))
                },
                navigationIcon = {
                    IconButton(onClick = { if (active != null) viewModel.closeConversation() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.authority_msg_back)
                        )
                    }
                },
                actions = {
                    if (active != null) {
                        IconButton(onClick = onCall) {
                            Icon(
                                Icons.Filled.Call,
                                contentDescription = stringResource(R.string.internet_call_start_action)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        val current = active
        if (current == null) {
            when (val p = picker) {
                is HierarchyMessagingViewModel.PickerState.Loading -> CenteredProgress(padding)
                is HierarchyMessagingViewModel.PickerState.Error -> HierarchyError(
                    padding = padding,
                    message = stringResource(p.messageRes),
                    onRetry = viewModel::loadChannels
                )
                is HierarchyMessagingViewModel.PickerState.Ready -> ChannelPicker(
                    padding = padding,
                    channels = p.channels,
                    onPeer = viewModel::openConversation
                )
            }
        } else {
            ChatContent(
                padding = padding,
                messages = messages,
                selfUid = viewModel.selfUid,
                peerName = current.peer.name,
                draft = draft,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send
            )
        }
    }
}

@Composable
private fun CenteredProgress(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun HierarchyError(padding: PaddingValues, message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.authority_msg_retry)) }
        }
    }
}

@Composable
private fun ChannelPicker(
    padding: PaddingValues,
    channels: List<HierarchyChannel>,
    onPeer: (HierarchyChannel, HierarchyPeer) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        channels.forEach { channel ->
            item(key = "h_" + channel.channelId) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = channel.peerPanelName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = groupLabel(channel.group),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(channel.peers, key = { channel.channelId + "_" + it.uid }) { peer ->
                PeerRow(peer = peer, onClick = { onPeer(channel, peer) })
            }
            item(key = "d_" + channel.channelId) { HorizontalDivider() }
        }
    }
}

@Composable
private fun PeerRow(peer: HierarchyPeer, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(text = peer.name, style = MaterialTheme.typography.bodyLarge)
        peer.role?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatContent(
    padding: PaddingValues,
    messages: List<HierarchyMessage>,
    selfUid: String?,
    peerName: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ChatTextureBackground(modifier = Modifier.matchParentSize())
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.hierarchy_msg_empty, peerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        AuthorityMessageBubble(
                            text = message.text,
                            senderName = message.senderName,
                            isSelf = selfUid != null && message.senderUid == selfUid,
                            atMillis = message.atMillis
                        )
                    }
                }
            }
        }
        AuthorityMessageInput(draft = draft, onDraftChange = onDraftChange, onSend = onSend)
    }
}

@Composable
private fun groupLabel(group: String): String = when (group.lowercase()) {
    "up" -> stringResource(R.string.hierarchy_group_up)
    "down" -> stringResource(R.string.hierarchy_group_down)
    else -> stringResource(R.string.hierarchy_group_sibling)
}
