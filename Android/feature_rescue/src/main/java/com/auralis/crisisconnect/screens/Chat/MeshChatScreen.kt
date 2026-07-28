package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.auralis.crisisconnect.ui.components.AudioMessageCard
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.core.chat.previewTextForReplyTarget
import com.auralis.crisisconnect.core.chat.stripReplyMetadata
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Schedule
import com.auralis.crisisconnect.data.MeshMessageStatus
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.auralis.crisisconnect.service.gattmesh.ptt.PttFloorState
import com.auralis.crisisconnect.service.gattmesh.ptt.PttSessionState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.font.FontFamily
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.service.gattmesh.GattMeshConnectedPeer
import com.auralis.crisisconnect.service.gattmesh.GattMeshPeerVerificationStatus
import com.auralis.crisisconnect.service.gattmesh.MeshDiagnostics
import com.auralis.crisisconnect.ui.components.GroupChatAvatar
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.ui.components.ContactAvatar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshChatScreen(
    navController: NavController,
    onBack: () -> Unit = { navController.navigateUp() }
) {
    val context = LocalContext.current
    val viewModel: MeshChatViewModel = viewModel()
    val meshState by viewModel.meshState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageDraft by viewModel.messageDraft.collectAsStateWithLifecycle()
    val isRecordingVoice by viewModel.isRecordingVoice.collectAsStateWithLifecycle()
    val localDisplayName by viewModel.localDisplayName.collectAsStateWithLifecycle()
    val localAgency by viewModel.localAgency.collectAsStateWithLifecycle()
    val isEnablingMesh by viewModel.isEnablingMesh.collectAsStateWithLifecycle()

    var replyTargetId by remember { mutableStateOf<String?>(null) }
    var infoTargetId by remember { mutableStateOf<String?>(null) }
    val replyTarget = remember(messages, replyTargetId) { messages.firstOrNull { it.id == replyTargetId } }
    val infoTarget = remember(messages, infoTargetId) { messages.firstOrNull { it.id == infoTargetId } }
    val infoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(messages, replyTargetId) {
        if (replyTargetId != null && replyTarget == null) replyTargetId = null
    }
    val canChat = remember(meshState) { MeshChatViewModel.isSecureMeshChatReady(meshState) }
    // Store-and-forward: the composer stays usable whenever the mesh is ON (no connected peer
    // required) so messages can be queued offline and flushed when a peer comes in range.
    val canCompose = meshState.isEnabled
    val listState = rememberLazyListState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showPeersSheet by remember { mutableStateOf(false) }
    val peersSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val connectedPeers = meshState.connectedPeers
    val telsizState by viewModel.telsizState.collectAsStateWithLifecycle()
    var showTelsiz by remember { mutableStateOf(false) }
    val revealTelsizFab = remember { mutableStateOf(false) }
    val telsizPullAccumulator = remember { mutableStateOf(0f) }
    val telsizActive = telsizState.joined || telsizState.participants.isNotEmpty()

    val telsizMicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // Permission just granted — join and open the telsiz so the grant isn't a dead end.
            viewModel.joinTelsiz()
            showTelsiz = true
        } else {
            // After a denial, a false rationale flag means "don't ask again" — re-launching the
            // system dialog would silently do nothing, so guide the user to app settings instead.
            val activity = context as? Activity
            val permanentlyDenied = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                )
            if (permanentlyDenied) {
                Toast.makeText(
                    context,
                    context.getString(R.string.telsiz_permission_settings),
                    Toast.LENGTH_LONG
                ).show()
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.telsiz_permission_needed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val requestJoinTelsiz: () -> Unit = {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) viewModel.joinTelsiz() else telsizMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(telsizState.busyTick) {
        if (telsizState.busyTick > 0) {
            Toast.makeText(context, context.getString(R.string.telsiz_busy), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(telsizState.maxTalkTick) {
        if (telsizState.maxTalkTick > 0) {
            Toast.makeText(context, context.getString(R.string.telsiz_max_talk), Toast.LENGTH_SHORT).show()
        }
    }

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        GroupChatAvatar(
                            modifier = Modifier.size(40.dp),
                            iconSize = 22.dp
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.authority_mesh_chat_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPeersSheet = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.gatt_mesh_connected_users_title)
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
                val imagePickerLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    uri?.let(viewModel::sendImage)
                }
                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.startVoiceRecording()
                    }
                }
                var showAttachMenu by remember { mutableStateOf(false) }
                Column(modifier = Modifier.navigationBarsPadding()) {
                    if (replyTarget != null) {
                        MeshChatReplyComposerBanner(
                            target = replyTarget,
                            onDismiss = { replyTargetId = null }
                        )
                    }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box {
                        FilledTonalIconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.size(44.dp),
                            enabled = canCompose
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.chat_add_attachment)
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false }
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(imageVector = Icons.Filled.Image, contentDescription = null)
                                },
                                text = { Text(stringResource(R.string.mesh_chat_attach_image)) },
                                onClick = {
                                    showAttachMenu = false
                                    imagePickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = messageDraft,
                        onValueChange = viewModel::updateDraft,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        enabled = canCompose && !isRecordingVoice,
                        singleLine = false,
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        placeholder = {
                            Text(
                                text = stringResource(
                                    if (isRecordingVoice) {
                                        R.string.mesh_chat_recording
                                    } else {
                                        R.string.mesh_chat_message_placeholder
                                    }
                                )
                            )
                        }
                    )
                    if (messageDraft.isBlank()) {
                        FilledIconButton(
                            onClick = {
                                if (isRecordingVoice) {
                                    viewModel.stopAndSendVoiceRecording()
                                } else {
                                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            enabled = canCompose,
                            colors = if (isRecordingVoice) {
                                IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                IconButtonDefaults.filledIconButtonColors()
                            }
                        ) {
                            Icon(
                                imageVector = if (isRecordingVoice) Icons.Filled.Stop else Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.mesh_chat_voice_record)
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                viewModel.sendMessage(replyTarget)
                                replyTargetId = null
                            },
                            modifier = Modifier.size(48.dp),
                            enabled = canCompose && messageDraft.isNotBlank()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.chat_send_message)
                            )
                        }
                    }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Pull-down-to-reveal, mirroring MainScreen's "pull to search": accumulate the unconsumed
        // downward overscroll while the chat is at the top (onPostScroll + UserInput), then on
        // release (onPreFling / scroll stop) reveal the round telsiz button if pulled far enough.
        val telsizPullThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
        val telsizPullConnection = remember(listState, telsizPullThresholdPx) {
            object : NestedScrollConnection {
                // Accumulate in onPreScroll (the over-scroll stretch can swallow the delta before
                // onPostScroll runs). canScroll* are the reliable boundary checks. The multiplier is
                // deliberately low so the indicator grows gradually as you pull, rather than snapping.
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        val isAtTop = !listState.canScrollBackward
                        val isAtBottom = !listState.canScrollForward
                        val cap = telsizPullThresholdPx * 1.4f
                        when {
                            isAtTop && available.y > 0f ->
                                telsizPullAccumulator.value =
                                    (telsizPullAccumulator.value + available.y * 0.32f).coerceAtMost(cap)
                            isAtBottom && available.y < 0f ->
                                telsizPullAccumulator.value =
                                    (telsizPullAccumulator.value - available.y * 0.32f).coerceAtMost(cap)
                            kotlin.math.abs(available.y) > 1f -> telsizPullAccumulator.value = 0f
                        }
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (telsizPullAccumulator.value >= telsizPullThresholdPx) {
                        revealTelsizFab.value = true
                    }
                    telsizPullAccumulator.value = 0f
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        // Fallback for slow pulls that end without a fling: when scrolling stops past the threshold.
        LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && telsizPullAccumulator.value >= telsizPullThresholdPx) {
                    revealTelsizFab.value = true
                    telsizPullAccumulator.value = 0f
                }
            }
        }
        // The swipe gesture opens the telsiz directly (the round button below is always available too).
        LaunchedEffect(revealTelsizFab.value) {
            if (revealTelsizFab.value) {
                revealTelsizFab.value = false
                telsizPullAccumulator.value = 0f
                requestJoinTelsiz()
                showTelsiz = true
            }
        }
        Box(modifier = Modifier.fillMaxSize().nestedScroll(telsizPullConnection)) {
            ChatTextureBackground(modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (telsizActive) {
                    TelsizActiveBanner(state = telsizState, onClick = { showTelsiz = true })
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // WhatsApp-style: as you over-pull, slide the whole message list up so a
                        // gap opens below the last message, revealing the telsiz pill in it.
                        // Read in the draw phase so it tracks the gesture without recomposition.
                        .graphicsLayer { translationY = -telsizPullAccumulator.value },
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                if (!meshState.isEnabled) {
                    item(key = "mesh_enable_card") {
                        MeshChatEnableCard(
                            isEnabling = isEnablingMesh,
                            onEnable = viewModel::enableAuthorityMesh
                        )
                    }
                }
                item(key = "mesh_security_notice") {
                    MeshSecurityNoticeCard(canChat = canChat)
                }
                if (BuildConfig.DEBUG) {
                    item(key = "mesh_diagnostics") {
                        val diagnostics by MeshDiagnostics.events.collectAsStateWithLifecycle()
                        MeshChatDiagnosticsCard(
                            lines = diagnostics,
                            onClear = { MeshDiagnostics.clear() }
                        )
                    }
                }
                items(
                    items = messages,
                    key = MeshChatMessage::id
                ) { message ->
                    MeshChatMessageBubble(
                        message = message,
                        timeFormatter = timeFormatter,
                        onReply = { replyTargetId = it.id },
                        onInfo = { infoTargetId = it.id }
                    )
                }
                }
            }
            // WhatsApp-style: over-pull at the bottom to grow a "swipe up for telsiz" indicator
            // below the last message; releasing past the threshold opens the telsiz. The pill is
            // ALWAYS in the tree — its alpha is driven by the pull accumulator in the draw phase
            // (graphicsLayer), so it tracks the gesture in real time without relying on
            // recomposition timing (which was hiding it on quick pulls).
            val telsizReadyToRelease by remember {
                androidx.compose.runtime.derivedStateOf {
                    telsizPullAccumulator.value >= telsizPullThresholdPx
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // The parent Box is fillMaxSize (no innerPadding), so its BottomCenter sits
                    // BEHIND the composer/bottom bar — lift the pill above it by the bottom inset.
                    .padding(bottom = innerPadding.calculateBottomPadding() + 24.dp)
                    .graphicsLayer {
                        alpha = if (telsizActive) {
                            0f
                        } else {
                            (telsizPullAccumulator.value / telsizPullThresholdPx).coerceIn(0f, 1f)
                        }
                    }
            ) {
                TelsizPullIndicator(
                    readyToRelease = telsizReadyToRelease,
                    progressProvider = { telsizPullAccumulator.value / telsizPullThresholdPx }
                )
            }
        }
    }

    if (showTelsiz) {
        TelsizFullScreen(
            state = telsizState,
            onBack = { showTelsiz = false },
            onJoin = requestJoinTelsiz,
            onLeave = { viewModel.leaveTelsiz() },
            onPressTalk = viewModel::pttPressTalk,
            onReleaseTalk = viewModel::pttReleaseTalk
        )
    }

    if (showPeersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPeersSheet = false },
            sheetState = peersSheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        ) {
            MeshConnectedPeersSheet(
                peers = connectedPeers,
                localDisplayName = localDisplayName,
                localAgency = localAgency
            )
        }
    }

    val infoTargetMessage = infoTarget
    if (infoTargetMessage != null) {
        ModalBottomSheet(
            onDismissRequest = { infoTargetId = null },
            sheetState = infoSheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        ) {
            MeshChatMessageInfoSheet(message = infoTargetMessage, timeFormatter = timeFormatter)
        }
    }
}

@Composable
private fun TelsizActiveBanner(state: PttSessionState, onClick: () -> Unit) {
    val targetColor = when (state.floor) {
        PttFloorState.LOCAL_SPEAKING -> MaterialTheme.colorScheme.errorContainer
        PttFloorState.REMOTE_SPEAKING -> MaterialTheme.colorScheme.primaryContainer
        PttFloorState.IDLE -> MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    }
    val barColor by animateColorAsState(targetValue = targetColor, label = "telsizBanner")
    val statusText = when (state.floor) {
        PttFloorState.LOCAL_SPEAKING -> stringResource(R.string.telsiz_you_speaking)
        PttFloorState.REMOTE_SPEAKING ->
            stringResource(R.string.telsiz_speaking, state.speakerName?.takeIf { it.isNotBlank() } ?: "—")
        PttFloorState.IDLE -> stringResource(R.string.telsiz_participants, state.participantCount)
    }
    Surface(color = barColor, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_telsiz),
                contentDescription = stringResource(R.string.telsiz_open),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.telsiz_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (state.floor == PttFloorState.IDLE) {
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(-90f)
                )
            } else {
                // Live indicator that matches the full-screen look while someone is talking.
                SpeakingEqualizer(
                    color = if (state.floor == PttFloorState.LOCAL_SPEAKING) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        }
    }
}

@Composable
private fun TelsizPullIndicator(
    readyToRelease: Boolean,
    progressProvider: () -> Float
) {
    // scale/rotation read the live pull value in the DRAW phase (graphicsLayer), so the pill
    // grows smoothly with the gesture without depending on recomposition. Visibility (alpha) is
    // handled by the always-present parent Box.
    val containerColor by animateColorAsState(
        targetValue = if (readyToRelease) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
        },
        label = "telsizPullContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = if (readyToRelease) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "telsizPullContent"
    )
    // Icon spins up toward the telsiz mark as you pull; flips to the megaphone when ready to release.
    Row(
        modifier = Modifier
            .graphicsLayer {
                val p = progressProvider().coerceIn(0f, 1f)
                val s = 0.85f + 0.15f * p
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (readyToRelease) {
                ImageVector.vectorResource(R.drawable.ic_telsiz)
            } else {
                Icons.Filled.KeyboardArrowUp
            },
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = progressProvider().coerceIn(0f, 1f) * 360f }
        )
        Text(
            text = stringResource(
                if (readyToRelease) R.string.telsiz_release_to_open else R.string.telsiz_pull_to_open
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1
        )
    }
}

/** Animated equalizer bars — a live "audio active" indicator next to a speaker. */
@Composable
private fun SpeakingEqualizer(
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 4
) {
    val transition = rememberInfiniteTransition(label = "telsizEq")
    val bars = (0 until barCount).map { i ->
        transition.animateFloat(
            initialValue = 0.30f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 380 + i * 110, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "telsizEqBar$i"
        )
    }
    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        bars.forEach { bar ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight(bar.value)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/** Concentric pulse rings radiating from the PTT button while audio is live. */
@Composable
private fun PttPulseRings(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "telsizPulse")
    val ringCount = 3
    val phases = (0 until ringCount).map { i ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, delayMillis = i * 650, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "telsizPulseRing$i"
        )
    }
    Canvas(modifier = modifier) {
        val maxRadius = size.minDimension / 2f
        val strokeWidth = 3.dp.toPx()
        phases.forEach { phase ->
            val t = phase.value
            val radius = maxRadius * (0.46f + 0.54f * t)
            val alpha = (1f - t).coerceIn(0f, 1f) * 0.35f
            drawCircle(color = color.copy(alpha = alpha), radius = radius, style = Stroke(width = strokeWidth))
        }
    }
}

/** Small live "● N" chip for the header. */
@Composable
private fun TelsizCountChip(count: Int) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF22C55E)))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Compact headline: who's speaking + equalizer, "you're live", or an idle prompt. Kept short so the
 *  whole control area fits on screen without scrolling. */
@Composable
private fun TelsizStatusHero(state: PttSessionState, accent: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (state.floor) {
            PttFloorState.REMOTE_SPEAKING -> {
                val name = state.speakerName?.takeIf { it.isNotBlank() } ?: "—"
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeakingEqualizer(color = accent)
                    Text(stringResource(R.string.telsiz_status_speaking), style = MaterialTheme.typography.bodyMedium, color = accent)
                }
            }
            PttFloorState.LOCAL_SPEAKING -> {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    Text(stringResource(R.string.telsiz_live), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Text(stringResource(R.string.telsiz_you_speaking), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            PttFloorState.IDLE -> {
                Text(
                    text = if (state.joined) stringResource(R.string.telsiz_idle) else stringResource(R.string.telsiz_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (state.joined) {
                        stringResource(R.string.telsiz_hold_to_talk)
                    } else {
                        stringResource(R.string.telsiz_participants, state.participantCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The big circular hold-to-talk button with depth gradient, press scale, and state-aware icon. */
@Composable
private fun PttTalkButton(
    talking: Boolean,
    remoteSpeaking: Boolean,
    onPressTalk: () -> Unit,
    onReleaseTalk: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (pressed) 0.93f else 1f, label = "telsizPttScale")
    val container = when {
        talking -> MaterialTheme.colorScheme.error
        remoteSpeaking -> MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
        else -> MaterialTheme.colorScheme.primary
    }
    val onContainer = when {
        talking -> MaterialTheme.colorScheme.onError
        remoteSpeaking -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimary
    }
    val brush = Brush.verticalGradient(listOf(lerp(container, Color.White, 0.16f), container))
    Box(
        modifier = Modifier
            .size(168.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(brush)
            .pointerInput(remoteSpeaking) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPressTalk()
                        val released = tryAwaitRelease()
                        onReleaseTalk()
                        pressed = false
                        if (released) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (remoteSpeaking) ImageVector.vectorResource(R.drawable.ic_telsiz) else Icons.Filled.Mic,
            contentDescription = stringResource(R.string.telsiz_hold_to_talk),
            tint = onContainer,
            modifier = Modifier.size(72.dp)
        )
    }
}

@Composable
private fun TelsizFullScreen(
    state: PttSessionState,
    onBack: () -> Unit,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onPressTalk: () -> Unit,
    onReleaseTalk: () -> Unit
) {
    val talking = state.floor == PttFloorState.LOCAL_SPEAKING
    val remoteSpeaking = state.floor == PttFloorState.REMOTE_SPEAKING
    val accent = if (talking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.close)
                    )
                }
                Text(
                    text = stringResource(R.string.telsiz_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (state.joined) {
                    TelsizCountChip(count = state.participantCount)
                }
            }
            if (!state.joined) {
                // Not joined: centered status + a single big "join" affordance — nothing to scroll.
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    TelsizStatusHero(state = state, accent = accent)
                    Spacer(Modifier.height(28.dp))
                    Box(
                        modifier = Modifier
                            .size(168.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(onClick = onJoin),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_telsiz),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = stringResource(R.string.telsiz_join),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            } else {
                // Joined: fixed control block (always on screen), then ONLY the participant list scrolls.
                Spacer(Modifier.height(16.dp))
                TelsizStatusHero(state = state, accent = accent)
                Spacer(Modifier.height(18.dp))
                Box(modifier = Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                    PttPulseRings(
                        active = talking || remoteSpeaking,
                        color = accent,
                        modifier = Modifier.matchParentSize()
                    )
                    PttTalkButton(
                        talking = talking,
                        remoteSpeaking = remoteSpeaking,
                        onPressTalk = onPressTalk,
                        onReleaseTalk = onReleaseTalk
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        talking -> stringResource(R.string.telsiz_release_hint)
                        remoteSpeaking -> stringResource(R.string.telsiz_channel_busy)
                        else -> stringResource(R.string.telsiz_hold_to_talk)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))

                if (state.participants.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.telsiz_members),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(start = 24.dp, bottom = 4.dp)
                    )
                    // The only scrollable region — scrolls only when the channel is crowded.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                    ) {
                        state.participants.forEach { participant ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ContactAvatar(
                                    displayName = participant.displayName,
                                    stableKey = participant.address,
                                    modifier = Modifier.size(34.dp),
                                    textStyle = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = if (participant.isSelf) {
                                        "${participant.displayName} · ${stringResource(R.string.telsiz_self)}"
                                    } else {
                                        participant.displayName
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                participant.agency?.trim()?.takeIf { it.isNotEmpty() }?.let { kurum ->
                                    AgencyBadge(agency = kurum, verified = false)
                                }
                                if (participant.isSpeaking) {
                                    SpeakingEqualizer(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(onClick = onLeave) {
                        Text(
                            text = stringResource(R.string.telsiz_leave),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshConnectedPeersSheet(
    peers: List<GattMeshConnectedPeer>,
    localDisplayName: String,
    localAgency: String?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GroupChatAvatar(modifier = Modifier.size(44.dp), iconSize = 24.dp)
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
                        stringResource(R.string.gatt_mesh_connected_count, peers.size + 1)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val trimmedLocalName = localDisplayName.trim()
        val selfDisplayName = if (trimmedLocalName.isNotEmpty()) {
            stringResource(R.string.gatt_mesh_connected_users_self_format, trimmedLocalName)
        } else {
            stringResource(R.string.gatt_mesh_connected_users_this_device)
        }
        MeshPeerRow(
            name = selfDisplayName,
            stableKey = "self",
            role = null,
            agency = localAgency?.trim()?.takeIf { it.isNotEmpty() },
            verified = false,
            avatarName = trimmedLocalName.ifEmpty {
                stringResource(R.string.gatt_mesh_connected_users_this_device)
            }
        )
        if (peers.isEmpty()) {
            Text(
                text = stringResource(R.string.mesh_chat_waiting_for_peers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            peers.forEach { peer ->
                MeshPeerRow(
                    name = peer.displayName,
                    stableKey = peer.address,
                    role = peer.verifiedRole?.trim()?.takeIf { it.isNotEmpty() },
                    agency = peer.verifiedAgency?.trim()?.takeIf { it.isNotEmpty() },
                    verified = peer.verificationStatus == GattMeshPeerVerificationStatus.VERIFIED
                )
            }
        }
    }
}

/** Verified-institution badge — the kurum (AFAD/FEMA…) shown to the RIGHT of a user, with a check
 *  when the membership is cryptographically verified. */
@Composable
private fun AgencyBadge(agency: String, verified: Boolean) {
    val container = if (verified) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    }
    val content = if (verified) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (verified) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = agency,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1
        )
    }
}

@Composable
private fun MeshPeerRow(
    name: String,
    stableKey: String,
    role: String?,
    agency: String?,
    verified: Boolean,
    avatarName: String = name
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ContactAvatar(
            displayName = avatarName,
            stableKey = stableKey,
            modifier = Modifier.size(40.dp),
            textStyle = MaterialTheme.typography.titleSmall
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            if (!role.isNullOrBlank()) {
                Text(
                    text = role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        // Kurum on the right: a verified institution badge, or a bare check when verified w/o a kurum.
        if (!agency.isNullOrBlank()) {
            AgencyBadge(agency = agency, verified = verified)
        } else if (verified) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun MeshChatImageContent(message: MeshChatMessage) {
    val context = LocalContext.current
    // Decoding the (potentially full-size) image synchronously in composition janked scrolling.
    // Decode off the main thread, downsampled to the bubble's max on-screen size.
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        message.id, message.imageThumbnailName, message.imageFileName
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = message.imageThumbnailName
                ?.let { imageThumbnailFile(context, it) }
                ?.takeIf { it.exists() }
                ?: message.imageFileName
                    ?.let { imageMessageFile(context, it) }
                    ?.takeIf { it.exists() }
            file?.let { decodeSampledImage(it.absolutePath, reqWidth = 660, reqHeight = 780) }
                ?.asImageBitmap()
        }
    }
    val readyBitmap = bitmap
    if (readyBitmap != null) {
        Image(
            bitmap = readyBitmap,
            contentDescription = stringResource(R.string.mesh_chat_attach_image),
            modifier = Modifier
                .widthIn(max = 220.dp)
                .heightIn(max = 260.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = "🖼️",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

/** Decodes [path] with an inSampleSize that targets [reqWidth]x[reqHeight] — bubble-size, not full-size. */
private fun decodeSampledImage(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= reqWidth &&
        bounds.outHeight / (sampleSize * 2) >= reqHeight
    ) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize }
    )
}

@Composable
private fun MeshChatVoiceContent(
    message: MeshChatMessage,
    tint: Color
) {
    val context = LocalContext.current
    val audioUri = remember(message.voiceFileName) {
        message.voiceFileName
            ?.let { voiceMessageFile(context, it) }
            ?.takeIf { it.exists() }
            ?.let { Uri.fromFile(it) }
    }
    if (audioUri != null) {
        // Same polished waveform player the main ChatScreen uses (AudioMessageCard).
        AudioMessageCard(
            uri = audioUri,
            useLiveVisualizer = false,
            modifier = Modifier.fillMaxWidth(),
            initialDurationMillis = message.voiceDurationMillis,
            waveBaseColor = tint.copy(alpha = 0.3f),
            waveActiveColor = if (message.isLocal) {
                MaterialTheme.colorScheme.primary
            } else {
                tint.copy(alpha = 0.95f)
            },
            timeTextColor = tint.copy(alpha = 0.78f),
            controlContainerColor = tint.copy(alpha = if (message.isLocal) 0.16f else 0.22f),
            controlContentColor = tint
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = tint.copy(alpha = 0.7f)
            )
            Text(
                text = stringResource(R.string.chat_voice_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = tint
            )
        }
    }
}

@Composable
private fun MeshChatDiagnosticsCard(lines: List<String>, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Mesh tanılama (debug) • ${lines.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (expanded) {
                    TextButton(onClick = onClear) { Text(text = "Temizle") }
                }
            }
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                        .height(260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        // Newest first so the latest events (incl. the STOP line) are visible first.
                        text = lines.takeLast(45).reversed().joinToString("\n")
                            .ifEmpty { "(henüz olay yok)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        // Authority/rescue mesh — not the public "General chat" notice.
        stringResource(R.string.authority_mesh_security_notice)
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
    timeFormatter: SimpleDateFormat,
    onReply: (MeshChatMessage) -> Unit,
    onInfo: (MeshChatMessage) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val alignment = if (message.isLocal) Alignment.CenterEnd else Alignment.CenterStart
    val (bubbleColor, contentColor) = if (message.isLocal) {
        outgoingChatBubbleColors()
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp) to MaterialTheme.colorScheme.onSurface
    }
    // Same asymmetric bubble as the main ChatScreen: 3 rounded corners + 1 square corner on the
    // bottom toward the sender's side.
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = if (message.isLocal) 4.dp else 16.dp,
        bottomStart = if (message.isLocal) 16.dp else 4.dp
    )
    val remoteDisplayName = message.senderLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.rescue_unknown_user)
    val senderLabel = message.senderLabel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val parsedReply = remember(message.text) { parseReplyMetadata(message.text) }
    val bodyText = remember(message.text, parsedReply) {
        if (parsedReply != null) parsedReply.body.ifBlank { parsedReply.preview } else message.text
    }
    val copyText = if (parsedReply != null) bodyText else message.text

    var showMenu by remember { mutableStateOf(false) }
    var swipeOffset by remember(message.id) { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(targetValue = swipeOffset, label = "meshSwipeReply")
    val swipeThresholdPx = with(density) { 56.dp.toPx() }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Row(
            modifier = Modifier
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .pointerInput(message.id) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            swipeOffset = (swipeOffset + dragAmount).coerceIn(-180f, 180f)
                        },
                        onDragEnd = {
                            if (abs(swipeOffset) > swipeThresholdPx) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onReply(message)
                            }
                            swipeOffset = 0f
                        },
                        onDragCancel = { swipeOffset = 0f }
                    )
                },
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
            Box {
                Column(
                    modifier = Modifier
                        .background(bubbleColor, bubbleShape)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMenu = true
                            }
                        )
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
                    if (parsedReply != null) {
                        MeshChatReplyQuote(
                            preview = parsedReply.preview,
                            authorLabel = parsedReply.authorLabel,
                            contentColor = contentColor,
                            isLocal = message.isLocal
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    when {
                        message.imageFileName != null -> MeshChatImageContent(message = message)
                        message.voiceFileName != null -> MeshChatVoiceContent(
                            message = message,
                            tint = contentColor
                        )
                        else -> Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    Text(
                        text = timeFormatter.format(Date(message.timestampMillis)),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.72f)
                    )
                    if (message.isLocal) {
                        val visualStatus = resolveAuthorityMeshLocalVisualStatus(message)
                        val (statusIcon, statusTint, statusLabelRes) = when (visualStatus) {
                            MeshMessageStatus.QUEUED -> Triple(
                                Icons.Outlined.Schedule,
                                contentColor.copy(alpha = 0.55f),
                                R.string.chat_message_status_pending
                            )
                            MeshMessageStatus.SENDING -> Triple(
                                Icons.Filled.MoreHoriz,
                                contentColor.copy(alpha = 0.55f),
                                R.string.chat_message_status_sending
                            )
                            MeshMessageStatus.FAILED -> Triple(
                                Icons.Filled.Close,
                                MaterialTheme.colorScheme.error,
                                R.string.chat_message_status_failed
                            )
                            MeshMessageStatus.READ -> Triple(
                                Icons.Filled.DoneAll,
                                contentColor,
                                R.string.chat_message_status_read
                            )
                            MeshMessageStatus.DELIVERED -> Triple(
                                Icons.Filled.DoneAll,
                                contentColor.copy(alpha = 0.6f),
                                R.string.chat_message_status_delivered
                            )
                            else -> Triple(
                                Icons.Filled.Done,
                                contentColor.copy(alpha = 0.6f),
                                R.string.chat_message_status_sent
                            )
                        }
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = stringResource(statusLabelRes),
                            tint = statusTint,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_action_reply)) },
                        onClick = {
                            showMenu = false
                            onReply(message)
                        }
                    )
                    DropdownMenuItem(
                        enabled = copyText.isNotBlank(),
                        text = { Text(stringResource(R.string.chat_action_copy)) },
                        onClick = {
                            showMenu = false
                            clipboard.setText(AnnotatedString(copyText))
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
                            showMenu = false
                            onInfo(message)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshChatReplyQuote(
    preview: String,
    authorLabel: String?,
    contentColor: Color,
    isLocal: Boolean
) {
    val indicatorColor = if (isLocal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val title = authorLabel?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_reply_context_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(contentColor.copy(alpha = if (isLocal) 0.10f else 0.14f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(indicatorColor)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = indicatorColor,
                maxLines = 1
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.85f),
                maxLines = 2
            )
        }
    }
}

@Composable
private fun MeshChatReplyComposerBanner(
    target: MeshChatMessage,
    onDismiss: () -> Unit
) {
    val author = if (target.isLocal) {
        stringResource(R.string.chat_reply_sender_you)
    } else {
        target.senderLabel?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.rescue_unknown_user)
    }
    val preview = previewTextForReplyTarget(target.text)
        ?: stringResource(R.string.chat_reply_unknown_placeholder)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp))
            .height(IntrinsicSize.Min)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${stringResource(R.string.chat_reply_context_label)} $author",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MeshChatMessageInfoSheet(
    message: MeshChatMessage,
    timeFormatter: SimpleDateFormat
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.gatt_mesh_action_message_info),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        val visibleBody = stripReplyMetadata(message.text) ?: message.text
        if (visibleBody.isNotBlank()) {
            Text(
                text = visibleBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MeshChatInfoRow(
            label = stringResource(R.string.chat_message_status_sent),
            value = timeFormatter.format(Date(message.timestampMillis))
        )
        if (message.isLocal) {
            MeshChatInfoRow(
                label = stringResource(R.string.chat_message_status_delivered),
                value = message.deliveredTo.size.toString()
            )
            MeshChatInfoRow(
                label = stringResource(R.string.chat_message_status_read),
                value = message.readBy.size.toString()
            )
        }
    }
}

@Composable
private fun MeshChatEnableCard(isEnabling: Boolean, onEnable: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.authority_mesh_enable_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.authority_mesh_enable_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            Button(onClick = onEnable, enabled = !isEnabling) {
                if (isEnabling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.authority_mesh_enable_button))
                }
            }
        }
    }
}

@Composable
private fun MeshChatInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun resolveAuthorityMeshExpectedRecipientCount(message: MeshChatMessage): Int {
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

/** Mirrors GattMeshScreen.resolveGattMeshLocalVisualStatus: lifts a local message to its effective
 *  delivery state from the receipt sets, so the bubble shows sent/delivered/read accurately. */
private fun resolveAuthorityMeshLocalVisualStatus(message: MeshChatMessage): MeshMessageStatus {
    if (!message.isLocal) return message.status
    if (
        message.status == MeshMessageStatus.QUEUED ||
        message.status == MeshMessageStatus.SENDING ||
        message.status == MeshMessageStatus.FAILED
    ) {
        return message.status
    }
    val expectedRecipients = resolveAuthorityMeshExpectedRecipientCount(message)
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
