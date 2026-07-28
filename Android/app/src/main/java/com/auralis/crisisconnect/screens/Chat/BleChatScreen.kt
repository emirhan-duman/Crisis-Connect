@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.with
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.data.BleChatMessage
import com.auralis.crisisconnect.data.BleMessageStatus
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.navigation.ChatSharedElements
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.client.BleClientManager
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import com.auralis.crisisconnect.ui.components.AttachmentAction
import com.auralis.crisisconnect.ui.components.AudioMessageCard
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.WhatsAppAttachmentMenu
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalAnimationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun BleChatScreen(
    navController: NavController,
    sessionCode: String,
    preferredDisplayName: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val viewModel: BleChatViewModel = viewModel()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val contactName by viewModel.contactName.collectAsStateWithLifecycle()
    val contactAvatarVersion by ContactAvatarStorage.observeAvatarVersion(sessionCode)
        .collectAsStateWithLifecycle(initialValue = 0L)
    val contactProfileBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = sessionCode,
        key2 = contactAvatarVersion
    ) {
        value = withContext(Dispatchers.IO) {
            ContactAvatarStorage.loadContactAvatar(context, sessionCode)
        }
    }
    val draft by viewModel.messageDraft.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val peerLastSeenMillis by viewModel.peerLastSeenMillis.collectAsStateWithLifecycle()
    val rescueCall by viewModel.rescueCall.collectAsStateWithLifecycle()
    val serverReady by viewModel.serverReady.collectAsStateWithLifecycle()
    val isRescueUser by viewModel.isRescueUser.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordingFilePath by viewModel.recordingFilePath.collectAsStateWithLifecycle()
    val recordingDuration by viewModel.recordingDuration.collectAsStateWithLifecycle()
    val isSendingVoice by viewModel.isSendingVoice.collectAsStateWithLifecycle()
    val voiceTransfers by viewModel.voiceTransfers.collectAsStateWithLifecycle()
    val isSendingImage by viewModel.isSendingImage.collectAsStateWithLifecycle()
    val imageTransfers by viewModel.imageTransfers.collectAsStateWithLifecycle()
    val isSendingDocument by viewModel.isSendingDocument.collectAsStateWithLifecycle()
    val isContactVerified by viewModel.isContactVerified.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isListDragged by listState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    val messageFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val displayName = remember(contactName, preferredDisplayName, sessionCode) {
        resolveChatDisplayName(
            context = context,
            sessionCode = sessionCode,
            contactName = contactName,
            preferredDisplayName = preferredDisplayName
        )
    }
    // connectionState re-emits with only peerBatteryPercent changed on every peer_info packet;
    // derivedStateOf keeps those battery-only pulses from invalidating the whole screen.
    val isTransportReady by remember {
        derivedStateOf {
            connectionState?.status == BleClientManager.ConnectionStatus.Ready || serverReady
        }
    }
    val counterpartyIsFieldTeam = BlePeerIdentityUtils.isRescuerDisplayName(displayName)
    val showsVerifiedIdentity = !isRescueUser &&
        counterpartyIsFieldTeam &&
        (isTransportReady || isContactVerified)
    val securityNoticeInList = isTransportReady && messages.isNotEmpty()
    val listTailIndex = messages.lastIndex + if (securityNoticeInList) 1 else 0
    val hasRecordedVoice by remember { derivedStateOf { !recordingFilePath.isNullOrBlank() } }
    var infoTargetMessageId by remember { mutableStateOf<String?>(null) }
    val infoTargetMessage = remember(messages, infoTargetMessageId) {
        messages.firstOrNull { it.id == infoTargetMessageId }
    }
    val messageInfoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // voiceTransfers republishes as a new map per received chunk; a plain remember(voiceTransfers)
    // key recomposed the entire screen many times per second during a transfer. derivedStateOf
    // confines that to wherever this value is actually read (the bottom-bar banner).
    val activeIncomingVoiceTransfer by remember {
        derivedStateOf {
            voiceTransfers.values
                .filter { transfer ->
                    transfer.direction == VoiceTransferDirection.Download &&
                        transfer.state != VoiceTransferState.Completed
                }
                .maxByOrNull { transfer -> transfer.confirmedChunks }
        }
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val titleSharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedElement(
                sharedContentState = rememberSharedContentState(
                    key = ChatSharedElements.title(sessionCode)
                ),
                animatedVisibilityScope = animatedVisibilityScope
            )
        }
    } else {
        Modifier
    }
    var pendingImage by remember(sessionCode) { mutableStateOf<PendingImage?>(null) }
    var cameraPhotoFile by remember(sessionCode) { mutableStateOf<File?>(null) }
    var cameraCaptureUri by remember(sessionCode) { mutableStateOf<Uri?>(null) }
    var showAttachmentMenu by remember(sessionCode) { mutableStateOf(false) }
    var fullScreenImageUri by remember(sessionCode) { mutableStateOf<Uri?>(null) }
    var pendingRecordingStart by remember { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingRecordingStart) {
            viewModel.startVoiceRecording()
        } else if (!granted && pendingRecordingStart) {
            Toast.makeText(
                context,
                context.getString(R.string.chat_voice_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingRecordingStart = false
    }
    val ensureMicrophonePermission: (onGranted: () -> Unit) -> Unit = { action ->
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingRecordingStart = true
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun clearPendingImage(deleteTempFile: Boolean = true) {
        val current = pendingImage
        if (deleteTempFile && current?.shouldDeleteOnClear == true) {
            current.filePath?.let { path -> runCatching { File(path).delete() } }
        }
        pendingImage = null
        if (deleteTempFile) {
            cameraPhotoFile?.let { file -> runCatching { file.delete() } }
            cameraPhotoFile = null
            cameraCaptureUri = null
        }
    }
    val pickMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        val prepared = createPendingImageFromUri(context, uri)
        if (prepared == null) {
            clearPendingImage(deleteTempFile = true)
            viewModel.onImageSelectionFailed()
            return@rememberLauncherForActivityResult
        }
        clearPendingImage(deleteTempFile = true)
        pendingImage = prepared
    }
    val pickDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        viewModel.sendDocumentAttachment(uri) { success ->
            if (!success) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_document_send_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    val takePictureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val photoFile = cameraPhotoFile
        val outputUri = cameraCaptureUri
        if (!success || photoFile == null || outputUri == null) {
            photoFile?.delete()
            cameraPhotoFile = null
            cameraCaptureUri = null
            return@rememberLauncherForActivityResult
        }
        val dimensions = decodeImageFileDimensions(photoFile)
        if (dimensions == null) {
            photoFile.delete()
            viewModel.onImageSelectionFailed()
            cameraPhotoFile = null
            cameraCaptureUri = null
            return@rememberLauncherForActivityResult
        }
        clearPendingImage(deleteTempFile = false)
        pendingImage = PendingImage(
            uri = outputUri,
            mimeType = "image/jpeg",
            width = dimensions.first,
            height = dimensions.second,
            filePath = photoFile.absolutePath,
            shouldDeleteOnClear = true
        )
    }
    fun launchCameraCapture() {
        val photoDir = File(context.cacheDir, "camera_images")
        val dirReady = runCatching { photoDir.mkdirs() || photoDir.exists() }.getOrDefault(false)
        if (!dirReady) {
            viewModel.onImageSelectionFailed()
            return
        }
        val photoFile = runCatching {
            File.createTempFile("IMG_", ".jpg", photoDir)
        }.getOrNull()
        val outputUri = photoFile?.let {
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    it
                )
            }.getOrElse {
                photoFile.delete()
                null
            }
        }
        if (photoFile == null || outputUri == null) {
            viewModel.onImageSelectionFailed()
            return
        }
        cameraPhotoFile = photoFile
        cameraCaptureUri = outputUri
        takePictureLauncher.launch(outputUri)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            clearPendingImage(deleteTempFile = true)
            viewModel.onCameraPermissionDenied()
        }
    }
    var pendingLocationPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var isSharingLocation by remember(sessionCode) { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val granted = grantResults[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted && hasPreciseLocationPermission(context)) {
            pendingLocationPermissionAction?.invoke()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.chat_location_precise_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingLocationPermissionAction = null
    }
    val ensurePreciseLocationPermission: (onGranted: () -> Unit) -> Unit = { action ->
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> action()
            hasPreciseLocationPermission(context) -> action()
            else -> {
                pendingLocationPermissionAction = action
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }
    val shareCurrentLocation: () -> Unit = {
        if (isSharingLocation) {
            Toast.makeText(
                context,
                context.getString(R.string.chat_location_fetching),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            ensurePreciseLocationPermission {
                scope.launch {
                    isSharingLocation = true
                    val locationEstimate = fetchCurrentLocationEstimateForChatSend(
                        context = context,
                        bleRssi = null,
                        preferFreshFix = isTransportReady
                    )
                    if (locationEstimate == null) {
                        isSharingLocation = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_location_unavailable),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }
                    if (locationEstimate.source != LOCATION_SOURCE_GPS) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.chat_location_sent_approximate),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    viewModel.sendMessage(buildLocationPayload(locationEstimate)) { success ->
                        isSharingLocation = false
                        if (!success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.chat_location_send_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        }
    }
    val showScrollToBottom by remember(messages, securityNoticeInList, listState) {
        derivedStateOf {
            if (messages.isEmpty()) {
                false
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: return@derivedStateOf false
                lastVisibleIndex < listTailIndex
            }
        }
    }
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsCount = layoutInfo.totalItemsCount
            if (totalItemsCount == 0) {
                true
            } else {
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                val lastItemIndex = totalItemsCount - 1
                val viewportBottom = layoutInfo.viewportEndOffset
                val itemBottom = lastVisibleItem.offset + lastVisibleItem.size
                val tolerance = layoutInfo.afterContentPadding + 4
                val distanceToBottom = viewportBottom - itemBottom
                lastVisibleItem.index == lastItemIndex &&
                    distanceToBottom <= tolerance &&
                    distanceToBottom >= -4
            }
        }
    }
    var isComposerInputFocused by remember { mutableStateOf(false) }
    var wasImeVisible by remember { mutableStateOf(false) }

    DisposableEffect(sessionCode) {
        ActiveChatTracker.setActiveSession(sessionCode)
        onDispose { ActiveChatTracker.clearSession(sessionCode) }
    }

    LaunchedEffect(sessionCode) {
        viewModel.initialize(sessionCode)
    }

    LaunchedEffect(isListDragged, isComposerInputFocused) {
        if (isListDragged && isComposerInputFocused) {
            isComposerInputFocused = false
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }

    LaunchedEffect(messages.size, securityNoticeInList) {
        if (messages.isEmpty()) {
            return@LaunchedEffect
        }
        val targetIndex = listTailIndex
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) {
            listState.scrollToItem(targetIndex)
            return@LaunchedEffect
        }
        val lastVisibleIndex = visibleItems.last().index
        if (lastVisibleIndex < targetIndex - 5) {
            listState.scrollToItem(targetIndex)
        } else {
            listState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(isImeVisible, isComposerInputFocused, messages.size, securityNoticeInList, isAtBottom) {
        if (isImeVisible && !wasImeVisible && isComposerInputFocused && messages.isNotEmpty()) {
            val targetIndex = listTailIndex
            if (!isAtBottom) {
                listState.scrollToItem(targetIndex)
            }
        }
        wasImeVisible = isImeVisible
    }

    LaunchedEffect(messages) {
        viewModel.acknowledgeRemoteMessages()
    }

    LaunchedEffect(messages, infoTargetMessageId) {
        if (infoTargetMessageId != null && infoTargetMessage == null) {
            infoTargetMessageId = null
        }
    }

    // snapshotFlow instead of effect keys: the flags were read at screen-root purely to close the
    // attachment menu, so every toggle recomposed the whole screen. snapshotFlow reads happen
    // outside composition.
    LaunchedEffect(Unit) {
        snapshotFlow {
            isRecording ||
                hasRecordedVoice ||
                isSendingVoice ||
                pendingImage != null ||
                isSendingImage ||
                isSendingDocument ||
                isSharingLocation
        }.collect { busy ->
            if (busy) {
                showAttachmentMenu = false
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContactAvatar(
                            displayName = displayName,
                            stableKey = sessionCode,
                            bitmap = contactProfileBitmap,
                            modifier = Modifier.size(36.dp),
                            textStyle = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = titleSharedModifier,
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (showsVerifiedIdentity) {
                                    BleIdentityVerifiedTitleIcon()
                                }
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BleConnectionStatusBadge(
                                    state = connectionState,
                                    serverReady = serverReady
                                )
                                if (showsVerifiedIdentity) {
                                    BleIdentityVerifiedBadge()
                                }
                                BlePeerBatteryBadge(
                                    state = connectionState,
                                    showForCurrentRole = isRescueUser
                                )
                                val linkAlive = isTransportReady ||
                                    connectionState?.status == BleClientManager.ConnectionStatus.Connected
                                if (!linkAlive) {
                                    peerLastSeenMillis?.let { lastSeenMillis ->
                                        Text(
                                            text = stringResource(
                                                R.string.chat_presence_last_seen,
                                                DateUtils.getRelativeTimeSpanString(lastSeenMillis).toString()
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (isTransportReady && rescueCall == null) {
                        IconButton(onClick = {
                            val micGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!micGranted) {
                                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                viewModel.startRescueCall()
                            }
                        }) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = stringResource(R.string.ble_call_start)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val incomingTransfer = activeIncomingVoiceTransfer
                    if (incomingTransfer != null) {
                        BleVoiceTransferBanner(
                            progress = incomingTransfer,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    MessageComposer(
                        value = draft,
                        onValueChange = viewModel::updateDraft,
                        onInputFocusChanged = { isFocused ->
                            isComposerInputFocused = isFocused
                            if (messages.isNotEmpty()) {
                                val targetIndex = listTailIndex
                                scope.launch {
                                    if (isFocused && !isAtBottom) {
                                        listState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                        },
                        onSendText = { viewModel.sendMessage() },
                        onStartRecording = {
                            ensureMicrophonePermission {
                                val started = viewModel.startVoiceRecording()
                                if (!started) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_voice_recording_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onStopRecording = {
                            val stopped = viewModel.stopVoiceRecording()
                            if (!stopped) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.chat_voice_recording_stop_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDiscardRecording = { viewModel.cancelVoiceRecording() },
                        onSendVoice = {
                            viewModel.sendRecordedVoice { success ->
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_voice_send_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        isRecording = isRecording,
                        hasRecordedVoice = hasRecordedVoice,
                        isSendingVoice = isSendingVoice,
                        recordingDurationMillis = { recordingDuration },
                        canRecordVoice = isTransportReady,
                        onAttachmentClick = {
                            if (!showAttachmentMenu) {
                                isComposerInputFocused = false
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                            showAttachmentMenu = !showAttachmentMenu
                        },
                        onAttachmentMenuDismiss = { showAttachmentMenu = false },
                        isAttachmentMenuVisible = showAttachmentMenu,
                        canAddAttachments = true,
                        pendingImage = pendingImage,
                        onSendImage = {
                            val preview = pendingImage ?: return@MessageComposer
                            viewModel.sendImageAttachment(
                                uri = preview.uri,
                                mimeType = preview.mimeType,
                                width = preview.width,
                                height = preview.height
                            ) { success ->
                                clearPendingImage(deleteTempFile = true)
                                if (!success) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_image_send_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onDiscardImage = { clearPendingImage(deleteTempFile = true) },
                        isSendingImage = isSendingImage,
                        replyToMessage = null,
                        contactName = displayName,
                        onDismissReply = {}
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ChatTextureBackground(
                modifier = Modifier.matchParentSize()
            )

            if (messages.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (isTransportReady) {
                        BleChatSecurityNoticeBubble(
                            isRescueUser = isRescueUser,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.chat_message_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (securityNoticeInList) {
                        item(key = "ble_security_notice", contentType = "ble_notice") {
                            BleChatSecurityNoticeBubble(isRescueUser = isRescueUser)
                        }
                    }
                    items(
                        items = messages,
                        key = BleChatMessage::id,
                        contentType = { "ble_message" }
                    ) { message ->
                        // imageTransfers is republished as a new map on every progress chunk;
                        // a raw read here recomposed every visible bubble per tick. derivedStateOf
                        // keeps the invalidation scoped to the bubble whose entry changed.
                        val imageProgress by remember(message.id, message.messageType, message.isLocal) {
                            derivedStateOf {
                                if (message.messageType == MessageType.IMAGE) {
                                    val direction = if (message.isLocal) {
                                        ImageTransferDirection.Upload
                                    } else {
                                        ImageTransferDirection.Download
                                    }
                                    imageTransfers["${direction.name}:${message.id}"]
                                } else {
                                    null
                                }
                            }
                        }
                        BleChatBubble(
                            message = message,
                            messageFormatter = messageFormatter,
                            imageProgress = imageProgress,
                            onImageClick = { uri -> fullScreenImageUri = uri },
                            onInfoRequested = { selected ->
                                infoTargetMessageId = selected.id
                            }
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showScrollToBottom,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            val lastIndex = listTailIndex
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: 0
                            val distance = (lastIndex - lastVisibleIndex).coerceAtLeast(0)
                            scope.launch {
                                if (distance > 30) {
                                    listState.scrollToItem(lastIndex)
                                } else {
                                    listState.animateScrollToItem(lastIndex)
                                }
                            }
                        },
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 6.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.chat_scroll_to_bottom)
                        )
                    }
                }
            }
        }
    }

    fullScreenImageUri?.let { uri ->
        FullScreenImageViewer(
            uri = uri,
            onDismiss = { fullScreenImageUri = null }
        )
    }

    WhatsAppAttachmentMenu(
        visible = showAttachmentMenu,
        onDismiss = { showAttachmentMenu = false },
        onAction = { action ->
            showAttachmentMenu = false
            when (action) {
                AttachmentAction.Camera -> {
                    val hasCameraPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    if (hasCameraPermission) {
                        launchCameraCapture()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                AttachmentAction.Gallery -> {
                    pickMediaLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }

                AttachmentAction.Document -> {
                    pickDocumentLauncher.launch("*/*")
                }

                AttachmentAction.Location -> {
                    shareCurrentLocation()
                }
            }
        },
        actions = listOf(
            AttachmentAction.Document,
            AttachmentAction.Camera,
            AttachmentAction.Gallery,
            AttachmentAction.Location
        ),
        bottomPadding = 40.dp
    )

    infoTargetMessage?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { infoTargetMessageId = null },
            sheetState = messageInfoSheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            P2pBleMessageInfoSheet(
                message = message,
                conversationDisplayName = displayName,
                onDismiss = { infoTargetMessageId = null }
            )
        }
    }

    rescueCall?.let { call ->
        RescueCallOverlay(
            call = call,
            displayName = displayName,
            onAccept = {
                val micGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (micGranted) {
                    viewModel.acceptRescueCall(call.callId)
                } else {
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onReject = { viewModel.rejectRescueCall(call.callId) },
            onEnd = { viewModel.endRescueCall() },
            onToggleMute = { viewModel.setRescueCallMuted(!call.muted) },
            onToggleSpeaker = { viewModel.toggleRescueCallSpeaker() }
        )
    }
}

@Composable
private fun RescueCallOverlay(
    call: CallUiState,
    displayName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.callId, call.connectedAt) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val stateLabel = when {
        call.state == CallState.InCall -> {
            val connectedAt = call.connectedAt
            if (connectedAt != null) {
                val totalSeconds = ((nowMillis - connectedAt) / 1_000L).coerceAtLeast(0L)
                "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
            } else {
                stringResource(R.string.ble_call_connecting)
            }
        }
        call.state == CallState.Ringing && !call.isOutgoing ->
            stringResource(R.string.ble_call_incoming)
        call.state == CallState.Ringing -> stringResource(R.string.ble_call_ringing)
        else -> stringResource(R.string.ble_call_connecting)
    }
    val incomingRinging = call.state == CallState.Ringing && !call.isOutgoing
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.ble_call_via_bluetooth),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (incomingRinging) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RescueCallActionButton(
                        icon = Icons.Default.CallEnd,
                        label = stringResource(R.string.ble_call_reject),
                        container = MaterialTheme.colorScheme.error,
                        content = MaterialTheme.colorScheme.onError,
                        onClick = onReject
                    )
                    RescueCallActionButton(
                        icon = Icons.Default.Call,
                        label = stringResource(R.string.ble_call_accept),
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        onClick = onAccept
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 48.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RescueCallActionButton(
                        icon = if (call.muted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = stringResource(
                            if (call.muted) R.string.ble_call_unmute else R.string.ble_call_mute
                        ),
                        container = if (call.muted) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        content = MaterialTheme.colorScheme.onSurface,
                        onClick = onToggleMute
                    )
                    RescueCallActionButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = stringResource(R.string.ble_call_speaker),
                        container = if (call.speakerEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        content = MaterialTheme.colorScheme.onSurface,
                        onClick = onToggleSpeaker
                    )
                    RescueCallActionButton(
                        icon = Icons.Default.CallEnd,
                        label = stringResource(R.string.ble_call_end),
                        container = MaterialTheme.colorScheme.error,
                        content = MaterialTheme.colorScheme.onError,
                        onClick = onEnd
                    )
                }
            }
        }
    }
}

@Composable
private fun RescueCallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = container
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier
                    .padding(18.dp)
                    .size(32.dp),
                tint = content
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BleChatSecurityNoticeBubble(
    isRescueUser: Boolean,
    modifier: Modifier = Modifier
) {
    val text = if (isRescueUser) {
        stringResource(R.string.ble_chat_security_notice_rescue)
    } else {
        stringResource(R.string.ble_chat_security_notice_sos)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun BleConnectionStatusBadge(
    state: BleClientManager.ConnectionState?,
    serverReady: Boolean
) {
    val status = state?.status
    val (labelRes, backgroundColor, contentColor) = when {
        serverReady -> Triple(
            R.string.ble_status_ready,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )

        else -> when (status) {
            BleClientManager.ConnectionStatus.Ready -> Triple(
                R.string.ble_status_ready,
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer
            )

            BleClientManager.ConnectionStatus.Connected -> Triple(
                R.string.ble_status_connected,
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer
            )

            BleClientManager.ConnectionStatus.Connecting,
            BleClientManager.ConnectionStatus.Discovering,
            BleClientManager.ConnectionStatus.Authenticating,
            BleClientManager.ConnectionStatus.Reconnecting -> Triple(
                when (status) {
                    BleClientManager.ConnectionStatus.Discovering -> R.string.ble_status_discovering
                    BleClientManager.ConnectionStatus.Authenticating -> R.string.ble_status_authenticating
                    BleClientManager.ConnectionStatus.Reconnecting -> R.string.ble_status_reconnecting
                    else -> R.string.ble_status_connecting
                },
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer
            )

            BleClientManager.ConnectionStatus.Disconnected,
            BleClientManager.ConnectionStatus.Failed -> Triple(
                if (status == BleClientManager.ConnectionStatus.Disconnected) {
                    R.string.ble_status_disconnected
                } else {
                    R.string.ble_status_failed
                },
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer
            )

            null -> Triple(
                R.string.ble_status_connecting,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    val baseLabel = stringResource(id = labelRes)
    val reason = state?.reason?.takeIf {
        status == BleClientManager.ConnectionStatus.Disconnected ||
            status == BleClientManager.ConnectionStatus.Failed
    }
    val text = if (reason.isNullOrBlank()) {
        baseLabel
    } else {
        stringResource(R.string.ble_status_with_reason, baseLabel, reason)
    }

    Surface(
        color = backgroundColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun BlePeerBatteryBadge(
    state: BleClientManager.ConnectionState?,
    showForCurrentRole: Boolean
) {
    val status = state?.status
    val peerBatteryPercent = state?.peerBatteryPercent
    val shouldShowBattery =
        showForCurrentRole &&
            peerBatteryPercent != null &&
            (status == BleClientManager.ConnectionStatus.Connected ||
                status == BleClientManager.ConnectionStatus.Ready)
    if (!shouldShowBattery) {
        return
    }
    val normalized = peerBatteryPercent.coerceIn(0, 100)
    val levelColor = when {
        normalized >= 60 -> Color(0xFF2E7D32)
        normalized >= 30 -> Color(0xFFF9A825)
        else -> Color(0xFFC62828)
    }
    val text = stringResource(R.string.ble_chat_battery_percentage, normalized)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall
            )
            BleBatteryIcon(levelColor = levelColor, percent = normalized)
        }
    }
}

@Composable
private fun BleIdentityVerifiedTitleIcon() {
    Icon(
        imageVector = Icons.Filled.Verified,
        contentDescription = stringResource(R.string.ble_chat_identity_verified),
        tint = Color(0xFF1D9BF0),
        modifier = Modifier.size(16.dp)
    )
}

@Composable
private fun BleIdentityVerifiedBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun BleBatteryIcon(
    levelColor: Color,
    percent: Int
) {
    val fillFraction = when {
        percent <= 0 -> 0f
        percent < 10 -> 0.1f
        else -> percent / 100f
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(10.dp)
                .border(1.dp, levelColor, RoundedCornerShape(2.dp))
                .padding(1.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .height(8.dp)
                    .background(levelColor, RoundedCornerShape(1.dp))
            )
        }
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(6.dp)
                .background(levelColor, RoundedCornerShape(1.dp))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BleChatBubble(
    message: BleChatMessage,
    messageFormatter: SimpleDateFormat,
    imageProgress: ImageTransferProgress?,
    onImageClick: (Uri) -> Unit,
    onInfoRequested: (BleChatMessage) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val (bubbleColor, contentColor) = if (message.isLocal) {
        outgoingChatBubbleColors()
    } else {
        incomingChatBubbleColors()
    }
    val alignment = if (message.isLocal) Arrangement.End else Arrangement.Start
    val formattedTime = remember(
        message.timestampMillis,
        message.originalTimestampMillis,
        message.isLocal
    ) {
        formatMessageTimestampLabel(
            formatter = messageFormatter,
            displayTimestampMillis = message.timestampMillis,
            originalTimestampMillis = message.originalTimestampMillis,
            isLocal = message.isLocal
        )
    }
    val parsedReply = remember(message.text) { parseReplyMetadata(message.text) }
    val displayText = remember(message.text, parsedReply) {
        parsedReply?.body?.takeIf { it.isNotBlank() } ?: message.text
    }
    val isTextMessage = message.messageType == MessageType.TEXT
    var showContextMenu by remember(message.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = alignment
    ) {
        Box {
            Surface(
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showContextMenu = true }
                ),
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomEnd = if (message.isLocal) 4.dp else 16.dp,
                    bottomStart = if (message.isLocal) 16.dp else 4.dp
                ),
                tonalElevation = 1.dp,
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    when (message.messageType) {
                        MessageType.AUDIO -> {
                            BleAudioMessageContent(
                                message = message,
                                contentColor = contentColor
                            )
                        }

                        MessageType.SOS_ALERT,
                        MessageType.TEXT -> {
                            Text(
                                text = displayText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = contentColor
                            )
                        }

                        MessageType.IMAGE -> {
                            ImageMessageContent(
                                message = message.asChatMessage(),
                                contentColor = contentColor,
                                progress = imageProgress,
                                onImageClick = onImageClick
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.align(if (message.isLocal) Alignment.End else Alignment.Start),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            textAlign = if (message.isLocal) TextAlign.End else TextAlign.Start,
                        )
                        if (message.isLocal) {
                            val readIndicatorColor = MaterialTheme.colorScheme.primary
                            val (icon, statusLabel, tint) = when (message.status) {
                                BleMessageStatus.QUEUED -> Triple(
                                    Icons.Outlined.Schedule,
                                    R.string.chat_message_status_pending,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                BleMessageStatus.SENDING -> Triple(
                                    Icons.Filled.MoreVert,
                                    R.string.chat_message_status_sending,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                BleMessageStatus.SENT -> Triple(
                                    Icons.Filled.Done,
                                    R.string.chat_message_status_sent,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                BleMessageStatus.DELIVERED -> Triple(
                                    Icons.Filled.DoneAll,
                                    R.string.chat_message_status_delivered,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                BleMessageStatus.READ -> Triple(
                                    Icons.Filled.DoneAll,
                                    R.string.chat_message_status_read,
                                    readIndicatorColor
                                )

                                BleMessageStatus.FAILED -> Triple(
                                    Icons.Filled.Close,
                                    R.string.chat_message_status_failed,
                                    MaterialTheme.colorScheme.error
                                )
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(id = statusLabel),
                                modifier = Modifier.size(16.dp),
                                tint = tint
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
                    enabled = isTextMessage && displayText.isNotBlank(),
                    text = { Text(stringResource(R.string.chat_action_copy)) },
                    onClick = {
                        showContextMenu = false
                        clipboardManager.setText(AnnotatedString(displayText))
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

private fun BleChatMessage.asChatMessage(): ChatMessage {
    return ChatMessage(
        id = 0L,
        sessionCode = sessionCode,
        messageUuid = id,
        text = text,
        messageType = messageType,
        audioFilePath = audioFilePath,
        audioDurationMillis = audioDurationMillis,
        imageFilePath = imageFilePath,
        imageThumbnailPath = imageThumbnailPath,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageMimeType = imageMimeType,
        isLocal = isLocal,
        isRead = status == BleMessageStatus.READ,
        timestampMillis = timestampMillis,
        originalTimestampMillis = originalTimestampMillis,
        deliveryStatus = when (status) {
            BleMessageStatus.QUEUED -> com.auralis.crisisconnect.data.MessageDeliveryStatus.QUEUED
            BleMessageStatus.SENDING -> com.auralis.crisisconnect.data.MessageDeliveryStatus.SENDING
            BleMessageStatus.SENT -> com.auralis.crisisconnect.data.MessageDeliveryStatus.SENT
            BleMessageStatus.DELIVERED -> com.auralis.crisisconnect.data.MessageDeliveryStatus.DELIVERED
            BleMessageStatus.READ -> com.auralis.crisisconnect.data.MessageDeliveryStatus.READ
            BleMessageStatus.FAILED -> com.auralis.crisisconnect.data.MessageDeliveryStatus.FAILED
        },
        retryCount = retryCount,
        nextRetryAtMillis = nextRetryAtMillis,
        lastError = lastError,
        outboundRoute = outboundRoute
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun BleMessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    onSendText: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onDiscardRecording: () -> Unit,
    onSendVoice: () -> Unit,
    isRecording: Boolean,
    hasRecordedVoice: Boolean,
    isSendingVoice: Boolean,
    recordingDurationMillis: Long
) {
    val durationLabel = remember(recordingDurationMillis) {
        formatDuration(recordingDurationMillis)
    }
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                isRecording -> BleRecordingIndicator(
                    durationLabel = durationLabel,
                    onStop = onStopRecording,
                    onCancel = onDiscardRecording
                )

                hasRecordedVoice || isSendingVoice -> BleRecordingConfirmation(
                    durationLabel = durationLabel,
                    onSendVoice = onSendVoice,
                    onDiscardRecording = onDiscardRecording,
                    isSending = isSendingVoice
                )
            }

            if (!isRecording && !hasRecordedVoice && !isSendingVoice) {
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
                            .height(52.dp)
                            .onFocusChanged { focusState ->
                                onInputFocusChanged(focusState.isFocused)
                            },
                        placeholder = {
                            Text(text = stringResource(R.string.chat_message_placeholder))
                        },
                        singleLine = true,
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
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (hasText) {
                                    onSendText()
                                }
                            }
                        )
                    )
                    IconButton(
                        onClick = {
                            if (hasText) {
                                onSendText()
                            } else {
                                onStartRecording()
                            }
                        },
                        modifier = Modifier.size(52.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        AnimatedContent(
                            targetState = hasText,
                            transitionSpec = {
                                fadeIn(
                                    animationSpec = tween(
                                        durationMillis = 180,
                                        delayMillis = 90
                                    )
                                ) with fadeOut(
                                    animationSpec = tween(durationMillis = 90)
                                ) using SizeTransform(clip = false)
                            },
                            label = "SendVoiceToggle"
                        ) { showSend ->
                            if (showSend) {
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = stringResource(R.string.chat_send_message)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = stringResource(R.string.chat_voice_record)
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
private fun BleRecordingIndicator(
    durationLabel: String,
    onStop: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
                Column {
                    Text(
                        text = stringResource(R.string.chat_voice_recording),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = durationLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onCancel,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_voice_discard)
                )
            }
            IconButton(
                onClick = onStop,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.chat_voice_stop)
                )
            }
        }
    }
}

@Composable
private fun BleRecordingConfirmation(
    durationLabel: String,
    onSendVoice: () -> Unit,
    onDiscardRecording: () -> Unit,
    isSending: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.chat_voice_message_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp
                )
            } else {
                TextButton(onClick = onDiscardRecording) {
                    Text(text = stringResource(R.string.chat_voice_discard))
                }
                Button(
                    onClick = onSendVoice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(text = stringResource(R.string.chat_voice_send))
                }
            }
        }
    }
}

@Composable
private fun BleVoiceTransferBanner(
    progress: VoiceTransferProgress,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null
                )
                Text(
                    text = when (progress.direction) {
                        VoiceTransferDirection.Upload -> stringResource(R.string.chat_voice_message_label)
                        VoiceTransferDirection.Download -> stringResource(R.string.chat_voice_message_label)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            BleVoiceTransferStatus(progress = progress)
        }
    }
}

@Composable
private fun BleVoiceTransferStatus(progress: VoiceTransferProgress?) {
    if (progress == null || progress.state == VoiceTransferState.Completed) {
        return
    }
    val percent = (progress.percentage * 100f).roundToInt().coerceIn(0, 100)
    val remaining = progress.remainingChunks
    val label = when (progress.direction) {
        VoiceTransferDirection.Upload -> stringResource(
            R.string.chat_voice_transfer_sending,
            percent,
            remaining
        )

        VoiceTransferDirection.Download -> stringResource(
            R.string.chat_voice_transfer_receiving,
            percent,
            remaining
        )
    }
    LinearProgressIndicator(
        progress = progress.percentage.coerceIn(0f, 1f),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 4.dp)
    )
    when (progress.state) {
        VoiceTransferState.Verifying -> Text(
            text = stringResource(R.string.chat_voice_transfer_verifying),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )

        VoiceTransferState.Failed -> Text(
            text = stringResource(R.string.chat_voice_transfer_failed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )

        else -> Unit
    }
}

@Composable
private fun BleAudioMessageContent(
    message: BleChatMessage,
    contentColor: Color
) {
    val context = LocalContext.current
    val audioUri = remember(message.audioFilePath) {
        message.audioFilePath
            ?.let { path -> File(path) }
            ?.takeIf { file -> file.exists() }
            ?.let { file -> Uri.fromFile(file) }
    }
    var resolvedDurationMillis by remember(message.audioDurationMillis, audioUri) {
        mutableStateOf(message.audioDurationMillis)
    }

    LaunchedEffect(message.audioDurationMillis, audioUri) {
        if (resolvedDurationMillis == null && audioUri != null) {
            val metadataDuration = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (message.audioFilePath != null) {
                            retriever.setDataSource(message.audioFilePath)
                        } else {
                            retriever.setDataSource(context, audioUri)
                        }
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
            }
            if (metadataDuration != null && metadataDuration > 0L) {
                resolvedDurationMillis = metadataDuration
            }
        }
    }

    if (audioUri == null) {
        Text(
            text = stringResource(R.string.chat_voice_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
        return
    }

    AudioMessageCard(
        uri = audioUri,
        useLiveVisualizer = false,
        modifier = Modifier.fillMaxWidth(),
        initialDurationMillis = resolvedDurationMillis,
        waveBaseColor = contentColor.copy(alpha = 0.3f),
        waveActiveColor = if (message.isLocal) {
            MaterialTheme.colorScheme.primary
        } else {
            contentColor.copy(alpha = 0.95f)
        },
        timeTextColor = contentColor.copy(alpha = 0.78f),
        controlContainerColor = contentColor.copy(alpha = if (message.isLocal) 0.16f else 0.22f),
        controlContentColor = contentColor
    )
}

private fun formatDuration(durationMillis: Long): String {
    val safeMillis = durationMillis.coerceAtLeast(0L)
    val totalSeconds = safeMillis / 1_000L
    val hours = (totalSeconds / 3_600L).toInt()
    val minutes = ((totalSeconds % 3_600L) / 60L).toInt()
    val seconds = (totalSeconds % 60L).toInt()
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
