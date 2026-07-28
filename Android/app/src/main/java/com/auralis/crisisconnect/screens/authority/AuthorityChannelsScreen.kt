package com.auralis.crisisconnect.screens.authority

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One row in the authority-messages list: a specific peer reachable through a cross-panel channel. */
data class ChannelConversation(
    val channelId: String,
    val peerUid: String,
    val peerName: String,
    val peerPanelName: String,
    val group: String,
    /** Last message preview for the home-list row (decrypted); blank if unknown. */
    val lastText: String = "",
    val lastAtMillis: Long = 0L,
    /** Sender of the last message; when it equals [peerUid] the last message is incoming (for unread). */
    val lastSenderUid: String = "",
    /** "image" / "audio" / "file" when the last message is an attachment (blank text), else "". */
    val lastAttachmentKind: String = "",
    /** The peer's authority role (admin / authority / fieldteam …) — for the header role badge. */
    val peerRole: String = "",
)

data class AuthorityChannelsState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val conversations: List<ChannelConversation> = emptyList(),
)

/**
 * Lists the cross-panel (hierarchy) peers this manager can message, flattened from
 * [HierarchyMessagingClient.fetchChannels] (each channel's roster → one row per peer). This is the
 * Android counterpart of the web dashboard's Agency Messages contact list; tapping a row opens the 1:1
 * thread. Agency (intra-panel broadcast) channels are not surfaced here yet.
 */
class AuthorityChannelsViewModel(app: Application) : AndroidViewModel(app) {
    private val client = HierarchyMessagingClient()
    private val _state = MutableStateFlow(AuthorityChannelsState())
    val state: StateFlow<AuthorityChannelsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = false)
        viewModelScope.launch {
            try {
                val channels = client.fetchChannels()
                android.util.Log.i(
                    "AuthorityChannels",
                    "fetchChannels ok: ${channels.size} channels; peers=" +
                        channels.joinToString(" | ") { ch ->
                            "${ch.channelId.take(10)}[${ch.peers.joinToString(",") { "${it.name}:${it.uid.take(6)}" }}]"
                        },
                )
                val conversations = channels.flatMap { channel ->
                    channel.peers.map { peer ->
                        ChannelConversation(
                            channelId = channel.channelId,
                            peerUid = peer.uid,
                            peerName = peer.name,
                            peerPanelName = channel.peerPanelName,
                            group = channel.group,
                        )
                    }
                }
                _state.value = AuthorityChannelsState(loading = false, conversations = conversations)
            } catch (e: Exception) {
                android.util.Log.w("AuthorityChannels", "fetchChannels failed", e)
                _state.value = AuthorityChannelsState(loading = false, error = true)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorityChannelsScreen(navController: NavHostController) {
    val viewModel: AuthorityChannelsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.authority_channels_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && state.conversations.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.error && state.conversations.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.authority_channels_error),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                state.conversations.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.authority_channels_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.conversations, key = { "${it.channelId}:${it.peerUid}" }) { conversation ->
                            ConversationRow(conversation = conversation) {
                                val route = "authority_channel/${Uri.encode(conversation.channelId)}/" +
                                    "${Uri.encode(conversation.peerUid)}?title=${Uri.encode(conversation.peerName)}"
                                android.util.Log.i("AuthorityChannels", "row tap -> navigate $route")
                                runCatching { navController.navigate(route) }
                                    .onFailure { android.util.Log.w("AuthorityChannels", "navigate failed", it) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: ChannelConversation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = conversation.peerName.trim().take(1).uppercase().ifBlank { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.peerName.ifBlank { conversation.peerUid },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (conversation.peerPanelName.isNotBlank()) {
                Text(
                    text = conversation.peerPanelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
