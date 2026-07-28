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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.AgencyMessage
import com.auralis.crisisconnect.messaging.AuthorityRosterMember
import com.auralis.crisisconnect.screens.Chat.ChatTextureBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorityMessagingScreen(
    onBack: () -> Unit,
    viewModel: AuthorityMessagingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val callTargets by viewModel.callTargets.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showCallPicker by remember { mutableStateOf(false) }
    var pendingCallMember by remember { mutableStateOf<AuthorityRosterMember?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCallMember?.let { viewModel.startCallTo(it) }
    }
    val placeCall: (AuthorityRosterMember) -> Unit = { member ->
        pendingCallMember = member
        showCallPicker = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startCallTo(member)
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendError.collect { messageRes ->
            snackbarHostState.showSnackbar(viewModel.appErrorText(messageRes))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.authority_msg_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.authority_msg_back)
                        )
                    }
                },
                actions = {
                    if (state is AuthorityMessagingViewModel.State.Ready && callTargets.isNotEmpty()) {
                        IconButton(onClick = { showCallPicker = true }) {
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
        when (val s = state) {
            is AuthorityMessagingViewModel.State.Loading -> CenteredProgress(padding)
            is AuthorityMessagingViewModel.State.Error -> ErrorState(
                padding = padding,
                message = stringResource(s.messageRes),
                onRetry = viewModel::retry
            )
            is AuthorityMessagingViewModel.State.Ready -> ReadyContent(
                padding = padding,
                messages = messages,
                selfUid = viewModel.selfUid,
                draft = draft,
                onDraftChange = viewModel::updateDraft,
                onSend = viewModel::send
            )
        }
    }

    if (showCallPicker) {
        AlertDialog(
            onDismissRequest = { showCallPicker = false },
            title = { Text(stringResource(R.string.authority_call_pick_title)) },
            text = {
                LazyColumn {
                    items(callTargets, key = { it.uid }) { member ->
                        Text(
                            text = member.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { placeCall(member) }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCallPicker = false }) {
                    Text(stringResource(R.string.authority_call_cancel))
                }
            }
        )
    }
}

@Composable
private fun CenteredProgress(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorState(padding: PaddingValues, message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.authority_msg_retry)) }
        }
    }
}

@Composable
private fun ReadyContent(
    padding: PaddingValues,
    messages: List<AgencyMessage>,
    selfUid: String?,
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
                        text = stringResource(R.string.authority_msg_empty),
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
