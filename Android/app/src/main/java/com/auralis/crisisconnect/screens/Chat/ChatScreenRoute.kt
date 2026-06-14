@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.DateFormat as AndroidDateFormat
import android.view.Surface
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.with
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.chat.parseReplyMetadata
import com.auralis.crisisconnect.core.chat.stripReplyMetadata
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.navigation.ChatSharedElements
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.RfcommForegroundService.CallDirection
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.RfcommForegroundService.CallResult
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.ui.components.AttachmentAction
import com.auralis.crisisconnect.ui.components.AudioMessageCard
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.WhatsAppAttachmentMenu
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.coroutines.resume
import android.graphics.Color as AndroidColor

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalSharedTransitionApi::class
)
@Composable
fun ChatScreen(
    navController: NavController,
    sessionCode: String,
    preferredDisplayName: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val viewModel: ChatScreenViewModel = viewModel()
    DisposableEffect(sessionCode) {
        ActiveChatTracker.setActiveSession(sessionCode)
        onDispose { ActiveChatTracker.clearSession(sessionCode) }
    }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isListDragged by listState.interactionSource.collectIsDraggedAsState()
    val messageFormatter = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    val dateHeaderFormatter = remember {
        // Use the locale's preferred ordering rather than a hardcoded
        // "d MMMM" pattern, which rendered as "23 4月" in Japanese
        // (the correct Japanese form is "4月23日"). The "MMMMd" skeleton
        // asks ICU for the best pattern that contains a full month and a
        // day of month; ICU returns "M月d日" for ja, "MMMM d" for en, and
        // "d MMMM" for tr — exactly what we want in each locale.
        val locale = Locale.getDefault()
        val pattern = AndroidDateFormat.getBestDateTimePattern(locale, "MMMMd")
        SimpleDateFormat(pattern, locale)
    }
    val context = LocalContext.current
    val localUserName by getSavedUserName(context).collectAsState(initial = "")
    val localProfileBitmap = remember(context) { ProfileImageStorage.loadProfileImage(context) }
    val contactAvatarVersion by ContactAvatarStorage.observeAvatarVersion(sessionCode)
        .collectAsState(initial = 0L)
    val remoteProfileBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = sessionCode,
        key2 = contactAvatarVersion
    ) {
        value = withContext(Dispatchers.IO) {
            ContactAvatarStorage.loadContactAvatar(context, sessionCode)
        }
    }
    val timelineListOffset = 1
    val contactName by viewModel.contactName.collectAsState()
    val displayName = remember(contactName, preferredDisplayName, sessionCode) {
        resolveChatDisplayName(
            context = context,
            sessionCode = sessionCode,
            contactName = contactName,
            preferredDisplayName = preferredDisplayName
        )
    }
    val messages by viewModel.messages.collectAsState()
    val timelineItems by viewModel.timelineItems.collectAsState()
    val hasSharedLocationMessages = remember(timelineItems) {
        timelineItems.any { item ->
            val message = (item as? ChatTimelineItem.Msg)?.message ?: return@any false
            message.messageType == MessageType.TEXT && parseSharedLocationPayload(message.text) != null
        }
    }
    val latestRemoteSharedLocation = remember(timelineItems) {
        timelineItems
            .asReversed()
            .asSequence()
            .mapNotNull { item -> (item as? ChatTimelineItem.Msg)?.message }
            .filter { message -> !message.isLocal && message.messageType == MessageType.TEXT }
            .mapNotNull { message ->
                val textBody = stripReplyMetadata(message.text)?.takeIf { it.isNotBlank() } ?: message.text
                parseSharedLocationPayload(textBody)
            }
            .firstOrNull()
    }
    val connectionState by viewModel.connectionState.collectAsState()
    val ownLocationSnapshot = rememberOwnLocationSnapshot(
        enabled = hasSharedLocationMessages && hasLocationPermission(context),
        liveTracking = connectionState == ChatConnectionState.Connected
    )
    val signalInfo by viewModel.signalInfo.collectAsState()
    val signalPermissionMissing by viewModel.signalPermissionMissing.collectAsState()
    val isBleFallbackActive by viewModel.isBleFallbackActive.collectAsState()
    val canSendVoiceMessages by viewModel.canSendVoiceMessages.collectAsState()
    val canSendAttachments by viewModel.canSendAttachments.collectAsState()
    val canShareLocation by viewModel.canShareLocation.collectAsState()
    val canPlaceCall by viewModel.canPlaceCall.collectAsState()
    val showCallAction by viewModel.showCallAction.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingFilePath by viewModel.recordingFilePath.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val voiceTransfers by viewModel.voiceTransfers.collectAsState()
    val imageTransfers by viewModel.imageTransfers.collectAsState()
    val isSendingVoice by viewModel.isSendingVoice.collectAsState()
    val isSendingImage by viewModel.isSendingImage.collectAsState()
    val isSendingDocument by viewModel.isSendingDocument.collectAsState()
    val activeCall by viewModel.activeCall.collectAsState()
    var isCallScreenVisible by rememberSaveable { mutableStateOf(false) }
    var messageDraft by rememberSaveable { mutableStateOf("") }
    var showAttachmentMenu by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by rememberSaveable { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var infoTargetMessageUuid by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val availableAttachmentActions = remember(canSendAttachments, canShareLocation) {
        when {
            canSendAttachments -> listOf(
                AttachmentAction.Document,
                AttachmentAction.Camera,
                AttachmentAction.Gallery,
                AttachmentAction.Location
            )

            canShareLocation -> listOf(AttachmentAction.Location)
            else -> emptyList()
        }
    }
    val canOpenAttachmentMenu = availableAttachmentActions.isNotEmpty()
    var pendingUnreadMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val messageLookup = remember(messages) { messages.associateBy(ChatMessage::messageUuid) }
    val infoTargetMessage = remember(messages, infoTargetMessageUuid) {
        messages.firstOrNull { it.messageUuid == infoTargetMessageUuid }
    }
    val messageInfoSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val newMessageCount = pendingUnreadMessageIds.size
    val highlightedMessageIds = remember { mutableStateMapOf<String, Boolean>() }
    var knownMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hasInitializedMessages by remember { mutableStateOf(false) }
    val listTimeZone = remember { TimeZone.getDefault() }
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val trimmedSearchQuery = searchQuery.trim()
    val searchResults = remember(trimmedSearchQuery, showSearchDialog, timelineItems) {
        if (!showSearchDialog || trimmedSearchQuery.isEmpty()) {
            emptyList()
        } else {
            val locale = Locale.getDefault()
            val loweredQuery = trimmedSearchQuery.lowercase(locale)
            timelineItems
                .asSequence()
                .mapNotNull { item -> (item as? ChatTimelineItem.Msg)?.message }
                .filter { message ->
                    val body = stripReplyMetadata(message.text)?.takeIf { it.isNotBlank() } ?: message.text
                    val replyBody = parseReplyMetadata(message.text)?.body.orEmpty()
                    val haystack = "$body $replyBody".lowercase(locale)
                    haystack.contains(loweredQuery)
                }
                .toList()
        }
    }
    val navigateToMessage: (String) -> Unit = remember(
        timelineItems,
        listState,
        scope,
        context
    ) {
        { targetUuid ->
            val timelineIndex = timelineItems.indexOfFirst { item ->
                (item as? ChatTimelineItem.Msg)?.message?.messageUuid == targetUuid
            }
            if (timelineIndex >= 0) {
                val targetIndex = timelineIndex + timelineListOffset
                scope.launch {
                    listState.animateScrollToItem(targetIndex)
                    highlightedMessageIds[targetUuid] = true
                    delay(1400)
                    highlightedMessageIds.remove(targetUuid)
                }
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_reply_message_not_found),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(messages, infoTargetMessageUuid) {
        if (infoTargetMessageUuid != null && infoTargetMessage == null) {
            infoTargetMessageUuid = null
        }
    }

    LaunchedEffect(activeCall?.callId) {
        val call = activeCall
        if (call == null || call.state == CallState.Ended) {
            isCallScreenVisible = false
        } else {
            isCallScreenVisible = true
        }
    }

    LaunchedEffect(activeCall?.state) {
        val call = activeCall
        if (call == null || call.state == CallState.Ended) {
            isCallScreenVisible = false
        }
    }
    val shouldUseCallProximity = activeCall?.state == CallState.InCall &&
        activeCall?.currentRoute == CallAudioRoute.Earpiece &&
        isCallScreenVisible
    val isCallProximityNear = rememberCallProximityScreenOff(
        enabled = shouldUseCallProximity
    )
    var pendingImage by remember { mutableStateOf<PendingImage?>(null) }
    var cameraPhotoFile by remember { mutableStateOf<File?>(null) }
    var cameraCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var fullScreenImageUri by remember { mutableStateOf<Uri?>(null) }
    val clearPendingImage: (Boolean) -> Unit = remember {
        { deleteFile ->
            val current = pendingImage
            val shouldDelete = deleteFile || current?.shouldDeleteOnClear == true
            if (shouldDelete) {
                current?.filePath?.let { runCatching { File(it).delete() } }
            }
            pendingImage = null
            cameraPhotoFile = null
            cameraCaptureUri = null
        }
    }

    if (showSearchDialog) {
        SearchMessagesDialog(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            results = searchResults,
            onResultClick = { message ->
                showSearchDialog = false
                navigateToMessage(message.messageUuid)
            },
            onDismiss = {
                showOverflowMenu = false
                showSearchDialog = false
            }
        )
    }

    LaunchedEffect(isBleFallbackActive) {
        if (isBleFallbackActive && showSearchDialog) {
            showSearchDialog = false
        }
    }

    LaunchedEffect(canSendAttachments) {
        if (!canSendAttachments) {
            showAttachmentMenu = false
        }
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val preparedImage = createPendingImageFromUri(context, uri)
            if (preparedImage != null) {
                clearPendingImage(false)
                pendingImage = preparedImage
            } else {
                viewModel.onImageSelectionFailed()
            }
        }
    }
    val pickDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
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
        val (width, height) = decodeImageFileDimensions(photoFile)
            ?: run {
                photoFile.delete()
                viewModel.onImageSelectionFailed()
                cameraPhotoFile = null
                cameraCaptureUri = null
                return@rememberLauncherForActivityResult
            }
        clearPendingImage(false)
        pendingImage = PendingImage(
            uri = outputUri,
            mimeType = "image/jpeg",
            width = width,
            height = height,
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
                val authority = "${context.packageName}.fileprovider"
                FileProvider.getUriForFile(
                    context,
                    authority,
                    it
                )
            }.getOrElse {
                photoFile.delete()
                null
            }
        }

        if (outputUri != null && photoFile != null) {
            cameraPhotoFile = photoFile
            cameraCaptureUri = outputUri
            takePictureLauncher.launch(outputUri)
        } else {
            viewModel.onImageSelectionFailed()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            clearPendingImage(true)
            viewModel.onCameraPermissionDenied()
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
    val showScrollToBottom by remember {
        derivedStateOf {
            if (timelineItems.isEmpty()) {
                false
            } else {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: return@derivedStateOf false
                lastVisibleIndex < (timelineItems.lastIndex + timelineListOffset)
            }
        }
    }
    var isComposerInputFocused by remember { mutableStateOf(false) }
    var wasImeVisible by remember { mutableStateOf(false) }

    var pendingAudioPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingAudioPermissionAction?.invoke()
        } else {
            viewModel.onRecordingPermissionDenied()
        }
        pendingAudioPermissionAction = null
    }
    val ensureMicrophonePermission: (onGranted: () -> Unit) -> Unit = { action ->
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> action()
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> action()
            else -> {
                pendingAudioPermissionAction = action
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    var pendingLocationPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var isSharingLocation by remember { mutableStateOf(false) }
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
    val hasRecordedVoice = !recordingFilePath.isNullOrEmpty()
    LaunchedEffect(
        isRecording,
        hasRecordedVoice,
        isSendingVoice,
        pendingImage,
        isSendingImage,
        isSharingLocation,
        isSendingDocument
    ) {
        if (
            isRecording ||
            hasRecordedVoice ||
            isSendingVoice ||
            pendingImage != null ||
            isSendingImage ||
            isSharingLocation ||
            isSendingDocument
        ) {
            showAttachmentMenu = false
        }
    }
    val startVoiceRecording: () -> Unit = {
        ensureMicrophonePermission {
            viewModel.startVoiceRecording()
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
                        bleRssi = signalInfo?.rssi,
                        preferFreshFix = connectionState == ChatConnectionState.Connected
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
                    val locationMessage = buildLocationPayload(locationEstimate)
                    viewModel.sendMessage(locationMessage) { success ->
                        isSharingLocation = false
                        if (!success) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.chat_location_send_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            viewModel.shareOfflineMapBundleForLocation(
                                latitude = locationEstimate.location.latitude,
                                longitude = locationEstimate.location.longitude
                            )
                        }
                    }
                }
            }
        }
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

    LaunchedEffect(timelineItems.size) {
        if (timelineItems.isEmpty()) {
            return@LaunchedEffect
        }

        val targetIndex = timelineItems.lastIndex + timelineListOffset
        val visibleItems = listState.layoutInfo.visibleItemsInfo

        if (visibleItems.isEmpty()) {
            listState.scrollToItem(targetIndex)
            return@LaunchedEffect
        }

        val firstVisibleIndex = visibleItems.first().index
        val lastVisibleIndex = visibleItems.last().index
        val isNearBottom = lastVisibleIndex >= targetIndex - 1

        if (!isNearBottom) {
            listState.scrollToItem(targetIndex)
        } else {
            val hopDistance = targetIndex - firstVisibleIndex
            if (hopDistance <= 5) {
                listState.animateScrollToItem(targetIndex)
            } else {
                listState.scrollToItem(targetIndex)
            }
        }
    }

    LaunchedEffect(isImeVisible, isComposerInputFocused, timelineItems.size, isAtBottom) {
        if (isImeVisible && !wasImeVisible && isComposerInputFocused && timelineItems.isNotEmpty()) {
            val targetIndex = timelineItems.lastIndex + timelineListOffset
            if (!isAtBottom) {
                listState.scrollToItem(targetIndex)
            }
        }
        wasImeVisible = isImeVisible
    }

    LaunchedEffect(messages) {
        val currentIds = messages.map { it.messageUuid }
        val currentSet = currentIds.toSet()

        val newIds = if (hasInitializedMessages) {
            currentSet - knownMessageIds
        } else {
            emptySet()
        }

        knownMessageIds = currentSet
        if (!hasInitializedMessages) {
            hasInitializedMessages = true
        }

        if (newIds.isNotEmpty() && !isAtBottom) {
            val unreadIncomingIds = newIds.mapNotNull { id ->
                messageLookup[id]?.takeIf { !it.isLocal && !it.isRead }?.messageUuid
            }
            if (unreadIncomingIds.isNotEmpty()) {
                pendingUnreadMessageIds = pendingUnreadMessageIds + unreadIncomingIds
            }
        }

        val stillUnread = pendingUnreadMessageIds.filter { id ->
            val message = messageLookup[id]
            message != null && !message.isRead
        }.toSet()
        if (stillUnread.size != pendingUnreadMessageIds.size) {
            pendingUnreadMessageIds = stillUnread
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom && pendingUnreadMessageIds.isNotEmpty()) {
            pendingUnreadMessageIds = emptySet()
        }
    }

    LaunchedEffect(messages, listState) {
        fun currentVisibleUnread(): Set<String> =
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                val key = info.key as? String ?: return@mapNotNull null
                messageLookup[key]
            }
                .filter { !it.isLocal && !it.isRead }
                .map { it.messageUuid }
                .toSet()

        snapshotFlow { currentVisibleUnread() }
            .distinctUntilChanged()
            .collectLatest { visibleUnread ->
                if (visibleUnread.isEmpty()) {
                    return@collectLatest
                }
                delay(350)
                val stillVisible = currentVisibleUnread()
                val toMark = visibleUnread.intersect(stillVisible)
                if (toMark.isNotEmpty()) {
                    viewModel.onMessagesVisible(toMark)
                }
            }
    }

    LaunchedEffect(errorMessage) {
        if (!errorMessage.isNullOrEmpty()) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
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

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .imePadding(),
            topBar = {
                val minimizedCall = activeCall?.takeIf { call ->
                    !isCallScreenVisible &&
                        call.state != CallState.Idle &&
                        call.state != CallState.Ended
                }
                Column {
                    TopAppBar(
                        title = {
                            Row(
                                modifier = Modifier.clickable {
                                    val encodedCode = Uri.encode(sessionCode)
                                    navController.navigate("chat/$encodedCode/details")
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ContactAvatar(
                                    displayName = displayName,
                                    stableKey = sessionCode,
                                    bitmap = remoteProfileBitmap,
                                    modifier = Modifier.size(40.dp),
                                    textStyle = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = titleSharedModifier
                                    )
                                    ConnectionStatusBadge(connectionState)
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            val isConnected = connectionState == ChatConnectionState.Connected
                            val hasActiveCall = activeCall?.let { call ->
                                call.state != CallState.Idle && call.state != CallState.Ended
                            } == true
                            val phoneIconTint = if (canPlaceCall) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }

                            if (!hasActiveCall && showCallAction) {
                                IconButton(
                                    onClick = {
                                        ensureMicrophonePermission {
                                            viewModel.startCall()
                                        }
                                    },
                                    enabled = canPlaceCall
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Phone,
                                        contentDescription = stringResource(R.string.chat_call_contact),
                                        tint = phoneIconTint
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.chat_more_options)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_search_messages)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Search,
                                                contentDescription = null
                                            )
                                        },
                                        enabled = !isBleFallbackActive,
                                        onClick = {
                                            showOverflowMenu = false
                                            showSearchDialog = true
                                        }
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
                    AnimatedVisibility(
                        visible = minimizedCall != null,
                        enter = slideInVertically(
                            animationSpec = tween(durationMillis = 220),
                            initialOffsetY = { -it / 2 }
                        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                        exit = fadeOut(animationSpec = tween(durationMillis = 140))
                    ) {
                        minimizedCall?.let { call ->
                            ChatCallStatusBar(
                                call = call,
                                contactName = displayName,
                                avatarStableKey = sessionCode,
                                avatarBitmap = remoteProfileBitmap,
                                onOpen = { isCallScreenVisible = true },
                                onAccept = {
                                    ensureMicrophonePermission {
                                        viewModel.acceptCall(call.callId)
                                    }
                                },
                                onReject = { viewModel.rejectCall(call.callId) },
                                onHangup = { viewModel.hangupCall(call.callId) }
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                val replySenderYouLabel = stringResource(R.string.chat_reply_sender_you)
                Box(
                    Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    MessageComposer(
                        value = messageDraft,
                        onValueChange = { messageDraft = it },
                        onInputFocusChanged = { isFocused ->
                            isComposerInputFocused = isFocused
                            if (timelineItems.isNotEmpty()) {
                                val targetIndex = timelineItems.lastIndex + timelineListOffset
                                scope.launch {
                                    if (isFocused && !isAtBottom) {
                                        listState.scrollToItem(targetIndex)
                                    }
                                }
                            }
                        },
                        onSendText = {
                            val trimmed = messageDraft.trim()
                            if (trimmed.isNotEmpty()) {
                                val replyAuthorLabel = replyTarget?.let { target ->
                                    if (target.isLocal) {
                                        replySenderYouLabel
                                    } else {
                                        displayName
                                    }
                                }
                                val outgoingText = buildReplyFormattedMessage(
                                    context = context,
                                    body = trimmed,
                                    replyTo = replyTarget,
                                    replyAuthorLabel = replyAuthorLabel
                                )
                                viewModel.sendMessage(outgoingText) { success ->
                                    if (success) {
                                        messageDraft = ""
                                        replyTarget = null
                                    }
                                }
                            }
                        },
                        onStartRecording = startVoiceRecording,
                        onStopRecording = { viewModel.stopVoiceRecording() },
                        onDiscardRecording = { viewModel.cancelVoiceRecording() },
                        onSendVoice = {
                            if (canSendVoiceMessages) {
                                viewModel.sendRecordedVoice { }
                            }
                        },
                        isRecording = isRecording,
                        hasRecordedVoice = hasRecordedVoice,
                        isSendingVoice = isSendingVoice,
                        recordingDurationMillis = recordingDuration,
                        canRecordVoice = canSendVoiceMessages,
                        onAttachmentClick = {
                            if (!canOpenAttachmentMenu) {
                                return@MessageComposer
                            }
                            if (!showAttachmentMenu) {
                                isComposerInputFocused = false
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                            }
                            showAttachmentMenu = !showAttachmentMenu
                        },
                        onAttachmentMenuDismiss = { showAttachmentMenu = false },
                        isAttachmentMenuVisible = showAttachmentMenu && canOpenAttachmentMenu,
                        canAddAttachments = canOpenAttachmentMenu,
                        pendingImage = pendingImage,
                        onSendImage = {
                            if (!canSendAttachments || isSendingImage) {
                                return@MessageComposer
                            }
                            val image = pendingImage ?: return@MessageComposer
                            viewModel.sendImageAttachment(
                                image.uri,
                                image.mimeType,
                                image.width,
                                image.height
                            ) { success ->
                                if (success) {
                                    image.filePath?.let { runCatching { File(it).delete() } }
                                    clearPendingImage(false)
                                }
                            }
                        },
                        onDiscardImage = { clearPendingImage(true) },
                        isSendingImage = isSendingImage,
                        replyToMessage = replyTarget,
                        contactName = displayName,
                        onDismissReply = { replyTarget = null }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                ChatTextureBackground(
                    modifier = Modifier.matchParentSize()
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    state = listState
                ) {
                    item(
                        key = "chat_e2ee_notice",
                        contentType = "system_notice"
                    ) {
                        ChatEncryptionNoticeCard()
                    }
                    itemsIndexed(
                        timelineItems,
                        key = { _, item ->
                            when (item) {
                                is ChatTimelineItem.Msg -> item.message.messageUuid
                                is ChatTimelineItem.Call -> "call:${item.event.id}"
                            }
                        },
                        contentType = { _, item ->
                            when (item) {
                                is ChatTimelineItem.Msg -> {
                                    when (item.message.messageType) {
                                        MessageType.TEXT -> "msg_text"
                                        MessageType.AUDIO -> "msg_audio"
                                        MessageType.IMAGE -> "msg_image"
                                    }
                                }
                                is ChatTimelineItem.Call -> "call_event"
                            }
                        }
                    ) { index, item ->
                        val showDateHeader = index == 0 || !isSameLocalDay(
                            previousTimestamp = timelineItems[index - 1].timestampMillis,
                            currentTimestamp = item.timestampMillis,
                            timeZone = listTimeZone
                        )
                        if (showDateHeader) {
                            DateHeader(date = dateHeaderFormatter.format(Date(item.timestampMillis)))
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        when (item) {
                            is ChatTimelineItem.Msg -> {
                                val message = item.message
                                ChatBubble(
                                    message = message,
                                    messageFormatter = messageFormatter,
                                    voiceProgress = voiceTransfers[message.messageUuid],
                                    imageProgress = imageTransfers[message.messageUuid],
                                    onImageClick = { uri -> fullScreenImageUri = uri },
                                    onReply = { replyTarget = it },
                                    onInfoRequested = { selected ->
                                        infoTargetMessageUuid = selected.messageUuid
                                    },
                                    onReplyNavigate = navigateToMessage,
                                    isBluetoothConnected = connectionState == ChatConnectionState.Connected,
                                    bluetoothSignalInfo = signalInfo,
                                    signalPermissionMissing = signalPermissionMissing,
                                    conversationDisplayName = displayName,
                                    conversationStableKey = sessionCode,
                                    localUserDisplayName = localUserName,
                                    localProfileBitmap = localProfileBitmap,
                                    remoteProfileBitmap = remoteProfileBitmap,
                                    currentOwnLocation = ownLocationSnapshot,
                                    latestRemoteSharedLocation = latestRemoteSharedLocation,
                                    highlight = highlightedMessageIds[message.messageUuid] == true
                                )
                            }

                            is ChatTimelineItem.Call -> {
                                CallEventRow(
                                    event = item.event,
                                    messageFormatter = messageFormatter
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showScrollToBottom,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    ScrollToBottomButton(
                        count = newMessageCount,
                        onClick = {
                            if (timelineItems.isNotEmpty()) {
                                val lastIndex = timelineItems.lastIndex + timelineListOffset
                                val visible = listState.layoutInfo.visibleItemsInfo
                                val lastVisibleIndex = visible.lastOrNull()?.index ?: 0
                                val distance = (lastIndex - lastVisibleIndex).coerceAtLeast(0)
                                scope.launch {
                                    if (distance > 30) {
                                        listState.scrollToItem(lastIndex)
                                    } else {
                                        listState.animateScrollToItem(lastIndex)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            fullScreenImageUri?.let { uri ->
                FullScreenImageViewer(
                    uri = uri,
                    onDismiss = { fullScreenImageUri = null }
                )
            }

        }
        WhatsAppAttachmentMenu(
            visible = showAttachmentMenu && canOpenAttachmentMenu,
            onDismiss = { showAttachmentMenu = false },
            onAction = { action ->
                showAttachmentMenu = false
                when (action) {
                    AttachmentAction.Document -> {
                        pickDocumentLauncher.launch("*/*")
                    }

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

                    AttachmentAction.Location -> {
                        shareCurrentLocation()
                    }
                }
            },
            actions = availableAttachmentActions,
            bottomPadding = 40.dp
        )
    }

    infoTargetMessage?.let { message ->
        ModalBottomSheet(
            onDismissRequest = { infoTargetMessageUuid = null },
            sheetState = messageInfoSheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            P2pChatMessageInfoSheet(
                message = message,
                conversationDisplayName = displayName,
                onDismiss = { infoTargetMessageUuid = null }
            )
        }
    }

    activeCall?.takeIf { it.state != CallState.Ended && isCallScreenVisible }?.let { callState ->
        CallOverlay(
            modifier = Modifier.fillMaxSize(),
            call = callState,
            contactName = displayName,
            avatarStableKey = sessionCode,
            avatarBitmap = remoteProfileBitmap,
            onAccept = {
                ensureMicrophonePermission {
                    viewModel.acceptCall(callState.callId)
                }
            },
            onReject = { viewModel.rejectCall(callState.callId) },
            onHangup = { viewModel.hangupCall(callState.callId) },
            onToggleMute = { viewModel.setMicMuted(it) },
            onSelectAudioRoute = { viewModel.setAudioRoute(it) },
            onMinimize = { isCallScreenVisible = false }
        )
        if (shouldUseCallProximity && isCallProximityNear) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                            }
                        }
                    }
            )
        }
    }
}
