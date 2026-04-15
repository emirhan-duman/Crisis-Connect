package com.auralis.crisisconnect.screens.Chat

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.core.chat.previewTextForReplyTarget
import com.auralis.crisisconnect.data.GattMeshChatStore
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.gattmesh.GattMeshConnectedPeer
import com.auralis.crisisconnect.service.gattmesh.GattMeshPeerVerificationStatus
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.GroupChatAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.roundToInt
import com.auralis.crisisconnect.settingsDataStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GattMeshScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val viewModel: GattMeshViewModel = viewModel()
    val localUserName by getSavedUserName(context).collectAsState(initial = "")
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageDraft by viewModel.messageDraft.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keep composer enabled while mesh mode is on so users can queue messages offline.
    val canCompose = uiState.publicMeshEnabled
    val meshDeviceCount = remember(
        uiState.publicMeshEnabled,
        uiState.isServiceEnabled,
        uiState.connectedPeerCount,
        uiState.connectedPeers
    ) {
        // Discovery is intentionally local and can diverge across phones because scan callbacks are
        // opportunistic / device-specific. The UI headline should therefore reflect only peers that
        // are actually connected, otherwise two devices in the same mesh can show different
        // "devices in network" counts at the same moment.
        val networkPeerCount = maxOf(
            uiState.connectedPeerCount,
            uiState.connectedPeers.size
        )
        if (uiState.publicMeshEnabled && (uiState.isServiceEnabled || networkPeerCount > 0)) {
            networkPeerCount + 1
        } else {
            0
        }
    }
    val listState = rememberLazyListState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var replyTargetId by remember { mutableStateOf<String?>(null) }
    var infoTargetMessageId by remember { mutableStateOf<String?>(null) }
    var introDismissedThisSession by rememberSaveable { mutableStateOf(false) }
    var showConnectedPeersSheet by remember { mutableStateOf(false) }
    val connectedPeersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val messageInfoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val introSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val dismissIntroKey = remember { booleanPreferencesKey("advanced_gatt_mesh_intro_dismissed") }
    val experimentalFeaturesKey = remember {
        booleanPreferencesKey("advanced_experimental_features_enabled")
    }
    val introDismissedFlow = remember(context, dismissIntroKey) {
        context.settingsDataStore.data.map { prefs -> prefs[dismissIntroKey] ?: false }
    }
    val experimentalFeaturesFlow = remember(context, experimentalFeaturesKey) {
        context.settingsDataStore.data.map { prefs -> prefs[experimentalFeaturesKey] ?: false }
    }
    val introDismissedForever: Boolean by introDismissedFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val experimentalFeaturesEnabled: Boolean by experimentalFeaturesFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val shouldShowIntroSheet = !introDismissedForever && !introDismissedThisSession
    val selfDisplayName = remember(localUserName) {
        localUserName.trim()
    }.ifBlank {
        context.getString(R.string.gatt_mesh_message_info_you)
    }
    val selfSubtitle = stringResource(R.string.gatt_mesh_connected_users_this_device)
    val showSelfInConnectedList = uiState.publicMeshEnabled && uiState.isServiceEnabled
    val connectedPeerItems = remember(
        uiState.connectedPeers,
        selfDisplayName,
        selfSubtitle,
        showSelfInConnectedList
    ) {
        buildList {
            if (showSelfInConnectedList) {
                add(
                    ConnectedPeerSheetItem(
                        key = SELF_CONNECTED_PEER_KEY,
                        displayName = selfDisplayName,
                        secondaryText = selfSubtitle,
                        isSelf = true,
                        verificationStatus = GattMeshPeerVerificationStatus.UNVERIFIED,
                        verifiedRole = null,
                    )
                )
            }
            uiState.connectedPeers.forEach { peer ->
                add(
                    ConnectedPeerSheetItem(
                        key = peer.address,
                        displayName = peer.displayName,
                        secondaryText = peer.address,
                        isSelf = false,
                        verificationStatus = peer.verificationStatus,
                        verifiedRole = peer.verifiedRole,
                    )
                )
            }
        }
    }
    val replyTarget = remember(messages, replyTargetId) {
        messages.firstOrNull { it.id == replyTargetId }
    }
    val infoTargetMessage = remember(messages, infoTargetMessageId) {
        messages.firstOrNull { it.id == infoTargetMessageId }
    }
    val infoTargetDirectPeer = remember(infoTargetMessage, uiState.connectedPeers) {
        val sourceAddress = infoTargetMessage?.sourceAddress?.trim().orEmpty()
        if (sourceAddress.isEmpty()) {
            null
        } else {
            uiState.connectedPeers.firstOrNull { peer ->
                peer.address.equals(sourceAddress, ignoreCase = true)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        var isScreenStarted = false
        fun onStart() {
            if (isScreenStarted) return
            isScreenStarted = true
            ActiveChatTracker.setActiveSession(GattMeshChatStore.SESSION_CODE)
            viewModel.onScreenStarted()
        }
        fun onStop() {
            if (!isScreenStarted) return
            isScreenStarted = false
            viewModel.onScreenStopped()
            ActiveChatTracker.clearSession(GattMeshChatStore.SESSION_CODE)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_STOP -> onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onStart()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStop()
        }
    }

    LaunchedEffect(messages, replyTargetId) {
        if (replyTargetId != null && replyTarget == null) {
            replyTargetId = null
        }
    }

    LaunchedEffect(messages, infoTargetMessageId) {
        if (infoTargetMessageId != null && infoTargetMessage == null) {
            infoTargetMessageId = null
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
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GroupChatAvatar(
                            modifier = Modifier.size(36.dp),
                            iconSize = 18.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.mesh_chat_general_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (!uiState.publicMeshEnabled) {
                                    stringResource(R.string.mesh_chat_mode_disabled_short)
                                } else {
                                    stringResource(R.string.gatt_mesh_connected_count, meshDeviceCount)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showConnectedPeersSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.gatt_mesh_connected_users_title)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            Box(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        replyTarget?.let { target ->
                            GattMeshReplyComposerBanner(
                                target = target,
                                onDismiss = { replyTargetId = null }
                            )
                        }
                        if (!uiState.publicMeshEnabled) {
                            OutlinedButton(
                                onClick = viewModel::enablePublicMeshMode,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(text = stringResource(R.string.gatt_mesh_action_join_general_chat))
                            }
                        }
                        GattMeshMessageComposer(
                            value = messageDraft,
                            enabled = canCompose,
                            onValueChange = viewModel::updateDraft,
                            onSendText = {
                                viewModel.sendMessage(replyTarget)
                                replyTargetId = null
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ChatTextureBackground(modifier = Modifier.fillMaxSize())
            if (messages.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    GattMeshStatusCard(
                        uiState = uiState,
                        meshDeviceCount = meshDeviceCount,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.mesh_chat_message_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = messages,
                        key = MeshChatMessage::id
                    ) { message ->
                        GattMeshMessageBubble(
                            message = message,
                            timeFormatter = timeFormatter,
                            connectedPeers = uiState.connectedPeers,
                            onReplyRequested = { selected ->
                                replyTargetId = selected.id
                            },
                            onReplyNavigate = { targetId ->
                                val targetIndex = messages.indexOfFirst { it.id == targetId }
                                coroutineScope.launch {
                                    if (targetIndex >= 0) {
                                        listState.animateScrollToItem(targetIndex)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.chat_reply_message_not_found),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            },
                            onInfoRequested = { selected ->
                                infoTargetMessageId = selected.id
                            }
                        )
                    }
                }
            }
        }
    }

    if (shouldShowIntroSheet) {
        ModalBottomSheet(
            onDismissRequest = { introDismissedThisSession = true },
            sheetState = introSheetState,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            tonalElevation = 10.dp,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        ) {
            GattMeshIntroBottomSheet(
                encryptionStatus = if (experimentalFeaturesEnabled) {
                    stringResource(R.string.gatt_mesh_intro_encryption_status_experimental)
                } else {
                    stringResource(R.string.gatt_mesh_intro_encryption_status_standard)
                },
                onContinue = { dismissForever ->
                    introDismissedThisSession = true
                    if (dismissForever) {
                        coroutineScope.launch {
                            context.settingsDataStore.edit { prefs ->
                                prefs[dismissIntroKey] = true
                            }
                        }
                    }
                },
                onCancel = {
                    introDismissedThisSession = true
                    navController.navigateUp()
                }
            )
        }
    }

    if (showConnectedPeersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showConnectedPeersSheet = false },
            sheetState = connectedPeersSheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            tonalElevation = 8.dp,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        ) {
            ConnectedPeersBottomSheet(
                peers = connectedPeerItems,
                onPeerClick = { showConnectedPeersSheet = false }
            )
        }
    }

    if (infoTargetMessage != null) {
        ModalBottomSheet(
            onDismissRequest = { infoTargetMessageId = null },
            sheetState = messageInfoSheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            GattMeshMessageInfoSheet(
                message = infoTargetMessage,
                directPeer = infoTargetDirectPeer,
                onDismiss = { infoTargetMessageId = null }
            )
        }
    }
}

@Composable
private fun GattMeshIntroBottomSheet(
    encryptionStatus: String,
    onContinue: (dismissForever: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    var dismissForeverChecked by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compactLayout = maxWidth < 360.dp
        val horizontalPadding = if (compactLayout) 16.dp else 20.dp
        val sectionSpacing = if (compactLayout) 12.dp else 16.dp
        val cardCornerRadius = if (compactLayout) 16.dp else 18.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = if (compactLayout) 16.dp else 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(sectionSpacing)
        ) {
            Surface(
                shape = RoundedCornerShape(cardCornerRadius),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.gatt_mesh_intro_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.gatt_mesh_intro_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(cardCornerRadius),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GattMeshIntroBullet(text = stringResource(R.string.gatt_mesh_intro_bullet_all_devices))
                    GattMeshIntroBullet(text = stringResource(R.string.gatt_mesh_intro_bullet_multi_hop))
                    GattMeshIntroBullet(text = stringResource(R.string.gatt_mesh_intro_bullet_privacy))
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ) {
                Text(
                    text = stringResource(
                        R.string.gatt_mesh_intro_encryption_status_template,
                        encryptionStatus
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = dismissForeverChecked,
                    onCheckedChange = { dismissForeverChecked = it }
                )
                Text(
                    text = stringResource(R.string.gatt_mesh_intro_action_dont_show_again),
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clickable { dismissForeverChecked = !dismissForeverChecked },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.gatt_mesh_intro_action_cancel),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { onContinue(dismissForeverChecked) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.gatt_mesh_intro_action_continue),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GattMeshIntroBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        ) {}
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ConnectedPeersBottomSheet(
    peers: List<ConnectedPeerSheetItem>,
    onPeerClick: () -> Unit
) {
    var listVisible by remember { mutableStateOf(false) }
    LaunchedEffect(peers) {
        listVisible = false
        listVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.gatt_mesh_connected_users_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (peers.isEmpty()) {
                        stringResource(R.string.gatt_mesh_connected_users_empty)
                    } else {
                        stringResource(R.string.gatt_mesh_connected_count, peers.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (peers.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(
                    items = peers,
                    key = { _, peer -> peer.key }
                ) { index, peer ->
                    AnimatedVisibility(
                        visible = listVisible,
                        enter = fadeIn(
                            animationSpec = tween(
                                durationMillis = 240,
                                delayMillis = 50 + (index * 35)
                            )
                        ) + slideInVertically(
                            animationSpec = tween(
                                durationMillis = 300,
                                delayMillis = 50 + (index * 35)
                            ),
                            initialOffsetY = { it / 5 }
                        )
                    ) {
                        ConnectedPeerSheetRow(
                            peer = peer,
                            onClick = onPeerClick
                        )
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = stringResource(R.string.gatt_mesh_connected_users_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun ConnectedPeerSheetRow(
    peer: ConnectedPeerSheetItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val verifiedRoleLabel = remember(peer.verificationStatus, peer.verifiedRole) {
        resolveGattMeshVerifiedRoleLabel(peer.verifiedRole, context)
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        )
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(
            displayName = peer.displayName,
            stableKey = peer.key,
            modifier = Modifier.size(30.dp),
            textStyle = MaterialTheme.typography.labelLarge
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = peer.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (
                !peer.isSelf &&
                peer.verificationStatus == GattMeshPeerVerificationStatus.VERIFIED &&
                verifiedRoleLabel != null
            ) {
                GattMeshVerifiedPeerBadge(verifiedRoleLabel = verifiedRoleLabel)
            }
            if (peer.isSelf) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.gatt_mesh_message_info_you),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Text(
                text = peer.secondaryText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    }
}

private data class ConnectedPeerSheetItem(
    val key: String,
    val displayName: String,
    val secondaryText: String,
    val isSelf: Boolean,
    val verificationStatus: GattMeshPeerVerificationStatus,
    val verifiedRole: String?,
)

private const val SELF_CONNECTED_PEER_KEY = "__self__"

@Composable
private fun GattMeshMessageInfoSheet(
    message: MeshChatMessage,
    directPeer: GattMeshConnectedPeer?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    val timestamp = remember(
        message.timestampMillis,
        message.receivedTimestampMillis,
        message.isLocal
    ) {
        formatMessageTimestampLabel(
            formatter = timeFormatter,
            displayTimestampMillis = message.receivedTimestampMillis ?: message.timestampMillis,
            originalTimestampMillis = message.receivedTimestampMillis
                ?.takeIf { receivedAtMillis -> receivedAtMillis > message.timestampMillis }
                ?.let { message.timestampMillis },
            isLocal = message.isLocal
        )
    }
    val messageBody = remember(message.text) {
        val parsedReply = parseReplyMetadata(message.text)
        if (parsedReply == null) {
            message.text
        } else {
            parsedReply.body.takeIf { it.isNotBlank() }
                ?: previewTextForReplyTarget(message.text)
                ?: message.text
        }
    }
    val senderLabel = if (message.isLocal) {
        stringResource(R.string.gatt_mesh_message_info_you)
    } else {
        message.senderLabel?.trim().takeIf { !it.isNullOrBlank() }
            ?: stringResource(R.string.rescue_unknown_user)
    }
    val effectiveLocalStatus = remember(message) {
        resolveGattMeshLocalVisualStatus(message)
    }
    val statusLabelRes = when (effectiveLocalStatus) {
        MeshMessageStatus.QUEUED -> R.string.chat_message_status_pending
        MeshMessageStatus.SENDING -> R.string.chat_message_status_sending
        MeshMessageStatus.SENT -> R.string.chat_message_status_sent
        MeshMessageStatus.DELIVERED -> R.string.chat_message_status_delivered
        MeshMessageStatus.READ -> R.string.chat_message_status_read
        MeshMessageStatus.FAILED -> R.string.chat_message_status_failed
    }
    val statusTint = when (effectiveLocalStatus) {
        MeshMessageStatus.READ -> MaterialTheme.colorScheme.primary
        MeshMessageStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusContainer = when (effectiveLocalStatus) {
        MeshMessageStatus.READ -> MaterialTheme.colorScheme.primaryContainer
        MeshMessageStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val directPeerVerifiedRoleLabel = remember(
        directPeer?.verificationStatus,
        directPeer?.verifiedRole
    ) {
        if (directPeer?.verificationStatus == GattMeshPeerVerificationStatus.VERIFIED) {
            resolveGattMeshVerifiedRoleLabel(directPeer.verifiedRole, context)
        } else {
            null
        }
    }
    val originVerifiedRoleLabel = remember(
        message.originVerifiedRole,
        message.originVerifiedAtMillis
    ) {
        if (!message.isLocal) {
            resolveGattMeshVerifiedRoleLabel(message.originVerifiedRole, context)
        } else {
            null
        }
    }
    val canShowDirectPeerVerifiedFallback = remember(
        message.isLocal,
        message.originVerifiedAtMillis,
        directPeer?.verificationStatus
    ) {
        !message.isLocal &&
            message.originVerifiedAtMillis == null &&
            directPeer?.verificationStatus == GattMeshPeerVerificationStatus.VERIFIED
    }
    val showOriginVerifiedBadge = !message.isLocal &&
        (message.originVerifiedAtMillis != null || canShowDirectPeerVerifiedFallback)
    val effectiveOriginVerifiedRoleLabel = originVerifiedRoleLabel ?: directPeerVerifiedRoleLabel

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
                    GattMeshInfoMetaPill(
                        label = stringResource(R.string.gatt_mesh_message_info_sender),
                        value = senderLabel,
                        modifier = Modifier.weight(1f)
                    )
                    GattMeshInfoMetaPill(
                        label = stringResource(R.string.gatt_mesh_message_info_time),
                        value = timestamp,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (showOriginVerifiedBadge) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.gatt_mesh_message_info_origin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GattMeshVerifiedPeerBadge(verifiedRoleLabel = effectiveOriginVerifiedRoleLabel)
                    }
                }
                if (!message.isLocal && directPeerVerifiedRoleLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.gatt_mesh_message_info_direct_peer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        GattMeshVerifiedPeerBadge(verifiedRoleLabel = directPeerVerifiedRoleLabel)
                    }
                }
            }
        }

        if (message.isLocal) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GattMeshCountBadge(
                    label = stringResource(R.string.gatt_mesh_message_info_sent_to),
                    count = message.sentTo.size,
                    modifier = Modifier.weight(1f)
                )
                GattMeshCountBadge(
                    label = stringResource(R.string.gatt_mesh_message_info_delivered_to),
                    count = message.deliveredTo.size,
                    modifier = Modifier.weight(1f)
                )
                GattMeshCountBadge(
                    label = stringResource(R.string.gatt_mesh_message_info_read_by),
                    count = message.readBy.size,
                    modifier = Modifier.weight(1f)
                )
            }
            GattMeshInfoSection(
                title = stringResource(R.string.gatt_mesh_message_info_sent_to),
                values = message.sentTo
            )
            GattMeshInfoSection(
                title = stringResource(R.string.gatt_mesh_message_info_delivered_to),
                values = message.deliveredTo
            )
            GattMeshInfoSection(
                title = stringResource(R.string.gatt_mesh_message_info_read_by),
                values = message.readBy
            )
        }
    }
}

@Composable
private fun GattMeshInfoMetaPill(
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
private fun GattMeshVerifiedPeerBadge(
    verifiedRoleLabel: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
                tint = Color(0xFF1D9BF0),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(R.string.ble_chat_identity_verified),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            verifiedRoleLabel
                ?.takeIf { it.isNotBlank() }
                ?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
        }
    }
}

private fun resolveGattMeshVerifiedRoleLabel(
    verifiedRole: String?,
    context: android.content.Context
): String? {
    return when (verifiedRole?.trim()?.lowercase(Locale.US)) {
        "admin", "fieldteam" -> BlePeerIdentityUtils.roleLabel(
            BlePeerIdentityUtils.ROLE_RESCUE,
            context
        )

        else -> null
    }
}

private fun resolveGattMeshExpectedRecipientCount(
    message: MeshChatMessage
): Int {
    val receiptTrackedRecipientCount = maxOf(
        message.sentTo.size,
        message.deliveredTo.size,
        message.readBy.size
    )
    return when {
        message.sentTo.isNotEmpty() -> message.sentTo.size
        receiptTrackedRecipientCount > 0 -> receiptTrackedRecipientCount
        else -> 0
    }
}

private fun resolveGattMeshLocalVisualStatus(
    message: MeshChatMessage
): MeshMessageStatus {
    if (!message.isLocal) {
        return message.status
    }
    if (
        message.status == MeshMessageStatus.QUEUED ||
        message.status == MeshMessageStatus.SENDING ||
        message.status == MeshMessageStatus.FAILED
    ) {
        return message.status
    }
    val expectedRecipients = resolveGattMeshExpectedRecipientCount(
        message = message
    )
    return when {
        expectedRecipients > 0 && message.readBy.size >= expectedRecipients -> MeshMessageStatus.READ
        expectedRecipients > 0 && message.deliveredTo.size >= expectedRecipients -> MeshMessageStatus.DELIVERED
        expectedRecipients > 0 &&
            (message.readBy.isNotEmpty() || message.deliveredTo.isNotEmpty()) -> MeshMessageStatus.DELIVERED
        message.readBy.isNotEmpty() -> MeshMessageStatus.READ
        message.deliveredTo.isNotEmpty() -> MeshMessageStatus.DELIVERED
        message.status == MeshMessageStatus.READ -> MeshMessageStatus.READ
        message.status == MeshMessageStatus.DELIVERED -> MeshMessageStatus.DELIVERED
        else -> MeshMessageStatus.SENT
    }
}

@Composable
private fun GattMeshCountBadge(
    label: String,
    count: Int,
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
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GattMeshInfoSection(
    title: String,
    values: List<String>
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = values.size.toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (values.isEmpty()) {
                Text(
                    text = stringResource(R.string.gatt_mesh_message_info_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    values.forEach { value ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(6.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                ) {}
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodySmall,
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
}

@Composable
private fun GattMeshStatusCard(
    uiState: GattMeshViewModel.GattMeshUiState,
    meshDeviceCount: Int,
    modifier: Modifier = Modifier
) {
    val errorMessage = uiState.errorMessage
    val statusText = when {
        !uiState.publicMeshEnabled -> stringResource(R.string.mesh_chat_mode_disabled_notice)
        errorMessage != null -> stringResource(errorMessage)
        uiState.canChat -> stringResource(R.string.gatt_mesh_security_notice)
        uiState.isScanning -> stringResource(R.string.gatt_mesh_waiting_notice)
        else -> stringResource(R.string.gatt_mesh_waiting_notice)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.gatt_mesh_connected_count,
                        meshDeviceCount
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun GattMeshReplyComposerBanner(
    target: MeshChatMessage,
    onDismiss: () -> Unit
) {
    val preview = remember(target.text) {
        previewTextForReplyTarget(target.text)
            ?: target.text.trim()
    }
    val authorLabel = if (target.isLocal) {
        stringResource(R.string.chat_reply_sender_you)
    } else {
        target.senderLabel?.trim().orEmpty()
    }.ifBlank { stringResource(R.string.chat_reply_context_label) }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_replying_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = authorLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_reply_dismiss)
                )
            }
        }
    }
}

@Composable
private fun GattMeshMessageComposer(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSendText: () -> Unit
) {
    val hasText = value.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            enabled = enabled,
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = {
                Text(text = stringResource(R.string.chat_message_placeholder))
            },
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
            keyboardActions = KeyboardActions(
                onSend = {
                    if (enabled && hasText) {
                        onSendText()
                    }
                }
            )
        )
        IconButton(
            onClick = onSendText,
            enabled = enabled && hasText,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.chat_send_message)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GattMeshMessageBubble(
    message: MeshChatMessage,
    timeFormatter: SimpleDateFormat,
    connectedPeers: List<GattMeshConnectedPeer>,
    onReplyRequested: (MeshChatMessage) -> Unit,
    onReplyNavigate: (String) -> Unit,
    onInfoRequested: (MeshChatMessage) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val parsedReply = remember(message.text) { parseReplyMetadata(message.text) }
    val bodyText = remember(message.text, parsedReply) {
        if (parsedReply == null) {
            message.text
        } else {
            parsedReply.body.trim()
        }
    }
    val timestampLabel = remember(
        message.timestampMillis,
        message.receivedTimestampMillis,
        message.isLocal
    ) {
        formatMessageTimestampLabel(
            formatter = timeFormatter,
            displayTimestampMillis = message.receivedTimestampMillis ?: message.timestampMillis,
            originalTimestampMillis = message.receivedTimestampMillis
                ?.takeIf { receivedAtMillis -> receivedAtMillis > message.timestampMillis }
                ?.let { message.timestampMillis },
            isLocal = message.isLocal
        )
    }
    val (bubbleColor, contentColor) = if (message.isLocal) {
        outgoingChatBubbleColors()
    } else {
        incomingChatBubbleColors()
    }
    val remoteDisplayName = message.senderLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.rescue_unknown_user)
    val remoteAvatarStableKey = message.senderLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "unknown-sender"
    val verifiedRoleLabel = remember(
        message.originVerifiedRole,
        message.originVerifiedAtMillis
    ) {
        resolveGattMeshVerifiedRoleLabel(message.originVerifiedRole, context)
    }
    val directPeer = remember(message.sourceAddress, connectedPeers) {
        val normalizedSourceAddress = message.sourceAddress?.trim().orEmpty()
        if (normalizedSourceAddress.isEmpty()) {
            null
        } else {
            connectedPeers.firstOrNull { peer ->
                peer.address.equals(normalizedSourceAddress, ignoreCase = true)
            }
        }
    }
    val isDirectPeerVerifiedSender = remember(
        message.isLocal,
        message.originVerifiedAtMillis,
        directPeer?.address,
        directPeer?.verificationStatus
    ) {
        !message.isLocal &&
            message.originVerifiedAtMillis == null &&
            directPeer?.verificationStatus == GattMeshPeerVerificationStatus.VERIFIED
    }
    val directPeerVerifiedRoleLabel = remember(
        isDirectPeerVerifiedSender,
        directPeer?.verifiedRole
    ) {
        if (isDirectPeerVerifiedSender) {
            resolveGattMeshVerifiedRoleLabel(directPeer?.verifiedRole, context)
        } else {
            null
        }
    }
    val showVerifiedBadge = !message.isLocal &&
        (message.originVerifiedAtMillis != null || isDirectPeerVerifiedSender)
    val effectiveVerifiedRoleLabel = verifiedRoleLabel ?: directPeerVerifiedRoleLabel
    val effectiveLocalStatus = remember(message) {
        resolveGattMeshLocalVisualStatus(message)
    }
    val rowAlignment = if (message.isLocal) Arrangement.End else Arrangement.Start
    var swipeOffset by remember(message.id) { mutableStateOf(0f) }
    var showContextMenu by remember(message.id) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "gattReplySwipeOffset"
    )
    val swipeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = rowAlignment,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!message.isLocal) {
            ContactAvatar(
                displayName = remoteDisplayName,
                stableKey = "gatt-mesh-remote-$remoteAvatarStableKey",
                modifier = Modifier.size(28.dp),
                textStyle = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Box {
            Surface(
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomEnd = if (message.isLocal) 4.dp else 16.dp,
                    bottomStart = if (message.isLocal) 16.dp else 4.dp
                ),
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                    .pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                swipeOffset = (swipeOffset + dragAmount).coerceIn(-200f, 200f)
                            },
                            onDragEnd = {
                                val shouldReply = abs(swipeOffset) > swipeThresholdPx
                                swipeOffset = 0f
                                if (shouldReply) {
                                    onReplyRequested(message)
                                }
                            },
                            onDragCancel = {
                                swipeOffset = 0f
                            }
                        )
                    }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showContextMenu = true }
                    )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!message.isLocal && (!message.senderLabel.isNullOrBlank() || showVerifiedBadge)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!message.senderLabel.isNullOrBlank()) {
                                Text(
                                    text = message.senderLabel,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.82f),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (showVerifiedBadge) {
                                GattMeshVerifiedPeerBadge(verifiedRoleLabel = effectiveVerifiedRoleLabel)
                            }
                        }
                    }
                parsedReply?.let { reply ->
                    GattMeshReplyQuotedPreview(
                        preview = previewTextForReplyTarget(reply.preview) ?: reply.preview,
                        authorLabel = reply.authorLabel,
                        contentColor = contentColor,
                        isLocal = message.isLocal,
                        onClick = reply.targetUuid
                            ?.takeIf { it.isNotBlank() }
                            ?.let { targetUuid ->
                                { onReplyNavigate(targetUuid) }
                            }
                    )
                }
                if (bodyText.isNotBlank()) {
                    Text(
                        text = bodyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                }
                Row(
                    modifier = Modifier.align(if (message.isLocal) Alignment.End else Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timestampLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.7f),
                        textAlign = if (message.isLocal) TextAlign.End else TextAlign.Start
                    )
                    if (message.isLocal) {
                        val (icon, tint, statusLabel) = when (effectiveLocalStatus) {
                            MeshMessageStatus.QUEUED -> Triple(
                                Icons.Outlined.Schedule,
                                contentColor.copy(alpha = 0.62f),
                                R.string.chat_message_status_pending
                            )

                            MeshMessageStatus.SENDING -> Triple(
                                Icons.Filled.MoreVert,
                                contentColor.copy(alpha = 0.62f),
                                R.string.chat_message_status_sending
                            )

                            MeshMessageStatus.FAILED -> Triple(
                                Icons.Filled.Close,
                                MaterialTheme.colorScheme.error,
                                R.string.chat_message_status_failed
                            )

                            MeshMessageStatus.READ -> Triple(
                                Icons.Filled.DoneAll,
                                MaterialTheme.colorScheme.primary,
                                R.string.chat_message_status_read
                            )

                            MeshMessageStatus.DELIVERED -> Triple(
                                Icons.Filled.DoneAll,
                                contentColor.copy(alpha = 0.62f),
                                R.string.chat_message_status_delivered
                            )

                            else -> Triple(
                                Icons.Filled.Done,
                                contentColor.copy(alpha = 0.62f),
                                R.string.chat_message_status_sent
                            )
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = stringResource(statusLabel),
                            tint = tint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
            DropdownMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_action_reply)) },
                    onClick = {
                        showContextMenu = false
                        onReplyRequested(message)
                    }
                )
                DropdownMenuItem(
                    enabled = bodyText.isNotBlank(),
                    text = { Text(stringResource(R.string.chat_action_copy)) },
                    onClick = {
                        showContextMenu = false
                        clipboardManager.setText(AnnotatedString(bodyText))
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_message_copied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gatt_mesh_action_message_info)) },
                    onClick = {
                        showContextMenu = false
                        onInfoRequested(message)
                    }
                )
            }
        }
    }
}

@Composable
private fun GattMeshReplyQuotedPreview(
    preview: String,
    authorLabel: String?,
    contentColor: Color,
    isLocal: Boolean,
    onClick: (() -> Unit)? = null
) {
    val indicatorColor = if (isLocal) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondary
    }
    val backgroundColor = if (isLocal) {
        contentColor.copy(alpha = 0.08f)
    } else {
        contentColor.copy(alpha = 0.14f)
    }
    val title = authorLabel?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.chat_reply_context_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(indicatorColor.copy(alpha = 0.95f))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = indicatorColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
