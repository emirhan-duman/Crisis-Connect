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
import android.os.SystemClock
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.font.FontFamily
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
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.orderedCallAudioRoutes
import com.auralis.crisisconnect.ui.components.AttachmentAction
import com.auralis.crisisconnect.ui.components.AudioMessageCard
import com.auralis.crisisconnect.ui.components.ContactAvatar
import com.auralis.crisisconnect.ui.components.WhatsAppAttachmentMenu
import com.auralis.crisisconnect.ui.theme.StatusConnectedContainer
import com.auralis.crisisconnect.ui.theme.StatusConnectedOnContainer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
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
internal fun SearchMessagesDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<ChatMessage>,
    onResultClick: (ChatMessage) -> Unit,
    onDismiss: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(stringResource(R.string.chat_search_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.chat_search_placeholder)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                val hasQuery = query.isNotBlank()
                if (results.isEmpty() && hasQuery) {
                    Text(
                        text = stringResource(R.string.chat_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (results.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(results, key = { it.messageUuid }) { message ->
                            SearchResultRow(
                                message = message,
                                timeFormatter = timeFormatter,
                                onClick = { onResultClick(message) }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun SearchResultRow(
    message: ChatMessage,
    timeFormatter: SimpleDateFormat,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val replyMetadata = remember(message.text) { parseReplyMetadata(message.text) }
    val baseText = remember(message.text) {
        stripReplyMetadata(message.text)?.takeIf { it.isNotBlank() } ?: message.text.trim()
    }
    val sharedLocation = remember(baseText) { parseSharedLocationPayload(baseText) }
    val sharedFile = remember(baseText) { parseSharedFilePayload(baseText) }
    val preview = remember(message.messageType, baseText, sharedLocation, sharedFile) {
        when (message.messageType) {
            MessageType.TEXT -> {
                if (sharedLocation != null) {
                    context.getString(R.string.chat_location_preview_label)
                } else if (sharedFile != null) {
                    context.getString(
                        R.string.chat_file_preview_with_name,
                        sharedFile.displayName
                    )
                } else {
                    baseText.ifBlank { context.getString(R.string.chat_reply_unknown_placeholder) }
                }
            }
            MessageType.AUDIO -> context.getString(R.string.conversation_preview_voice_message)
            MessageType.IMAGE -> context.getString(R.string.conversation_preview_photo_message)
        }
    }
    val timestamp = remember(message.timestampMillis) {
        timeFormatter.format(Date(message.timestampMillis))
    }
    val leadingIcon = when (message.messageType) {
        MessageType.TEXT -> when {
            sharedLocation != null -> Icons.Filled.LocationOn
            sharedFile != null -> Icons.Filled.Description
            else -> Icons.Outlined.TextSnippet
        }
        MessageType.AUDIO -> Icons.Filled.Mic
        MessageType.IMAGE -> Icons.Filled.Image
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(22.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            replyMetadata?.body?.takeIf { it.isNotBlank() }?.let { repliedBody ->
                Text(
                    text = stringResource(R.string.chat_search_reply_prefix, repliedBody),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun ScrollToBottomButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    BadgedBox(
        badge = {
            if (count > 0) {
                Badge {
                    Text(text = count.coerceAtMost(99).toString())
                }
            }
        },
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
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

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CallOverlay(
    modifier: Modifier = Modifier,
    call: CallUiState,
    contactName: String,
    avatarStableKey: String,
    avatarBitmap: Bitmap?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onSelectAudioRoute: (CallAudioRoute) -> Unit,
    onMinimize: () -> Unit
) {
    var showAudioRouteSheet by remember(call.callId, call.state) { mutableStateOf(false) }
    val routeOptions = remember(call.availableRoutes, call.currentRoute) {
        orderedCallAudioRoutes(call.availableRoutes + call.currentRoute)
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val (chipColor, chipContentColor) = if (call.encrypted) {
                MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }
            val chipLabel = if (call.encrypted) {
                stringResource(R.string.chat_call_secure)
            } else {
                stringResource(R.string.chat_call_unsecured)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    onClick = onMinimize,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_call_cancel),
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = chipColor,
                    contentColor = chipContentColor,
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = chipLabel,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp
            ) {
                Box(
                    modifier = Modifier
                        .size(176.dp)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ContactAvatar(
                        displayName = contactName,
                        stableKey = avatarStableKey,
                        bitmap = avatarBitmap,
                        modifier = Modifier.size(156.dp),
                        textStyle = MaterialTheme.typography.displaySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = contactName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            val statusText = when (call.state) {
                CallState.Connecting -> stringResource(R.string.chat_call_connecting)
                CallState.Ringing -> if (call.isOutgoing) {
                    stringResource(R.string.chat_call_outgoing_ringing)
                } else {
                    stringResource(R.string.chat_call_incoming)
                }
                CallState.InCall -> stringResource(R.string.chat_call_incall)
                CallState.Idle, CallState.Ended -> stringResource(R.string.chat_call_idle)
            }
            AnimatedContent(
                targetState = statusText,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) with fadeOut(animationSpec = tween(180))
                },
                label = "call_status"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            if (call.state == CallState.InCall) {
                val durationText = rememberCallDuration(call)
                durationText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                Text(
                    text = stringResource(
                        R.string.whistle_route_label,
                        routeLabel(call.currentRoute)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            when {
                call.state == CallState.Ringing && !call.isOutgoing -> {
                    val acceptColor = Color(0xFF1E9E52)
                    val rejectColor = MaterialTheme.colorScheme.error
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IncomingCallAction(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.chat_call_reject),
                            containerColor = rejectColor,
                            contentColor = MaterialTheme.colorScheme.onError,
                            borderColor = rejectColor.copy(alpha = 0.4f),
                            onClick = onReject
                        )
                        IncomingCallAction(
                            icon = Icons.Filled.Phone,
                            label = stringResource(R.string.chat_call_accept),
                            containerColor = acceptColor,
                            contentColor = Color.White,
                            borderColor = acceptColor.copy(alpha = 0.35f),
                            onClick = onAccept
                        )
                    }
                }

                call.state == CallState.InCall -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallControlButton(
                            icon = if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            label = if (call.muted) {
                                stringResource(R.string.chat_call_unmute)
                            } else {
                                stringResource(R.string.chat_call_mute)
                            },
                            background = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = { onToggleMute(!call.muted) }
                        )
                        CallControlButton(
                            icon = if (call.currentRoute == CallAudioRoute.Speaker) {
                                Icons.Filled.VolumeOff
                            } else {
                                Icons.Filled.VolumeUp
                            },
                            label = routeLabel(call.currentRoute),
                            background = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            onClick = { showAudioRouteSheet = true }
                        )
                        CallControlButton(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.chat_call_hangup),
                            background = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            onClick = onHangup
                        )
                    }
                }

                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CallControlButton(
                            icon = Icons.Filled.CallEnd,
                            label = stringResource(R.string.chat_call_cancel),
                            background = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                            onClick = onHangup
                        )
                    }
                }
            }
        }
    }
    if (showAudioRouteSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAudioRouteSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_call_audio_route_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.chat_call_audio_route_sheet_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                routeOptions.forEach { route ->
                    val selected = route == call.currentRoute
                    Surface(
                        onClick = {
                            onSelectAudioRoute(route)
                            showAudioRouteSheet = false
                        },
                        shape = RoundedCornerShape(18.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        },
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = routeLabel(route),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                )
                                Text(
                                    text = if (selected) {
                                        stringResource(R.string.chat_call_audio_route_current)
                                    } else {
                                        stringResource(R.string.chat_call_audio_route_tap_to_switch)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
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
private fun routeLabel(route: CallAudioRoute): String {
    val labelRes = when (route) {
        CallAudioRoute.Speaker -> R.string.whistle_route_builtin_speaker
        CallAudioRoute.Earpiece -> R.string.whistle_route_earpiece
        CallAudioRoute.Bluetooth -> R.string.whistle_route_bluetooth
        CallAudioRoute.WiredHeadset -> R.string.whistle_route_wired
        CallAudioRoute.Streaming -> R.string.whistle_route_usb
        CallAudioRoute.Unknown -> R.string.whistle_route_unknown
    }
    return stringResource(labelRes)
}

@Composable
internal fun rememberCallDuration(call: CallUiState?): String? {
    val callId = call?.callId
    val connectedAt = call?.connectedAt
    val shouldTrack = call?.state == CallState.InCall && connectedAt != null
    val elapsedMillis = remember(callId, connectedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(callId, connectedAt, shouldTrack) {
        if (!shouldTrack) {
            elapsedMillis.longValue = 0L
            return@LaunchedEffect
        }
        val connectedAtMillis = connectedAt ?: run {
            elapsedMillis.longValue = 0L
            return@LaunchedEffect
        }
        val initialElapsed = (System.currentTimeMillis() - connectedAtMillis).coerceAtLeast(0L)
        val baseRealtime = SystemClock.elapsedRealtime()
        elapsedMillis.longValue = initialElapsed
        while (isActive) {
            val progressedElapsed = initialElapsed + (SystemClock.elapsedRealtime() - baseRealtime)
            elapsedMillis.longValue = progressedElapsed.coerceAtLeast(0L)
            val delayUntilNextSecond = 1_000L - (progressedElapsed % 1_000L)
            delay(delayUntilNextSecond.coerceIn(50L, 1_000L))
        }
    }
    return if (shouldTrack) {
        val totalSeconds = (elapsedMillis.longValue / 1000).coerceAtLeast(0L)
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()
        if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    } else {
        null
    }
}

@Composable
internal fun ChatCallStatusBar(
    call: CallUiState,
    contactName: String,
    avatarStableKey: String,
    avatarBitmap: Bitmap?,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationText = rememberCallDuration(call)
    val isIncomingRinging = call.state == CallState.Ringing && !call.isOutgoing
    val accentColor = when {
        isIncomingRinging -> Color(0xFF1E9E52)
        call.state == CallState.InCall -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    val stateTitle = when (call.state) {
        CallState.Connecting -> stringResource(R.string.chat_call_connecting)
        CallState.Ringing -> if (call.isOutgoing) {
            stringResource(R.string.chat_call_outgoing_ringing)
        } else {
            stringResource(R.string.chat_call_incoming)
        }
        CallState.InCall -> stringResource(R.string.chat_call_ongoing_banner)
        CallState.Idle, CallState.Ended -> stringResource(R.string.chat_call_idle)
    }
    val supportingParts = buildList {
        if (call.state == CallState.InCall) {
            durationText?.let(::add)
            add(routeLabel(call.currentRoute))
        } else {
            add(contactName)
        }
        add(
            if (call.encrypted) {
                stringResource(R.string.chat_call_secure)
            } else {
                stringResource(R.string.chat_call_unsecured)
            }
        )
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(accentColor)
            )
            ContactAvatar(
                displayName = contactName,
                stableKey = avatarStableKey,
                bitmap = avatarBitmap,
                modifier = Modifier.size(44.dp),
                textStyle = MaterialTheme.typography.titleMedium
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = supportingParts.joinToString(separator = " • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = if (call.state == CallState.InCall) {
                        FontFamily.Monospace
                    } else {
                        FontFamily.Default
                    }
                )
            }
            if (isIncomingRinging) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallStatusActionButton(
                        icon = Icons.Filled.CallEnd,
                        label = stringResource(R.string.chat_call_reject),
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        onClick = onReject
                    )
                    CallStatusActionButton(
                        icon = Icons.Filled.Phone,
                        label = stringResource(R.string.chat_call_accept),
                        containerColor = accentColor,
                        contentColor = Color.White,
                        onClick = onAccept
                    )
                }
            } else {
                CallStatusActionButton(
                    icon = Icons.Filled.CallEnd,
                    label = if (call.state == CallState.InCall) {
                        stringResource(R.string.chat_call_hangup)
                    } else {
                        stringResource(R.string.chat_call_cancel)
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onHangup
                )
            }
        }
    }
}

@Composable
private fun CallStatusActionButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.size(40.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    val animatedElevation by animateFloatAsState(targetValue = 6f, label = "call_button_elevation")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = onClick,
            color = background,
            contentColor = contentColor,
            shape = CircleShape,
            tonalElevation = animatedElevation.dp,
            shadowElevation = animatedElevation.dp,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp)
        )
    }
}

@Composable
private fun IncomingCallAction(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.size(72.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}


@Composable
internal fun CallEventRow(
    event: CallEvent,
    messageFormatter: SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val timeLabel = remember(event.timestampMillis) {
        messageFormatter.format(Date(event.timestampMillis))
    }
    val durationText = event.durationMillis?.takeIf { it > 0L }?.let {
        formatCallDuration(context, it)
    }
    val statusText = when (event.direction) {
        CallDirection.OUTGOING -> when (event.result) {
            CallResult.ANSWERED -> stringResource(R.string.chat_call_event_outgoing_answered)
            CallResult.CANCELED -> stringResource(R.string.chat_call_event_canceled)
            CallResult.REJECTED -> stringResource(R.string.chat_call_event_rejected)
            CallResult.MISSED -> stringResource(R.string.chat_call_event_missed)
        }

        CallDirection.INCOMING -> when (event.result) {
            CallResult.ANSWERED -> stringResource(R.string.chat_call_event_incoming_answered)
            CallResult.MISSED -> stringResource(R.string.chat_call_event_missed)
            CallResult.REJECTED -> stringResource(R.string.chat_call_event_rejected)
            CallResult.CANCELED -> stringResource(R.string.chat_call_event_canceled)
        }
    }
    val fullText = buildString {
        append(timeLabel)
        append(' ')
        append(statusText)
        if (event.result == CallResult.ANSWERED && durationText != null) {
            append(" \u00B7 ")
            append(durationText)
        }
    }
    val (icon, tint) = when (event.result) {
        CallResult.ANSWERED -> {
            if (event.direction == CallDirection.OUTGOING) {
                Icons.Filled.CallMade to MaterialTheme.colorScheme.primary
            } else {
                Icons.Filled.CallReceived to MaterialTheme.colorScheme.primary
            }
        }

        CallResult.MISSED -> Icons.Filled.CallMissed to MaterialTheme.colorScheme.error
        CallResult.REJECTED -> Icons.Filled.CallEnd to MaterialTheme.colorScheme.error
        CallResult.CANCELED -> {
            val iconVector = if (event.direction == CallDirection.OUTGOING) {
                Icons.Filled.CallMade
            } else {
                Icons.Filled.CallReceived
            }
            iconVector to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    val (cardColor, cardContentColor) = outgoingChatBubbleColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = cardColor,
        contentColor = cardContentColor,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Text(
                text = fullText,
                style = MaterialTheme.typography.bodyMedium,
                color = cardContentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun DateHeader(date: String) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = date,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
internal fun ChatEncryptionNoticeCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.chat_e2ee_notice),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun ConnectionStatusBadge(connectionState: ChatConnectionState) {
    val statusIcon = when (connectionState) {
        ChatConnectionState.Connected -> Icons.Filled.Link
        else -> null
    }
    val (label, backgroundColor, contentColor) = when (connectionState) {
        ChatConnectionState.Connected -> Triple(
            stringResource(R.string.chat_status_connected),
            StatusConnectedContainer,
            StatusConnectedOnContainer
        )

        ChatConnectionState.Connecting -> Triple(
            stringResource(R.string.chat_status_connecting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )

        ChatConnectionState.Error -> Triple(
            stringResource(R.string.chat_status_disconnected),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )

        ChatConnectionState.Idle -> Triple(
            stringResource(R.string.chat_status_idle),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Surface(
        color = backgroundColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            statusIcon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
