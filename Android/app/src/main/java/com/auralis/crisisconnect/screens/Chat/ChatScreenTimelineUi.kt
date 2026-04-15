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
import android.view.Surface
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Done
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
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
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

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ChatBubble(
    message: ChatMessage,
    messageFormatter: SimpleDateFormat,
    voiceProgress: VoiceTransferProgress?,
    imageProgress: ImageTransferProgress?,
    onImageClick: (Uri) -> Unit,
    onReply: (ChatMessage) -> Unit,
    onInfoRequested: (ChatMessage) -> Unit,
    onReplyNavigate: (String) -> Unit,
    isBluetoothConnected: Boolean,
    bluetoothSignalInfo: SignalStrengthInfo?,
    signalPermissionMissing: Boolean,
    conversationDisplayName: String,
    conversationStableKey: String,
    localUserDisplayName: String,
    localProfileBitmap: Bitmap?,
    remoteProfileBitmap: Bitmap?,
    currentOwnLocation: Location?,
    latestRemoteSharedLocation: SharedLocationPayload?,
    highlight: Boolean
) {
    val (bubbleColor, contentColor) = if (message.isLocal) {
        outgoingChatBubbleColors()
    } else {
        incomingChatBubbleColors()
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomEnd = if (message.isLocal) 4.dp else 16.dp,
        bottomStart = if (message.isLocal) 16.dp else 4.dp
    )
    val highlightColor = if (highlight) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val parsedReply = remember(message.text) { parseReplyMetadata(message.text) }
    val formattedTimestamp = remember(
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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val displayText = parsedReply?.body ?: message.text
    val sharedLocation = remember(displayText) { parseSharedLocationPayload(displayText) }
    val sharedFile = remember(displayText) { parseSharedFilePayload(displayText) }
    val shouldShowLiveLocation = message.isLocal &&
        isBluetoothConnected &&
        currentOwnLocation != null
    val locationPayloadForDisplay = remember(
        sharedLocation,
        shouldShowLiveLocation,
        currentOwnLocation?.latitude,
        currentOwnLocation?.longitude,
        currentOwnLocation?.accuracy,
        currentOwnLocation?.time
    ) {
        if (!shouldShowLiveLocation || sharedLocation == null) {
            sharedLocation
        } else {
            val liveLocation = currentOwnLocation ?: return@remember sharedLocation
            val liveAccuracy = liveLocation.accuracy
                .takeIf { it > 0f && it.isFinite() }
            sharedLocation.copy(
                latitude = liveLocation.latitude,
                longitude = liveLocation.longitude,
                accuracyMeters = liveAccuracy,
                timestampMillis = liveLocation.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                confidenceRadiusMeters = liveAccuracy,
                source = LOCATION_SOURCE_GPS
            )
        }
    }
    var showContextMenu by remember { mutableStateOf(false) }
    var showLocationDialog by remember(message.messageUuid) { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val granted = grantResults[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grantResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            showLocationDialog = true
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.chat_location_permission_required),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    var swipeOffset by remember { mutableStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = swipeOffset,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "replySwipeOffset"
    )
    val swipeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val isTextMessage = message.messageType == MessageType.TEXT
    val openLocationComparison: () -> Unit = openLocationComparison@{
        if (sharedLocation == null) {
            return@openLocationComparison
        }
        if (hasLocationPermission(context)) {
            showLocationDialog = true
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }
    val proximityPayload = remember(message.isLocal, sharedLocation, latestRemoteSharedLocation) {
        if (message.isLocal) {
            latestRemoteSharedLocation
        } else {
            sharedLocation
        }
    }
    val bubbleDistanceText = remember(
        proximityPayload,
        currentOwnLocation,
        context,
        isBluetoothConnected,
        bluetoothSignalInfo
    ) {
        val payload = proximityPayload ?: return@remember null
        val ownLocation = currentOwnLocation ?: return@remember null
        buildDistanceRangeText(
            context = context,
            ownLocation = ownLocation,
            payload = payload,
            isBluetoothConnected = isBluetoothConnected,
            bluetoothSignalInfo = bluetoothSignalInfo
        )
    }
    val localSharedDisplayName = remember(localUserDisplayName, context) {
        localUserDisplayName.trim().ifEmpty {
            context.getString(R.string.chat_location_you_are_here)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isLocal) Arrangement.End else Arrangement.Start
    ) {
        Box(
            contentAlignment = if (message.isLocal) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Surface(
                modifier = Modifier
                    .background(highlightColor, bubbleShape)
                    .offset { IntOffset(animatedOffset.roundToInt(), 0) },
                color = bubbleColor,
                contentColor = contentColor,
                shape = bubbleShape,
                border = if (message.isLocal) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                } else {
                    null
                },
                tonalElevation = if (highlight) 1.dp else 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .pointerInput(message.messageUuid) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    swipeOffset = (swipeOffset + dragAmount).coerceIn(-200f, 200f)
                                },
                                onDragEnd = {
                                    val shouldReply = abs(swipeOffset) > swipeThresholdPx
                                    swipeOffset = 0f
                                    if (shouldReply) {
                                        onReply(message)
                                    }
                                },
                                onDragCancel = {
                                    swipeOffset = 0f
                                }
                            )
                        }
                        .combinedClickable(
                            onClick = {
                                val targetUuid = parsedReply?.targetUuid
                                if (targetUuid != null) {
                                    onReplyNavigate(targetUuid)
                                } else if (sharedLocation != null) {
                                    openLocationComparison()
                                } else if (sharedFile != null) {
                                    val opened = openSharedFileFromMessage(
                                        context = context,
                                        messageUuid = message.messageUuid,
                                        payload = sharedFile
                                    )
                                    if (!opened) {
                                        Toast
                                            .makeText(
                                                context,
                                                context.getString(R.string.chat_file_open_failed),
                                                Toast.LENGTH_SHORT
                                            )
                                            .show()
                                    }
                                }
                            },
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onLongClick = { showContextMenu = true }
                        )
                ) {
                    when (message.messageType) {
                        MessageType.AUDIO -> AudioMessageContent(
                            message = message,
                            contentColor = contentColor,
                            progress = voiceProgress
                        )

                        MessageType.IMAGE -> ImageMessageContent(
                            message = message,
                            contentColor = contentColor,
                            progress = imageProgress,
                            onImageClick = onImageClick
                        )

                        MessageType.TEXT -> {
                            parsedReply?.let { reply ->
                                ReplyQuotedPreview(
                                    preview = reply.preview,
                                    authorLabel = reply.authorLabel,
                                    contentColor = contentColor,
                                    isLocal = message.isLocal
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            if (sharedLocation != null) {
                                val sharedDisplayName = if (message.isLocal) {
                                    localSharedDisplayName
                                } else {
                                    conversationDisplayName
                                }
                                val sharedStableKey = if (message.isLocal) {
                                    "self:$conversationStableKey"
                                } else {
                                    conversationStableKey
                                }
                                LocationMessageContent(
                                    payload = locationPayloadForDisplay ?: sharedLocation,
                                    ownLocation = if (message.isLocal) null else currentOwnLocation,
                                    distanceText = bubbleDistanceText,
                                    contentColor = contentColor,
                                    sharedDisplayName = sharedDisplayName,
                                    sharedStableKey = sharedStableKey,
                                    avatarBitmap = if (message.isLocal) {
                                        localProfileBitmap
                                    } else {
                                        remoteProfileBitmap
                                    },
                                    isLiveLocation = shouldShowLiveLocation,
                                    onOpenMap = openLocationComparison
                                )
                            } else if (sharedFile != null) {
                                FileMessageContent(
                                    payload = sharedFile,
                                    contentColor = contentColor
                                )
                            } else {
                                Text(
                                    text = displayText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = contentColor
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.align(
                            if (message.isLocal) Alignment.End else Alignment.Start
                        ),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formattedTimestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Start
                        )
                        if (message.isLocal) {
                            val readIndicatorColor = Color(0xFF34B7F1)
                            val normalizedStatus = when {
                                message.isRead -> MessageDeliveryStatus.READ
                                message.deliveryStatus != null -> message.deliveryStatus
                                else -> MessageDeliveryStatus.DELIVERED
                            }
                            val (icon, statusLabel, tint) = when (normalizedStatus) {
                                MessageDeliveryStatus.QUEUED -> Triple(
                                    Icons.Outlined.Schedule,
                                    R.string.chat_message_status_pending,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                MessageDeliveryStatus.SENDING -> Triple(
                                    Icons.Filled.MoreVert,
                                    R.string.chat_message_status_sending,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                MessageDeliveryStatus.SENT -> Triple(
                                    Icons.Filled.Done,
                                    R.string.chat_message_status_sent,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                MessageDeliveryStatus.DELIVERED -> Triple(
                                    Icons.Filled.DoneAll,
                                    R.string.chat_message_status_delivered,
                                    contentColor.copy(alpha = 0.6f)
                                )

                                MessageDeliveryStatus.READ -> Triple(
                                    Icons.Filled.DoneAll,
                                    R.string.chat_message_status_read,
                                    readIndicatorColor
                                )

                                MessageDeliveryStatus.FAILED -> Triple(
                                    Icons.Filled.Close,
                                    R.string.chat_message_status_failed,
                                    MaterialTheme.colorScheme.error
                                )
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(statusLabel),
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
                    text = { Text(stringResource(R.string.chat_action_reply)) },
                    onClick = {
                        showContextMenu = false
                        onReply(message)
                    }
                )
                DropdownMenuItem(
                    enabled = isTextMessage,
                    text = { Text(stringResource(R.string.chat_action_copy)) },
                    onClick = {
                        val textToCopy = locationPayloadForDisplay?.let {
                            context.getString(
                                R.string.chat_location_coordinates,
                                formatLocationCoordinate(it.latitude),
                                formatLocationCoordinate(it.longitude)
                            )
                        } ?: sharedFile?.let {
                            context.getString(
                                R.string.chat_file_copied_template,
                                it.displayName
                            )
                        } ?: displayText
                        clipboardManager.setText(AnnotatedString(textToCopy))
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.chat_message_copied),
                                Toast.LENGTH_SHORT
                            )
                            .show()
                        showContextMenu = false
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
    if (showLocationDialog && sharedLocation != null) {
        LocationFullScreenDialog(
            payload = locationPayloadForDisplay ?: sharedLocation,
            trackedOwnLocation = currentOwnLocation,
            isBluetoothConnected = isBluetoothConnected,
            bluetoothSignalInfo = bluetoothSignalInfo,
            signalPermissionMissing = signalPermissionMissing,
            sharedDisplayName = if (message.isLocal) {
                localSharedDisplayName
            } else {
                conversationDisplayName
            },
            sharedStableKey = if (message.isLocal) {
                "self:$conversationStableKey"
            } else {
                conversationStableKey
            },
            focusSharedOnlyOnMap = message.isLocal,
            proximityPayload = if (message.isLocal) latestRemoteSharedLocation else null,
            onDismiss = { showLocationDialog = false }
        )
    }
}

@Composable
private fun ReplyQuotedPreview(
    preview: String,
    authorLabel: String?,
    contentColor: Color,
    isLocal: Boolean
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
                color = indicatorColor
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

@Composable
private fun LocationMessageContent(
    payload: SharedLocationPayload,
    ownLocation: Location?,
    distanceText: String?,
    contentColor: Color,
    sharedDisplayName: String,
    sharedStableKey: String,
    avatarBitmap: Bitmap? = null,
    isLiveLocation: Boolean = false,
    onOpenMap: () -> Unit
) {
    val context = LocalContext.current
    val confidenceText = remember(payload) { buildConfidenceRadiusText(context, payload) }
    val previewBackground = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.08f)
    val previewBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpenMap),
        color = previewBackground,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, previewBorder)
    ) {
        Column {
            RelativeLocationFallbackMap(
                ownLocation = ownLocation,
                payload = payload,
                sharedDisplayName = sharedDisplayName,
                sharedStableKey = sharedStableKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ContactAvatar(
                    displayName = sharedDisplayName,
                    stableKey = sharedStableKey,
                    bitmap = avatarBitmap,
                    modifier = Modifier
                        .size(30.dp),
                    textStyle = MaterialTheme.typography.labelMedium
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            if (isLiveLocation) {
                                R.string.chat_location_preview_label_live
                            } else {
                                R.string.chat_location_preview_label
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                    Text(
                        text = stringResource(
                            R.string.chat_location_coordinates,
                            formatLocationCoordinate(payload.latitude),
                            formatLocationCoordinate(payload.longitude)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    distanceText?.let { value ->
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.74f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    confidenceText?.let { value ->
                        Text(
                            text = value,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
