package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
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
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.unit.IntSize
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

@Composable
internal fun ImageMessageContent(
    message: ChatMessage,
    contentColor: Color,
    progress: ImageTransferProgress?,
    onImageClick: (Uri) -> Unit
) {
    val context = LocalContext.current
    // File.exists() is a disk stat; unremembered it ran on every recomposition of every image
    // bubble (i.e. every transfer progress tick). Re-check only when the paths change or the
    // transfer reaches a new state (e.g. Completed drops the file onto disk).
    val viewableUri = remember(message.imageFilePath, message.imageThumbnailPath, progress?.state) {
        val primaryUri = message.imageFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) Uri.fromFile(file) else null
        }
        val thumbnailUri = message.imageThumbnailPath?.let { path ->
            val file = File(path)
            if (file.exists()) Uri.fromFile(file) else null
        }
        primaryUri ?: thumbnailUri
    }
    val imageRequest = remember(viewableUri, context) {
        viewableUri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .crossfade(false)
                .build()
        }
    }
    val aspectRatio = remember(message.imageWidth, message.imageHeight) {
        val width = message.imageWidth
        val height = message.imageHeight
        if (width != null && height != null && width > 0 && height > 0) {
            (width.toFloat() / height.toFloat()).coerceIn(0.5f, 2.5f)
        } else {
            null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (imageRequest != null && viewableUri != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (aspectRatio != null) {
                            Modifier.aspectRatio(aspectRatio).heightIn(min = 160.dp, max = 360.dp)
                        } else {
                            Modifier.heightIn(min = 160.dp, max = 360.dp)
                        }
                    )
            ) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.chat_image_message_content_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable {
                            onImageClick(viewableUri)
                        }
                )
                if (progress != null && progress.state != ImageTransferState.Completed) {
                    val overlayLabel = when (progress.state) {
                        ImageTransferState.Initializing -> stringResource(R.string.chat_image_transfer_initializing)
                        ImageTransferState.Transferring -> {
                            val percent = (progress.percentage * 100f).roundToInt().coerceIn(0, 100)
                            if (progress.direction == ImageTransferDirection.Upload) {
                                stringResource(R.string.chat_image_transfer_uploading, percent)
                            } else {
                                stringResource(R.string.chat_image_transfer_downloading, percent)
                            }
                        }
                        ImageTransferState.Waiting -> stringResource(R.string.chat_image_transfer_waiting)
                        ImageTransferState.Verifying -> stringResource(R.string.chat_image_transfer_verifying)
                        ImageTransferState.Failed -> stringResource(R.string.chat_image_transfer_failed)
                        ImageTransferState.Completed -> ""
                    }
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            if (progress.state == ImageTransferState.Failed) {
                                Icon(
                                    imageVector = Icons.Filled.BrokenImage,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            } else {
                                val percent = progress.percentage.coerceIn(0f, 1f)
                                if (progress.state == ImageTransferState.Transferring) {
                                    CircularProgressIndicator(
                                        progress = percent,
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White,
                                        trackColor = Color.White.copy(alpha = 0.3f)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(36.dp),
                                        color = Color.White,
                                        trackColor = Color.White.copy(alpha = 0.3f)
                                    )
                                }
                            }
                            Text(
                                text = overlayLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp)
                    .clip(RoundedCornerShape(16.dp)),
                color = contentColor.copy(alpha = 0.1f),
                contentColor = contentColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.75f),
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_image_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor
                    )
                    Text(
                        text = stringResource(R.string.chat_image_unavailable_description),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }

        if (message.imageWidth != null && message.imageHeight != null) {
            Text(
                text = stringResource(
                    R.string.chat_image_dimensions,
                    message.imageWidth,
                    message.imageHeight
                ),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.8f)
            )
        }

        val activeProgress = progress
        if (activeProgress != null && activeProgress.state != ImageTransferState.Completed) {
            ImageTransferStatus(activeProgress, contentColor)
        }
    }
}

@Composable
internal fun FullScreenImageViewer(
    uri: Uri,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showOptionsMenu by remember { mutableStateOf(false) }
    var imageScale by remember(uri) { mutableStateOf(FULL_SCREEN_IMAGE_MIN_SCALE) }
    var imageOffset by remember(uri) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val updateScale: (Float) -> Unit = remember(viewportSize, imageOffset) {
        { targetScale ->
            val boundedScale = targetScale.coerceIn(
                FULL_SCREEN_IMAGE_MIN_SCALE,
                FULL_SCREEN_IMAGE_MAX_SCALE
            )
            imageScale = boundedScale
            imageOffset = if (boundedScale <= FULL_SCREEN_IMAGE_MIN_SCALE) {
                Offset.Zero
            } else {
                clampFullScreenImageOffset(
                    candidate = imageOffset,
                    scale = boundedScale,
                    viewportSize = viewportSize
                )
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(uri)
                    .crossfade(true)
                    .build(),
                contentDescription = stringResource(R.string.chat_image_message_content_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .pointerInput(uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (imageScale * zoom).coerceIn(
                                FULL_SCREEN_IMAGE_MIN_SCALE,
                                FULL_SCREEN_IMAGE_MAX_SCALE
                            )
                            imageScale = nextScale
                            imageOffset = if (nextScale <= FULL_SCREEN_IMAGE_MIN_SCALE) {
                                Offset.Zero
                            } else {
                                clampFullScreenImageOffset(
                                    candidate = imageOffset + pan,
                                    scale = nextScale,
                                    viewportSize = viewportSize
                                )
                            }
                        }
                    }
                    .pointerInput(uri, imageScale, viewportSize) {
                        detectTapGestures(
                            onDoubleTap = {
                                val nextScale = if (imageScale > FULL_SCREEN_IMAGE_MIN_SCALE + 0.05f) {
                                    FULL_SCREEN_IMAGE_MIN_SCALE
                                } else {
                                    FULL_SCREEN_IMAGE_DOUBLE_TAP_SCALE
                                }
                                updateScale(nextScale)
                            }
                        )
                    }
                    .graphicsLayer {
                        scaleX = imageScale
                        scaleY = imageScale
                        translationX = imageOffset.x
                        translationY = imageOffset.y
                    }
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(android.R.string.cancel)
                    )
                }
                Box {
                    IconButton(
                        onClick = { showOptionsMenu = true },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.chat_image_viewer_more_options)
                        )
                    }
                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_image_viewer_zoom_in)) },
                            onClick = {
                                updateScale(imageScale + FULL_SCREEN_IMAGE_SCALE_STEP)
                                showOptionsMenu = false
                            },
                            enabled = imageScale < FULL_SCREEN_IMAGE_MAX_SCALE
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_image_viewer_zoom_out)) },
                            onClick = {
                                updateScale(imageScale - FULL_SCREEN_IMAGE_SCALE_STEP)
                                showOptionsMenu = false
                            },
                            enabled = imageScale > FULL_SCREEN_IMAGE_MIN_SCALE
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_image_viewer_reset_zoom)) },
                            onClick = {
                                updateScale(FULL_SCREEN_IMAGE_MIN_SCALE)
                                showOptionsMenu = false
                            },
                            enabled = imageScale > FULL_SCREEN_IMAGE_MIN_SCALE
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_image_viewer_share)) },
                            onClick = {
                                showOptionsMenu = false
                                if (!shareFullScreenImage(context, uri)) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_image_viewer_action_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_image_viewer_open_external)) },
                            onClick = {
                                showOptionsMenu = false
                                if (!openImageInExternalViewer(context, uri)) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_image_viewer_action_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun clampFullScreenImageOffset(
    candidate: Offset,
    scale: Float,
    viewportSize: IntSize
): Offset {
    if (scale <= FULL_SCREEN_IMAGE_MIN_SCALE || viewportSize == IntSize.Zero) {
        return Offset.Zero
    }
    val maxX = ((viewportSize.width * (scale - 1f)) / 2f).coerceAtLeast(0f)
    val maxY = ((viewportSize.height * (scale - 1f)) / 2f).coerceAtLeast(0f)
    return Offset(
        x = candidate.x.coerceIn(-maxX, maxX),
        y = candidate.y.coerceIn(-maxY, maxY)
    )
}

private fun toShareableImageUri(context: Context, uri: Uri): Uri {
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        return uri
    }
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val sourcePath = uri.path ?: return uri
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            return uri
        }
        return runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                sourceFile
            )
        }.getOrDefault(uri)
    }
    return uri
}

private fun shareFullScreenImage(context: Context, uri: Uri): Boolean {
    val shareUri = toShareableImageUri(context, uri)
    return runCatching {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            clipData = ClipData.newUri(context.contentResolver, "image", shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                shareIntent,
                context.getString(R.string.chat_image_viewer_share)
            )
        )
    }.isSuccess
}

private fun openImageInExternalViewer(context: Context, uri: Uri): Boolean {
    val targetUri = toShareableImageUri(context, uri)
    return runCatching {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(targetUri, "image/*")
            clipData = ClipData.newUri(context.contentResolver, "image", targetUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(
                openIntent,
                context.getString(R.string.chat_image_viewer_open_external)
            )
        )
    }.isSuccess
}

private const val FULL_SCREEN_IMAGE_MIN_SCALE = 1f
private const val FULL_SCREEN_IMAGE_MAX_SCALE = 5f
private const val FULL_SCREEN_IMAGE_DOUBLE_TAP_SCALE = 2f
private const val FULL_SCREEN_IMAGE_SCALE_STEP = 0.5f

@Composable
private fun ImageTransferStatus(progress: ImageTransferProgress, contentColor: Color) {
    val percent = (progress.percentage * 100f).roundToInt().coerceIn(0, 100)
    val directionLabel = when (progress.direction) {
        ImageTransferDirection.Upload -> stringResource(
            R.string.chat_image_transfer_sending,
            percent,
            progress.remainingChunks
        )

        ImageTransferDirection.Download -> stringResource(
            R.string.chat_image_transfer_receiving,
            percent,
            progress.remainingChunks
        )
    }
    LinearProgressIndicator(
        progress = progress.percentage.coerceIn(0f, 1f),
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = directionLabel,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 4.dp)
    )
    when (progress.state) {
        ImageTransferState.Initializing -> Text(
            text = stringResource(R.string.chat_image_transfer_initializing),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )

        ImageTransferState.Waiting -> Text(
            text = stringResource(R.string.chat_image_transfer_waiting),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )

        ImageTransferState.Verifying -> Text(
            text = stringResource(R.string.chat_image_transfer_verifying),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 2.dp)
        )

        ImageTransferState.Failed -> Text(
            text = stringResource(R.string.chat_image_transfer_failed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp)
        )

        else -> Unit
    }
}

@Composable
internal fun AudioMessageContent(
    message: ChatMessage,
    contentColor: Color,
    progress: VoiceTransferProgress?
) {
    val context = LocalContext.current
    val audioUri = message.audioFilePath
        ?.let { path -> File(path) }
        ?.takeIf { file -> file.exists() }
        ?.let { file -> Uri.fromFile(file) }
    val activeProgress = progress

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
            if (metadataDuration != null && metadataDuration > 0) {
                resolvedDurationMillis = metadataDuration
            }
        }
    }

    if (audioUri != null) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
            if (activeProgress != null && activeProgress.state != VoiceTransferState.Completed) {
                VoiceTransferStatus(activeProgress, contentColor)
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.7f)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_voice_message_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                val statusText = when (activeProgress?.state) {
                    null -> stringResource(R.string.chat_voice_unavailable)
                    VoiceTransferState.Failed -> stringResource(R.string.chat_voice_transfer_failed)
                    VoiceTransferState.Verifying -> stringResource(R.string.chat_voice_transfer_verifying)
                    VoiceTransferState.Completed -> stringResource(R.string.chat_voice_unavailable)
                    else -> null
                }
                statusText?.let { subtitle ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
        if (activeProgress != null && activeProgress.state != VoiceTransferState.Completed) {
            Spacer(modifier = Modifier.height(8.dp))
            VoiceTransferStatus(activeProgress, contentColor)
        }
    }
}

@Composable
private fun VoiceTransferStatus(progress: VoiceTransferProgress, contentColor: Color) {
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
        color = contentColor.copy(alpha = 0.8f),
        modifier = Modifier.padding(top = 4.dp)
    )
    when (progress.state) {
        VoiceTransferState.Verifying -> Text(
            text = stringResource(R.string.chat_voice_transfer_verifying),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.7f)
        )

        VoiceTransferState.Failed -> Text(
            text = stringResource(R.string.chat_voice_transfer_failed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp)
        )

        else -> Unit
    }
}

@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun MessageComposer(
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
    // Deferred read: ticks every 200 ms while recording; taking it as a plain value recomposed the
    // whole composer per tick. Only the duration label deep inside reads it now.
    recordingDurationMillis: () -> Long,
    canRecordVoice: Boolean,
    onAttachmentClick: () -> Unit,
    onAttachmentMenuDismiss: () -> Unit,
    isAttachmentMenuVisible: Boolean,
    canAddAttachments: Boolean,
    pendingImage: PendingImage?,
    onSendImage: () -> Unit,
    onDiscardImage: () -> Unit,
    isSendingImage: Boolean,
    replyToMessage: ChatMessage?,
    contactName: String,
    onDismissReply: () -> Unit
) {
    val durationLabel = { formatElapsedDuration(recordingDurationMillis()) }
    val showImagePreview = pendingImage != null && !isSendingImage
    val showVoiceConfirmation = hasRecordedVoice && !isSendingVoice
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            replyToMessage?.let { replyingTo ->
                ReplyPreview(
                    message = replyingTo,
                    contactName = contactName,
                    onDismiss = onDismissReply
                )
            }
            pendingImage?.takeIf { showImagePreview }?.let { preview ->
                ImageAttachmentPreview(
                    pendingImage = preview,
                    onSend = onSendImage,
                    onDiscard = onDiscardImage,
                    isSending = isSendingImage
                )
            }

            when {
                isRecording -> RecordingIndicator(
                    durationLabel = durationLabel,
                    onStop = onStopRecording,
                    onCancel = onDiscardRecording
                )

                showVoiceConfirmation -> RecordingConfirmation(
                    durationLabel = durationLabel,
                    onSendVoice = onSendVoice,
                    onDiscardRecording = onDiscardRecording,
                    isSending = isSendingVoice,
                    canSend = canRecordVoice
                )
            }

            if (!isRecording && !showVoiceConfirmation) {
                val hasText = value.isNotBlank()
                val shouldSendImage = pendingImage != null && !isSendingImage
                val shouldForceSendIcon = replyToMessage != null
                val showSendIcon = hasText || shouldSendImage || shouldForceSendIcon
                val isSendEnabled = hasText || shouldSendImage
                val isActionEnabled = !shouldForceSendIcon || isSendEnabled
                val disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                val disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                val buttonColors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = disabledContainerColor,
                    disabledContentColor = disabledContentColor
                )
                val attachmentColors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isAttachmentMenuVisible) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isAttachmentMenuVisible) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(
                        onClick = onAttachmentClick,
                        modifier = Modifier.size(44.dp),
                        colors = attachmentColors,
                        enabled = canAddAttachments
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.chat_add_attachment)
                        )
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .onFocusChanged { focusState ->
                                onInputFocusChanged(focusState.isFocused)
                            }
                            .animateContentSize(),
                        placeholder = {
                            Text(text = stringResource(R.string.chat_message_placeholder))
                        },
                        enabled = !shouldSendImage,
                        singleLine = false,
                        maxLines = 4,
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
                                onAttachmentMenuDismiss()
                                onSendText()
                            }
                        )
                    )
                    IconButton(
                        onClick = {
                            when {
                                shouldSendImage -> {
                                    onAttachmentMenuDismiss()
                                    onSendImage()
                                }

                                hasText -> {
                                    onAttachmentMenuDismiss()
                                    onSendText()
                                }

                                shouldForceSendIcon -> {
                                    onAttachmentMenuDismiss()
                                    onSendText()
                                }

                                else -> {
                                    onAttachmentMenuDismiss()
                                    if (canRecordVoice) {
                                        onStartRecording()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(52.dp),
                        colors = buttonColors,
                        enabled = (if (showSendIcon) isActionEnabled else canRecordVoice) &&
                            !isSendingImage &&
                            !isSendingVoice
                    ) {
                        AnimatedContent(
                            targetState = showSendIcon,
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
                                val description = if (shouldSendImage) {
                                    stringResource(R.string.chat_send_image)
                                } else {
                                    stringResource(R.string.chat_send_message)
                                }
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = description
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
private fun ReplyPreview(
    message: ChatMessage,
    contactName: String,
    onDismiss: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        val voiceLabel = stringResource(R.string.chat_reply_audio_placeholder)
        val imageLabel = stringResource(R.string.chat_reply_image_placeholder)
        val fileLabel = stringResource(R.string.chat_file_preview_label)
        val locationLabel = stringResource(R.string.chat_location_preview_label)
        val unknownLabel = stringResource(R.string.chat_reply_unknown_placeholder)
        val authorLabel = if (message.isLocal) {
            stringResource(R.string.chat_reply_sender_you)
        } else {
            contactName
        }
        val previewText = remember(message, voiceLabel, imageLabel, locationLabel, unknownLabel) {
            replyPreviewText(
                message = message,
                voiceLabel = voiceLabel,
                imageLabel = imageLabel,
                fileLabel = fileLabel,
                locationLabel = locationLabel,
                unknownLabel = unknownLabel
            )
        }
        val icon = when (message.messageType) {
            MessageType.TEXT -> when {
                parseSharedLocationPayload(message.text) != null -> Icons.Filled.LocationOn
                parseSharedFilePayload(message.text) != null -> Icons.Filled.Description
                else -> Icons.Outlined.TextSnippet
            }
            MessageType.AUDIO -> Icons.Outlined.Mic
            MessageType.IMAGE -> Icons.Outlined.Image
            MessageType.SOS_ALERT -> Icons.Outlined.Warning
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = authorLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = stringResource(R.string.chat_reply_context_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
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
private fun ImageAttachmentPreview(
    pendingImage: PendingImage,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    isSending: Boolean
) {
    val aspectRatio = rememberPendingImageAspectRatio(pendingImage)
    val sizeModifier = if (aspectRatio != null) {
        Modifier
            .aspectRatio(aspectRatio)
            .heightIn(min = 180.dp, max = 360.dp)
    } else {
        Modifier.heightIn(min = 180.dp, max = 360.dp)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .then(sizeModifier)
            ) {
                AsyncImage(
                    model = pendingImage.uri,
                    contentDescription = stringResource(R.string.chat_image_preview_content_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                )
                if (isSending) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                            Text(
                                text = stringResource(R.string.chat_image_sending),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
            if (pendingImage.width != null && pendingImage.height != null) {
                Text(
                    text = stringResource(
                        R.string.chat_image_dimensions,
                        pendingImage.width,
                        pendingImage.height
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDiscard,
                    enabled = !isSending
                ) {
                    Text(text = stringResource(R.string.chat_cancel_image))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = onSend,
                    enabled = !isSending
                ) {
                    Text(text = stringResource(R.string.chat_send_image_button))
                }
            }
        }
    }
}

@Composable
private fun rememberPendingImageAspectRatio(pendingImage: PendingImage): Float? {
    val context = LocalContext.current
    val aspectRatio by produceState<Float?>(initialValue = null, key1 = pendingImage.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(pendingImage.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                val width = options.outWidth
                val height = options.outHeight
                if (width > 0 && height > 0) {
                    (width.toFloat() / height.toFloat()).coerceIn(0.5f, 2.5f)
                } else {
                    null
                }
            }.getOrNull()
        }
    }
    return aspectRatio
}

@Composable
private fun RecordingIndicator(
    durationLabel: () -> String,
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
                        text = durationLabel(),
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
private fun RecordingConfirmation(
    durationLabel: () -> String,
    onSendVoice: () -> Unit,
    onDiscardRecording: () -> Unit,
    isSending: Boolean,
    canSend: Boolean
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
                    text = durationLabel(),
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
                    ),
                    enabled = canSend
                ) {
                    Text(text = stringResource(R.string.chat_voice_send))
                }
            }
        }
    }
}
