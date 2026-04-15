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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.local.ProfileImageStorage
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.util.createMapViewSafely
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
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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

private enum class LocationPreviewMode {
    Grid,
    MapTiles
}

private fun locationMapStatusMessageRes(
    mapViewAvailable: Boolean,
    hasInternetConnection: Boolean,
    hasOfflineMapCoverage: Boolean,
    mapLoadFailed: Boolean
): Int? {
    return when {
        !mapViewAvailable -> R.string.chat_location_map_unavailable_runtime
        !hasInternetConnection && !hasOfflineMapCoverage -> R.string.chat_location_map_unavailable_no_source
        mapLoadFailed -> R.string.chat_location_map_load_failed
        else -> null
    }
}

@Composable
internal fun LocationComparisonDialog(
    payload: SharedLocationPayload,
    isBluetoothConnected: Boolean,
    bluetoothSignalInfo: SignalStrengthInfo?,
    signalPermissionMissing: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val styleProvider = remember { OfflineServiceLocator.provideStyleUrlProvider(context) }
    val mapView = rememberLocationMapViewWithLifecycle()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember { mutableStateOf(false) }
    var mapLoadFailed by remember(mapView) { mutableStateOf(mapView == null) }
    var mapReloadToken by remember(mapView) { mutableStateOf(0) }
    var hasAppliedSharedCamera by remember(payload) { mutableStateOf(false) }
    var hasAppliedComparisonCamera by remember(payload) { mutableStateOf(false) }
    val hasPermission = hasLocationPermission(context)
    var ownLocation by remember(payload, hasPermission) { mutableStateOf<Location?>(null) }
    var locationResolved by remember(payload, hasPermission) { mutableStateOf(!hasPermission) }

    LaunchedEffect(payload, hasPermission) {
        if (!hasPermission) {
            locationResolved = true
            ownLocation = null
            return@LaunchedEffect
        }
        locationResolved = false
        fetchCurrentLocation(context) { location ->
            ownLocation = pickBetterLocationForPreview(ownLocation, location)
            locationResolved = true
        }
        delay(1_200L)
        fetchCurrentLocation(context) { location ->
            ownLocation = pickBetterLocationForPreview(ownLocation, location)
            locationResolved = true
        }
    }

    LaunchedEffect(mapView, mapReloadToken) {
        val safeMapView = mapView ?: run {
            mapLoadFailed = true
            return@LaunchedEffect
        }
        safeMapView.getMapAsync { map ->
            mapLibreMap = map
            configureLocationComparisonMapUi(map)
            isStyleLoaded = false
            mapLoadFailed = false
            var fallbackApplied = false
            val failListener = object : MapView.OnDidFailLoadingMapListener {
                override fun onDidFailLoadingMap(error: String?) {
                    if (fallbackApplied) {
                        mapLoadFailed = true
                        return
                    }
                    fallbackApplied = true
                    runCatching {
                        map.setStyle(styleProvider.fallback()) {
                            isStyleLoaded = true
                            mapLoadFailed = false
                        }
                    }.onFailure {
                        mapLoadFailed = true
                    }
                }
            }
            safeMapView.addOnDidFailLoadingMapListener(failListener)
            val primaryStyle = styleProvider.primary().ifBlank { styleProvider.fallback() }
            runCatching {
                map.setStyle(primaryStyle) {
                    isStyleLoaded = true
                    mapLoadFailed = false
                }
            }.onFailure {
                mapLoadFailed = true
            }
        }
    }

    LaunchedEffect(isStyleLoaded, mapLoadFailed) {
        if (isStyleLoaded || mapLoadFailed) {
            return@LaunchedEffect
        }
        delay(MAP_STYLE_LOAD_TIMEOUT_MS)
        if (!isStyleLoaded && !mapLoadFailed) {
            mapLoadFailed = true
        }
    }

    val deviceOrientation = rememberDeviceOrientationSnapshot(referenceLocation = ownLocation)
    val deviceHeadingDegrees = deviceOrientation.headingDegrees
    val hasPreciseLocation = hasPreciseLocationPermission(context)
    val shouldShowHorizontalHoldWarning = remember(deviceOrientation.tiltFromFlatDegrees) {
        val tilt = deviceOrientation.tiltFromFlatDegrees ?: return@remember false
        tilt >= LOCATION_VERTICAL_HOLD_WARNING_TILT_DEGREES
    }
    val resolvedHeadingDegrees = remember(deviceHeadingDegrees) {
        resolveHeadingForNavigation(
            sensorHeadingDegrees = deviceHeadingDegrees
        )
    }
    val mapReady = isStyleLoaded && !mapLoadFailed
    val mapHeadingForRender = remember(resolvedHeadingDegrees) {
        resolvedHeadingDegrees?.let { quantizeHeadingDegrees(it, MAP_HEADING_BUCKET_DEGREES) }
    }
    val mapRenderKey = remember(payload, ownLocation, mapHeadingForRender) {
        LocationMapRenderKey(
            ownLatE5 = ownLocation?.latitude?.let(::coordinateToE5),
            ownLonE5 = ownLocation?.longitude?.let(::coordinateToE5),
            sharedLatE5 = coordinateToE5(payload.latitude),
            sharedLonE5 = coordinateToE5(payload.longitude),
            headingBucket = mapHeadingForRender?.let { heading ->
                (normalizeHeadingDegrees(heading) / MAP_HEADING_BUCKET_DEGREES).roundToInt()
            }
        )
    }
    val activeStyleUrl = remember(styleProvider) {
        styleProvider.primary().ifBlank { styleProvider.fallback() }
    }
    val offlineZoomCap = rememberOfflineMaxZoomForLocation(
        latitude = payload.latitude,
        longitude = payload.longitude,
        styleUrl = activeStyleUrl
    )
    val hasOfflineMapCoverage = offlineZoomCap != null
    val hasInternetConnection = rememberInternetAvailability()
    val canShowMapTiles = mapView != null && (hasInternetConnection || hasOfflineMapCoverage)
    val mapStatusMessageRes = remember(
        mapView,
        hasInternetConnection,
        hasOfflineMapCoverage,
        mapLoadFailed
    ) {
        locationMapStatusMessageRes(
            mapViewAvailable = mapView != null,
            hasInternetConnection = hasInternetConnection,
            hasOfflineMapCoverage = hasOfflineMapCoverage,
            mapLoadFailed = mapLoadFailed
        )
    }
    var previewModeName by rememberSaveable(payload.latitude, payload.longitude) {
        mutableStateOf(
            if (canShowMapTiles) {
                LocationPreviewMode.MapTiles.name
            } else {
                LocationPreviewMode.Grid.name
            }
        )
    }
    val previewMode = if (previewModeName == LocationPreviewMode.MapTiles.name) {
        LocationPreviewMode.MapTiles
    } else {
        LocationPreviewMode.Grid
    }

    LaunchedEffect(canShowMapTiles) {
        if (!canShowMapTiles && previewMode == LocationPreviewMode.MapTiles) {
            previewModeName = LocationPreviewMode.Grid.name
        }
    }

    LaunchedEffect(mapLibreMap, mapReady, mapRenderKey, offlineZoomCap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!mapReady) {
            return@LaunchedEffect
        }
        val shouldAdjustCamera = if (ownLocation != null) {
            !hasAppliedComparisonCamera
        } else {
            !hasAppliedSharedCamera
        }
        val cameraApplied = updateLocationComparisonMap(
            map = map,
            shared = payload,
            ownLocation = ownLocation,
            context = context,
            ownHeadingDegrees = mapHeadingForRender,
            showOwnLocationPuck = true,
            maxZoomCap = offlineZoomCap,
            autoAdjustCamera = shouldAdjustCamera
        )
        if (cameraApplied) {
            if (ownLocation != null) {
                hasAppliedComparisonCamera = true
            } else {
                hasAppliedSharedCamera = true
            }
        }
    }

    LaunchedEffect(mapLibreMap, mapReady, ownLocation, previewMode) {
        val map = mapLibreMap ?: return@LaunchedEffect
        syncComparisonLocationComponent(
            map = map,
            context = context,
            ownLocation = ownLocation,
            enabled = mapReady && previewMode == LocationPreviewMode.MapTiles
        )
    }

    val distanceText = remember(
        payload,
        ownLocation,
        context,
        isBluetoothConnected,
        bluetoothSignalInfo,
        signalPermissionMissing
    ) {
        val current = ownLocation ?: return@remember context.getString(R.string.chat_location_distance_unavailable)
        buildDistanceRangeText(
            context = context,
            ownLocation = current,
            payload = payload,
            isBluetoothConnected = isBluetoothConnected,
            bluetoothSignalInfo = bluetoothSignalInfo
        )
    }

    val bluetoothHint = remember(
        context,
        isBluetoothConnected,
        bluetoothSignalInfo,
        signalPermissionMissing
    ) {
        if (!isBluetoothConnected) {
            null
        } else {
            val rssi = bluetoothSignalInfo?.rssi
            if (rssi != null) {
                context.getString(
                    R.string.chat_location_bluetooth_hint,
                    describeBluetoothProximity(context, rssi)
                )
            } else if (signalPermissionMissing) {
                context.getString(R.string.chat_location_bluetooth_unavailable)
            } else {
                context.getString(R.string.chat_location_bluetooth_connected_hint)
            }
        }
    }

    val deviceHeadingText = remember(resolvedHeadingDegrees, context) {
        val heading = resolvedHeadingDegrees ?: return@remember context.getString(R.string.chat_location_heading_unavailable)
        val normalized = normalizeHeadingDegrees(heading)
        val direction = context.getString(directionLabelResForHeading(normalized))
        context.getString(R.string.chat_location_phone_heading, normalized.roundToInt(), direction)
    }
    val targetBearingDegrees = remember(ownLocation, payload) {
        ownLocation?.let { location ->
            val target = Location("shared").apply {
                latitude = payload.latitude
                longitude = payload.longitude
            }
            normalizeHeadingDegrees(location.bearingTo(target))
        }
    }
    val isDirectionEstimateReliable = remember(ownLocation, payload) {
        isDirectionEstimateReliable(
            ownLocation = ownLocation,
            payload = payload
        )
    }
    val canShowDirectionalHints = remember(
        hasPreciseLocation,
        deviceOrientation.headingReliable,
        resolvedHeadingDegrees,
        isDirectionEstimateReliable
    ) {
        hasPreciseLocation &&
            deviceOrientation.headingReliable &&
            resolvedHeadingDegrees != null &&
            isDirectionEstimateReliable
    }
    val targetBearingText = remember(targetBearingDegrees, context, canShowDirectionalHints) {
        if (!canShowDirectionalHints) {
            return@remember null
        }
        val bearing = targetBearingDegrees ?: return@remember null
        val direction = context.getString(directionLabelResForHeading(bearing))
        context.getString(R.string.chat_location_shared_bearing, bearing.roundToInt(), direction)
    }
    val relativeDirectionText = remember(
        resolvedHeadingDegrees,
        targetBearingDegrees,
        context,
        canShowDirectionalHints
    ) {
        if (!canShowDirectionalHints) {
            return@remember null
        }
        val phoneHeading = resolvedHeadingDegrees ?: return@remember null
        val targetBearing = targetBearingDegrees ?: return@remember null
        buildRelativeDirectionText(context, phoneHeading, targetBearing)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_location_compare_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    val attachedMapView = mapView
                    if (attachedMapView != null) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (previewMode == LocationPreviewMode.MapTiles) 1f else 0f
                                },
                            factory = { attachedMapView }
                        )
                    }
                    if (previewMode == LocationPreviewMode.MapTiles) {
                        if (mapReady && attachedMapView != null) {
                            MapZoomControls(
                                onZoomIn = {
                                    mapLibreMap?.let { map ->
                                        zoomLocationComparisonMap(
                                            map = map,
                                            zoomDelta = LOCATION_MAP_MANUAL_ZOOM_STEP,
                                            maxZoomCap = offlineZoomCap
                                        )
                                    }
                                },
                                onZoomOut = {
                                    mapLibreMap?.let { map ->
                                        zoomLocationComparisonMap(
                                            map = map,
                                            zoomDelta = -LOCATION_MAP_MANUAL_ZOOM_STEP,
                                            maxZoomCap = offlineZoomCap
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 12.dp, bottom = 12.dp)
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    if (!mapLoadFailed) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = stringResource(R.string.offline_map_loading),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = stringResource(
                                                mapStatusMessageRes
                                                    ?: R.string.chat_location_map_load_failed
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        RelativeLocationFallbackMap(
                                            ownLocation = ownLocation,
                                            payload = payload,
                                            ownHeadingDegrees = resolvedHeadingDegrees,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = {
                                                mapLoadFailed = false
                                                isStyleLoaded = false
                                                mapReloadToken += 1
                                            }
                                        ) {
                                            Text(text = stringResource(R.string.chat_location_retry_map))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInteropFilter { true },
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            RelativeLocationFallbackMap(
                                ownLocation = ownLocation,
                                payload = payload,
                                ownHeadingDegrees = resolvedHeadingDegrees,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    LocationPreviewModeSelector(
                        selectedMode = previewMode,
                        isMapEnabled = canShowMapTiles,
                        onModeSelected = { mode ->
                            previewModeName = mode.name
                            if (mode == LocationPreviewMode.MapTiles && canShowMapTiles) {
                                mapLoadFailed = false
                                isStyleLoaded = false
                                mapReloadToken += 1
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    )
                }
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = deviceHeadingText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (shouldShowHorizontalHoldWarning) {
                    Text(
                        text = stringResource(R.string.chat_location_hold_horizontal_warning),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                targetBearingText?.let { bearingText ->
                    Text(
                        text = bearingText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                relativeDirectionText?.let { relativeText ->
                    Text(
                        text = relativeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isDirectionEstimateReliable && ownLocation != null) {
                    Text(
                        text = stringResource(R.string.chat_location_direction_low_confidence),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                bluetoothHint?.let { hint ->
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!canShowMapTiles && mapStatusMessageRes != null) {
                    Text(
                        text = stringResource(mapStatusMessageRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!hasPermission) {
                    Text(
                        text = stringResource(R.string.chat_location_compare_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!locationResolved) {
                    Text(
                        text = stringResource(R.string.chat_location_fetching),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(
                        R.string.chat_location_coordinates,
                        formatLocationCoordinate(payload.latitude),
                        formatLocationCoordinate(payload.longitude)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(android.R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
internal fun LocationFullScreenDialog(
    payload: SharedLocationPayload,
    trackedOwnLocation: Location?,
    isBluetoothConnected: Boolean,
    bluetoothSignalInfo: SignalStrengthInfo?,
    signalPermissionMissing: Boolean,
    sharedDisplayName: String,
    sharedStableKey: String,
    focusSharedOnlyOnMap: Boolean = false,
    proximityPayload: SharedLocationPayload? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val styleProvider = remember { OfflineServiceLocator.provideStyleUrlProvider(context) }
    val mapView = rememberLocationMapViewWithLifecycle()
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember { mutableStateOf(false) }
    var mapLoadFailed by remember(mapView) { mutableStateOf(mapView == null) }
    var mapReloadToken by remember(mapView) { mutableStateOf(0) }
    var hasAppliedSharedCamera by remember(payload, sharedStableKey, focusSharedOnlyOnMap) {
        mutableStateOf(false)
    }
    var hasAppliedComparisonCamera by remember(payload, sharedStableKey, focusSharedOnlyOnMap) {
        mutableStateOf(false)
    }
    val hasPermission = hasLocationPermission(context)
    var ownLocation by remember(payload, trackedOwnLocation, hasPermission) {
        mutableStateOf(trackedOwnLocation)
    }
    var locationResolved by remember(payload, trackedOwnLocation, hasPermission) {
        mutableStateOf(!hasPermission || trackedOwnLocation != null)
    }

    LaunchedEffect(payload, trackedOwnLocation, hasPermission) {
        if (!hasPermission) {
            ownLocation = null
            locationResolved = true
            return@LaunchedEffect
        }
        ownLocation = trackedOwnLocation
        locationResolved = trackedOwnLocation != null
        fetchCurrentLocation(context) { location ->
            ownLocation = pickBetterLocationForPreview(ownLocation, location)
            locationResolved = true
        }
        delay(1_200L)
        fetchCurrentLocation(context) { location ->
            ownLocation = pickBetterLocationForPreview(ownLocation, location)
            locationResolved = true
        }
    }

    LaunchedEffect(mapView, mapReloadToken) {
        val safeMapView = mapView ?: run {
            mapLoadFailed = true
            return@LaunchedEffect
        }
        safeMapView.getMapAsync { map ->
            mapLibreMap = map
            configureLocationComparisonMapUi(map)
            isStyleLoaded = false
            mapLoadFailed = false
            var fallbackApplied = false
            val failListener = object : MapView.OnDidFailLoadingMapListener {
                override fun onDidFailLoadingMap(error: String?) {
                    if (fallbackApplied) {
                        mapLoadFailed = true
                        return
                    }
                    fallbackApplied = true
                    runCatching {
                        map.setStyle(styleProvider.fallback()) {
                            isStyleLoaded = true
                            mapLoadFailed = false
                        }
                    }.onFailure {
                        mapLoadFailed = true
                    }
                }
            }
            safeMapView.addOnDidFailLoadingMapListener(failListener)
            val primaryStyle = styleProvider.primary().ifBlank { styleProvider.fallback() }
            runCatching {
                map.setStyle(primaryStyle) {
                    isStyleLoaded = true
                    mapLoadFailed = false
                }
            }.onFailure {
                if (fallbackApplied) {
                    mapLoadFailed = true
                    return@onFailure
                }
                fallbackApplied = true
                runCatching {
                    map.setStyle(styleProvider.fallback()) {
                        isStyleLoaded = true
                        mapLoadFailed = false
                    }
                }.onFailure {
                    mapLoadFailed = true
                }
            }
        }
    }

    LaunchedEffect(isStyleLoaded, mapLoadFailed) {
        if (isStyleLoaded || mapLoadFailed) {
            return@LaunchedEffect
        }
        delay(MAP_STYLE_LOAD_TIMEOUT_MS)
        if (!isStyleLoaded && !mapLoadFailed) {
            mapLoadFailed = true
        }
    }

    val deviceOrientation = rememberDeviceOrientationSnapshot(referenceLocation = ownLocation)
    val deviceHeadingDegrees = deviceOrientation.headingDegrees
    val hasPreciseLocation = hasPreciseLocationPermission(context)
    val shouldShowHorizontalHoldWarning = remember(deviceOrientation.tiltFromFlatDegrees) {
        val tilt = deviceOrientation.tiltFromFlatDegrees ?: return@remember false
        tilt >= LOCATION_VERTICAL_HOLD_WARNING_TILT_DEGREES
    }
    val resolvedHeadingDegrees = remember(deviceHeadingDegrees) {
        resolveHeadingForNavigation(
            sensorHeadingDegrees = deviceHeadingDegrees
        )
    }
    val mapOwnLocation = if (focusSharedOnlyOnMap) null else ownLocation
    val mapReady = isStyleLoaded && !mapLoadFailed
    val mapHeadingForRender = remember(resolvedHeadingDegrees, focusSharedOnlyOnMap) {
        if (focusSharedOnlyOnMap) {
            null
        } else {
            resolvedHeadingDegrees?.let { quantizeHeadingDegrees(it, MAP_HEADING_BUCKET_DEGREES) }
        }
    }
    val mapRenderKey = remember(payload, mapOwnLocation, mapHeadingForRender) {
        LocationMapRenderKey(
            ownLatE5 = mapOwnLocation?.latitude?.let(::coordinateToE5),
            ownLonE5 = mapOwnLocation?.longitude?.let(::coordinateToE5),
            sharedLatE5 = coordinateToE5(payload.latitude),
            sharedLonE5 = coordinateToE5(payload.longitude),
            headingBucket = mapHeadingForRender?.let { heading ->
                (normalizeHeadingDegrees(heading) / MAP_HEADING_BUCKET_DEGREES).roundToInt()
            }
        )
    }
    val activeStyleUrl = remember(styleProvider) {
        styleProvider.primary().ifBlank { styleProvider.fallback() }
    }
    val offlineZoomCap = rememberOfflineMaxZoomForLocation(
        latitude = payload.latitude,
        longitude = payload.longitude,
        styleUrl = activeStyleUrl
    )
    val hasOfflineMapCoverage = offlineZoomCap != null
    val hasInternetConnection = rememberInternetAvailability()
    val canShowMapTiles = mapView != null && (hasInternetConnection || hasOfflineMapCoverage)
    val mapStatusMessageRes = remember(
        mapView,
        hasInternetConnection,
        hasOfflineMapCoverage,
        mapLoadFailed
    ) {
        locationMapStatusMessageRes(
            mapViewAvailable = mapView != null,
            hasInternetConnection = hasInternetConnection,
            hasOfflineMapCoverage = hasOfflineMapCoverage,
            mapLoadFailed = mapLoadFailed
        )
    }
    var previewModeName by rememberSaveable(payload.latitude, payload.longitude, sharedStableKey) {
        mutableStateOf(
            if (canShowMapTiles) {
                LocationPreviewMode.MapTiles.name
            } else {
                LocationPreviewMode.Grid.name
            }
        )
    }
    val previewMode = if (previewModeName == LocationPreviewMode.MapTiles.name) {
        LocationPreviewMode.MapTiles
    } else {
        LocationPreviewMode.Grid
    }

    LaunchedEffect(canShowMapTiles) {
        if (!canShowMapTiles && previewMode == LocationPreviewMode.MapTiles) {
            previewModeName = LocationPreviewMode.Grid.name
        }
    }

    LaunchedEffect(mapLibreMap, mapReady, mapRenderKey, sharedDisplayName, sharedStableKey, offlineZoomCap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!mapReady) {
            return@LaunchedEffect
        }
        val shouldAdjustCamera = if (mapOwnLocation != null) {
            !hasAppliedComparisonCamera
        } else {
            !hasAppliedSharedCamera
        }
        val cameraApplied = updateLocationComparisonMap(
            map = map,
            shared = payload,
            ownLocation = mapOwnLocation,
            context = context,
            ownHeadingDegrees = mapHeadingForRender,
            sharedDisplayName = sharedDisplayName,
            sharedStableKey = sharedStableKey,
            showOwnLocationPuck = true,
            maxZoomCap = offlineZoomCap,
            autoAdjustCamera = shouldAdjustCamera
        )
        if (cameraApplied) {
            if (mapOwnLocation != null) {
                hasAppliedComparisonCamera = true
            } else {
                hasAppliedSharedCamera = true
            }
        }
    }

    LaunchedEffect(mapLibreMap, mapReady, mapOwnLocation, previewMode) {
        val map = mapLibreMap ?: return@LaunchedEffect
        syncComparisonLocationComponent(
            map = map,
            context = context,
            ownLocation = mapOwnLocation,
            enabled = mapReady && previewMode == LocationPreviewMode.MapTiles
        )
    }

    val distancePayload = remember(payload, proximityPayload, focusSharedOnlyOnMap) {
        if (focusSharedOnlyOnMap) {
            proximityPayload
        } else {
            proximityPayload ?: payload
        }
    }
    val distanceText = remember(
        distancePayload,
        ownLocation,
        context,
        isBluetoothConnected,
        bluetoothSignalInfo,
        signalPermissionMissing
    ) {
        val target = distancePayload ?: return@remember context.getString(R.string.chat_location_distance_unavailable)
        val current = ownLocation ?: return@remember context.getString(R.string.chat_location_distance_unavailable)
        buildDistanceRangeText(
            context = context,
            ownLocation = current,
            payload = target,
            isBluetoothConnected = isBluetoothConnected,
            bluetoothSignalInfo = bluetoothSignalInfo
        )
    }

    val bluetoothHint = remember(
        context,
        isBluetoothConnected,
        bluetoothSignalInfo,
        signalPermissionMissing
    ) {
        if (!isBluetoothConnected) {
            null
        } else {
            val rssi = bluetoothSignalInfo?.rssi
            if (rssi != null) {
                context.getString(
                    R.string.chat_location_bluetooth_hint,
                    describeBluetoothProximity(context, rssi)
                )
            } else if (signalPermissionMissing) {
                context.getString(R.string.chat_location_bluetooth_unavailable)
            } else {
                context.getString(R.string.chat_location_bluetooth_connected_hint)
            }
        }
    }

    val deviceHeadingText = remember(resolvedHeadingDegrees, context) {
        val heading = resolvedHeadingDegrees ?: return@remember context.getString(R.string.chat_location_heading_unavailable)
        val normalized = normalizeHeadingDegrees(heading)
        val direction = context.getString(directionLabelResForHeading(normalized))
        context.getString(R.string.chat_location_phone_heading, normalized.roundToInt(), direction)
    }
    val targetBearingDegrees = remember(ownLocation, distancePayload) {
        val targetPayload = distancePayload ?: return@remember null
        ownLocation?.let { location ->
            val target = Location("shared").apply {
                latitude = targetPayload.latitude
                longitude = targetPayload.longitude
            }
            normalizeHeadingDegrees(location.bearingTo(target))
        }
    }
    val isDirectionEstimateReliable = remember(ownLocation, distancePayload) {
        val targetPayload = distancePayload ?: return@remember false
        isDirectionEstimateReliable(
            ownLocation = ownLocation,
            payload = targetPayload
        )
    }
    val canShowDirectionalHints = remember(
        hasPreciseLocation,
        deviceOrientation.headingReliable,
        resolvedHeadingDegrees,
        isDirectionEstimateReliable
    ) {
        hasPreciseLocation &&
            deviceOrientation.headingReliable &&
            resolvedHeadingDegrees != null &&
            isDirectionEstimateReliable
    }
    val targetBearingText = remember(targetBearingDegrees, context, canShowDirectionalHints) {
        if (!canShowDirectionalHints) {
            return@remember null
        }
        val bearing = targetBearingDegrees ?: return@remember null
        val direction = context.getString(directionLabelResForHeading(bearing))
        context.getString(R.string.chat_location_shared_bearing, bearing.roundToInt(), direction)
    }
    val relativeDirectionText = remember(
        resolvedHeadingDegrees,
        targetBearingDegrees,
        context,
        canShowDirectionalHints
    ) {
        if (!canShowDirectionalHints) {
            return@remember null
        }
        val phoneHeading = resolvedHeadingDegrees ?: return@remember null
        val targetBearing = targetBearingDegrees ?: return@remember null
        buildRelativeDirectionText(context, phoneHeading, targetBearing)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(android.R.string.cancel)
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_location_compare_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val attachedMapView = mapView
                    if (attachedMapView != null) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (previewMode == LocationPreviewMode.MapTiles) 1f else 0f
                                },
                            factory = { attachedMapView }
                        )
                    }
                    if (previewMode == LocationPreviewMode.MapTiles) {
                        if (mapReady && attachedMapView != null) {
                            MapZoomControls(
                                onZoomIn = {
                                    mapLibreMap?.let { map ->
                                        zoomLocationComparisonMap(
                                            map = map,
                                            zoomDelta = LOCATION_MAP_MANUAL_ZOOM_STEP,
                                            maxZoomCap = offlineZoomCap
                                        )
                                    }
                                },
                                onZoomOut = {
                                    mapLibreMap?.let { map ->
                                        zoomLocationComparisonMap(
                                            map = map,
                                            zoomDelta = -LOCATION_MAP_MANUAL_ZOOM_STEP,
                                            maxZoomCap = offlineZoomCap
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 14.dp, bottom = 14.dp)
                            )
                        } else {
                            RelativeLocationFallbackMap(
                                ownLocation = mapOwnLocation,
                                payload = payload,
                                ownHeadingDegrees = mapHeadingForRender,
                                sharedDisplayName = sharedDisplayName,
                                sharedStableKey = sharedStableKey,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (!mapLoadFailed) {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 20.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                        Text(
                                            text = stringResource(R.string.offline_map_loading),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(16.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                ) {
                                    Text(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        text = stringResource(
                                            mapStatusMessageRes
                                                ?: R.string.chat_location_map_load_failed
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        mapLoadFailed = false
                                        isStyleLoaded = false
                                        mapReloadToken += 1
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 72.dp, end = 16.dp)
                                ) {
                                    Text(text = stringResource(R.string.chat_location_retry_map))
                                }
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInteropFilter { true },
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            RelativeLocationFallbackMap(
                                ownLocation = mapOwnLocation,
                                payload = payload,
                                ownHeadingDegrees = mapHeadingForRender,
                                sharedDisplayName = sharedDisplayName,
                                sharedStableKey = sharedStableKey,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    LocationPreviewModeSelector(
                        selectedMode = previewMode,
                        isMapEnabled = canShowMapTiles,
                        onModeSelected = { mode ->
                            previewModeName = mode.name
                            if (mode == LocationPreviewMode.MapTiles && canShowMapTiles) {
                                mapLoadFailed = false
                                isStyleLoaded = false
                                mapReloadToken += 1
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = distanceText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val headingRotation = (resolvedHeadingDegrees ?: 0f) - 45f
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .graphicsLayer { rotationZ = headingRotation }
                            )
                            Text(
                                text = deviceHeadingText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (shouldShowHorizontalHoldWarning) {
                            Text(
                                text = stringResource(R.string.chat_location_hold_horizontal_warning),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        targetBearingText?.let { bearingText ->
                            Text(
                                text = bearingText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        relativeDirectionText?.let { relativeText ->
                            Text(
                                text = relativeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!isDirectionEstimateReliable && ownLocation != null) {
                            Text(
                                text = stringResource(R.string.chat_location_direction_low_confidence),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        bluetoothHint?.let { hint ->
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!canShowMapTiles && mapStatusMessageRes != null) {
                            Text(
                                text = stringResource(mapStatusMessageRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!hasPermission) {
                            Text(
                                text = stringResource(R.string.chat_location_compare_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (!locationResolved) {
                            Text(
                                text = stringResource(R.string.chat_location_fetching),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.chat_location_coordinates,
                                formatLocationCoordinate(payload.latitude),
                                formatLocationCoordinate(payload.longitude)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

internal fun pickBetterLocationForPreview(
    current: Location?,
    candidate: Location?
): Location? {
    if (candidate == null) {
        return current
    }
    if (!isLocationCoordinatePlausible(candidate)) {
        return current
    }
    if (current == null) {
        return candidate
    }
    val nowMillis = System.currentTimeMillis()
    val currentAccuracy = locationAccuracyForComparison(current)
    val candidateAccuracy = locationAccuracyForComparison(candidate)
    val currentAgeMillis = locationAgeForComparisonMillis(current, nowMillis)
    val candidateAgeMillis = locationAgeForComparisonMillis(candidate, nowMillis)
    val driftMeters = calculateDistanceMeters(
        fromLatitude = current.latitude,
        fromLongitude = current.longitude,
        toLatitude = candidate.latitude,
        toLongitude = candidate.longitude
    )

    val dynamicMovementThreshold = max(
        4.0,
        min(26.0, ((currentAccuracy + candidateAccuracy) * 0.58).toDouble())
    )
    val movedEnough = driftMeters >= dynamicMovementThreshold
    val candidateMuchMoreAccurate = candidateAccuracy + 4f < currentAccuracy
    val candidateSlightlyMoreAccurateAndFresher =
        candidateAccuracy + 1.8f < currentAccuracy &&
            candidateAgeMillis <= currentAgeMillis + 20_000L
    val candidateClearlyFresher =
        candidateAgeMillis + 18_000L < currentAgeMillis &&
            candidateAccuracy <= currentAccuracy + 4f
    val candidateNotMeaningfullyWorse = candidateAccuracy <= currentAccuracy + 6f
    val candidateVeryNoisy =
        candidateAccuracy >= LOCATION_POOR_ACCURACY_METERS &&
            candidateAccuracy > currentAccuracy + 10f

    if (candidateVeryNoisy && !movedEnough) {
        return current
    }
    if (candidateMuchMoreAccurate || candidateSlightlyMoreAccurateAndFresher) {
        return candidate
    }
    if (movedEnough && candidateNotMeaningfullyWorse) {
        return candidate
    }
    if (candidateClearlyFresher && !movedEnough) {
        return candidate
    }

    val currentScore = locationQualityScore(current, nowMillis)
    val candidateScore = locationQualityScore(candidate, nowMillis)
    return if (candidateScore + 2.5 < currentScore) candidate else current
}

internal fun isLocationCoordinatePlausible(location: Location): Boolean {
    return location.latitude.isFinite() &&
        location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 &&
        location.longitude in -180.0..180.0
}

internal fun locationAccuracyForComparison(location: Location): Float {
    return location.accuracy
        .takeIf { it > 0f && it.isFinite() }
        ?.coerceIn(1.5f, 200f)
        ?: LOCATION_DEFAULT_ACCURACY_METERS
}

internal fun locationAgeForComparisonMillis(location: Location, nowMillis: Long): Long {
    val elapsedRealtimeNanos = location.elapsedRealtimeNanos
    if (elapsedRealtimeNanos > 0L) {
        return ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L)
            .coerceAtLeast(0L)
    }
    val timestamp = location.time.takeIf { it > 0L } ?: return LOCATION_STALE_AGE_THRESHOLD_MS + 1L
    return (nowMillis - timestamp).coerceAtLeast(0L)
}

internal fun locationQualityScore(location: Location, nowMillis: Long): Double {
    val accuracyComponent = locationAccuracyForComparison(location).toDouble()
    val ageMillis = locationAgeForComparisonMillis(location, nowMillis)
    val agePenalty = when {
        ageMillis <= 10_000L -> 0.0
        ageMillis <= 60_000L -> ((ageMillis - 10_000L) / 10_000.0) * 1.4
        ageMillis <= LOCATION_STALE_AGE_THRESHOLD_MS ->
            7.0 + ((ageMillis - 60_000L) / 60_000.0) * 5.0
        else -> 60.0 + min(
            160.0,
            ((ageMillis - LOCATION_STALE_AGE_THRESHOLD_MS) / 60_000.0) * 14.0
        )
    }
    return accuracyComponent + agePenalty
}

@Composable
internal fun RelativeLocationFallbackMap(
    ownLocation: Location?,
    payload: SharedLocationPayload,
    modifier: Modifier = Modifier,
    ownHeadingDegrees: Float? = null,
    sharedDisplayName: String? = null,
    sharedStableKey: String? = null
) {
    val ownMarkerColor = MaterialTheme.colorScheme.primary
    val sharedLabel = sharedDisplayName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.chat_location_shared_point)
    val sharedMarkerColor = remember(sharedStableKey, sharedLabel) {
        composeColorFromArgbInt(
            avatarColorForStableKey(
                stableKey = sharedStableKey?.trim().orEmpty().ifEmpty { sharedLabel }
            )
        )
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val center = Offset(widthPx / 2f, heightPx / 2f)
            val radiusPx = min(widthPx, heightPx) * 0.34f
            val normalizedHeading = ownHeadingDegrees?.takeIf { it.isFinite() }?.let(::normalizeHeadingDegrees)
            val sharedPoint = remember(payload, ownLocation, widthPx, heightPx) {
                if (ownLocation == null) {
                    center
                } else {
                    val meanLatRadians = Math.toRadians((ownLocation.latitude + payload.latitude) / 2.0)
                    val eastMeters =
                        (payload.longitude - ownLocation.longitude) * 111_320.0 * cos(meanLatRadians)
                    val northMeters =
                        (payload.latitude - ownLocation.latitude) * 110_540.0
                    val planarDistance = sqrt((eastMeters * eastMeters) + (northMeters * northMeters))
                    val normalizedRangeMeters = max(planarDistance, 30.0)
                    val scale = radiusPx / normalizedRangeMeters.toFloat()
                    val x = (center.x + eastMeters.toFloat() * scale).coerceIn(14f, widthPx - 14f)
                    val y = (center.y - northMeters.toFloat() * scale).coerceIn(14f, heightPx - 14f)
                    Offset(x, y)
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val step = min(size.width, size.height) / 4f
                val ownCenter = Offset(size.width / 2f, size.height / 2f)
                val ownArrowRadius = 13f
                val ownHeading = normalizedHeading ?: 0f
                val ownTip = offsetFromHeading(
                    center = ownCenter,
                    headingDegrees = ownHeading,
                    distance = ownArrowRadius * 1.85f
                )
                val ownLeft = offsetFromHeading(
                    center = ownCenter,
                    headingDegrees = ownHeading + 138f,
                    distance = ownArrowRadius * 1.05f
                )
                val ownRight = offsetFromHeading(
                    center = ownCenter,
                    headingDegrees = ownHeading - 138f,
                    distance = ownArrowRadius * 1.05f
                )
                val ownArrowPath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(ownTip.x, ownTip.y)
                    lineTo(ownLeft.x, ownLeft.y)
                    lineTo(ownRight.x, ownRight.y)
                    close()
                }
                val hasHeading = normalizedHeading != null
                val sharedRadius = 12f
                val pointerHalfWidth = sharedRadius * 0.62f
                val pointerTop = sharedPoint.y + sharedRadius - 1f
                val pointerTip = Offset(sharedPoint.x, sharedPoint.y + sharedRadius + 11f)
                val pointerLeft = Offset(sharedPoint.x - pointerHalfWidth, pointerTop)
                val pointerRight = Offset(sharedPoint.x + pointerHalfWidth, pointerTop)
                val sharedPinOuter = androidx.compose.ui.graphics.Path().apply {
                    moveTo(pointerLeft.x, pointerLeft.y)
                    lineTo(pointerTip.x, pointerTip.y)
                    lineTo(pointerRight.x, pointerRight.y)
                    close()
                }
                val sharedPinInner = androidx.compose.ui.graphics.Path().apply {
                    val innerInset = 1.8f
                    moveTo(pointerLeft.x + innerInset, pointerLeft.y + innerInset)
                    lineTo(pointerTip.x, pointerTip.y - 4f)
                    lineTo(pointerRight.x - innerInset, pointerRight.y + innerInset)
                    close()
                }
                val canvasRotation = -(normalizedHeading ?: 0f)
                rotate(degrees = canvasRotation, pivot = ownCenter) {
                    for (i in 1..3) {
                        val x = i * step
                        val y = i * step
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                    }

                    drawLine(
                        color = axisColor,
                        start = Offset(ownCenter.x, 0f),
                        end = Offset(ownCenter.x, size.height),
                        strokeWidth = 1.5f
                    )
                    drawLine(
                        color = axisColor,
                        start = Offset(0f, ownCenter.y),
                        end = Offset(size.width, ownCenter.y),
                        strokeWidth = 1.5f
                    )

                    if (ownLocation != null) {
                        drawLine(
                            color = axisColor,
                            start = ownCenter,
                            end = sharedPoint,
                            strokeWidth = 3f
                        )
                        drawCircle(
                            color = ownMarkerColor.copy(alpha = 0.22f),
                            radius = 20f,
                            center = ownCenter
                        )
                        if (hasHeading) {
                            drawPath(
                                path = ownArrowPath,
                                color = Color.White
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 9f,
                                center = ownCenter
                            )
                            drawPath(
                                path = ownArrowPath,
                                color = ownMarkerColor
                            )
                        } else {
                            drawCircle(
                                color = Color.White,
                                radius = 9f,
                                center = ownCenter
                            )
                        }
                        drawCircle(
                            color = ownMarkerColor,
                            radius = 4f,
                            center = ownCenter
                        )
                    }

                    drawPath(
                        path = sharedPinOuter,
                        color = Color.White
                    )
                    drawPath(
                        path = sharedPinInner,
                        color = sharedMarkerColor
                    )
                    drawCircle(
                        color = Color.White,
                        radius = sharedRadius + 2f,
                        center = sharedPoint
                    )
                    drawCircle(
                        color = sharedMarkerColor,
                        radius = sharedRadius,
                        center = sharedPoint
                    )
                    val profileHeadRadius = sharedRadius * 0.34f
                    val profileHeadCenter = Offset(
                        x = sharedPoint.x,
                        y = sharedPoint.y - sharedRadius * 0.24f
                    )
                    drawCircle(
                        color = Color.White,
                        radius = profileHeadRadius,
                        center = profileHeadCenter
                    )
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(
                            x = sharedPoint.x - sharedRadius * 0.52f,
                            y = sharedPoint.y + sharedRadius * 0.04f
                        ),
                        size = Size(
                            width = sharedRadius * 1.04f,
                            height = sharedRadius * 0.72f
                        ),
                        cornerRadius = CornerRadius(
                            x = sharedRadius * 0.38f,
                            y = sharedRadius * 0.38f
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 4.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ownLocation != null) {
                    LocationLegendItem(
                        label = stringResource(R.string.chat_location_you_are_here),
                        color = ownMarkerColor
                    )
                }
                LocationLegendItem(
                    label = sharedLabel,
                    color = sharedMarkerColor
                )
            }
        }
    }
}

@Composable
private fun LocationLegendItem(
    label: String,
    color: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LocationPreviewModeSelector(
    selectedMode: LocationPreviewMode,
    isMapEnabled: Boolean,
    onModeSelected: (LocationPreviewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = remember { listOf(LocationPreviewMode.Grid, LocationPreviewMode.MapTiles) }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.padding(4.dp)
        ) {
            options.forEachIndexed { index, mode ->
                val enabled = mode == LocationPreviewMode.Grid || isMapEnabled
                val labelRes = when (mode) {
                    LocationPreviewMode.Grid -> R.string.chat_location_view_grid
                    LocationPreviewMode.MapTiles -> R.string.chat_location_view_map
                }
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (enabled) {
                            onModeSelected(mode)
                        }
                    },
                    selected = selectedMode == mode,
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = {
                        Text(
                            text = stringResource(labelRes),
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MapZoomControls(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        SmallFloatingActionButton(
            onClick = onZoomIn,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
        ) {
            Text(
                text = "+",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        SmallFloatingActionButton(
            onClick = onZoomOut,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp)
        ) {
            Text(
                text = "-",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun rememberOfflineMaxZoomForLocation(
    latitude: Double,
    longitude: Double,
    styleUrl: String
): Double? {
    val context = LocalContext.current
    val listRegionsUseCase = remember { OfflineServiceLocator.provideListRegionsUseCase(context) }
    val regions by listRegionsUseCase().collectAsState(initial = emptyList())
    return remember(regions, latitude, longitude, styleUrl) {
        val normalizedStyle = styleUrl.trim()
        regions
            .asSequence()
            .filter { it.status == OfflineRegionStatus.Complete }
            .filter { region ->
                normalizedStyle.isBlank() || region.styleUrl.trim() == normalizedStyle
            }
            .filter { region ->
                offlineRegionContainsLocation(region, latitude, longitude)
            }
            .maxOfOrNull { it.maxZoom }
    }
}

private fun offlineRegionContainsLocation(
    region: OfflineRegionEntity,
    latitude: Double,
    longitude: Double
): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite()) {
        return false
    }
    val north = max(region.boundsNorth, region.boundsSouth)
    val south = min(region.boundsNorth, region.boundsSouth)
    if (latitude !in south..north) {
        return false
    }
    val west = region.boundsWest
    val east = region.boundsEast
    return if (west <= east) {
        longitude in west..east
    } else {
        longitude >= west || longitude <= east
    }
}

private fun clampLocationMapZoom(
    zoom: Double,
    maxZoomCap: Double?
): Double {
    val effectiveMax = maxZoomCap
        ?.takeIf { it.isFinite() }
        ?.coerceIn(LOCATION_MAP_MIN_ZOOM, LOCATION_MAP_MAX_ZOOM)
        ?: LOCATION_MAP_MAX_ZOOM
    return zoom.coerceIn(LOCATION_MAP_MIN_ZOOM, effectiveMax)
}

private fun locationComparisonBoundsPaddingPx(context: Context): Int {
    val density = context.resources.displayMetrics.density
    return max(96, (64f * density).roundToInt())
}

private fun zoomLocationComparisonMap(
    map: MapLibreMap,
    zoomDelta: Double,
    maxZoomCap: Double? = null
) {
    val currentCamera = runCatching { map.cameraPosition }.getOrNull() ?: return
    val currentTarget = currentCamera.target ?: return
    val targetZoom = clampLocationMapZoom(
        zoom = currentCamera.zoom + zoomDelta,
        maxZoomCap = maxZoomCap
    )
    runCatching {
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(currentTarget)
                    .zoom(targetZoom)
                    .bearing(currentCamera.bearing)
                    .tilt(currentCamera.tilt)
                    .build()
            )
        )
    }
}

private fun configureLocationComparisonMapUi(map: MapLibreMap) {
    runCatching {
        map.uiSettings.apply {
            setZoomGesturesEnabled(true)
            setScrollGesturesEnabled(true)
            setRotateGesturesEnabled(true)
            setTiltGesturesEnabled(false)
            setCompassEnabled(true)
        }
    }
}

@SuppressLint("MissingPermission")
private fun syncComparisonLocationComponent(
    map: MapLibreMap,
    context: Context,
    ownLocation: Location?,
    enabled: Boolean
) {
    val locationComponent = runCatching { map.locationComponent }.getOrNull() ?: return
    if (!enabled || ownLocation == null) {
        runCatching {
            if (locationComponent.isLocationComponentActivated) {
                locationComponent.isLocationComponentEnabled = false
            }
        }
        return
    }
    val style: Style = map.style ?: return
    runCatching {
        val options = LocationComponentOptions.builder(context)
            .trackingGesturesManagement(false)
            .compassAnimationEnabled(true)
            .accuracyAnimationEnabled(true)
            .pulseEnabled(false)
            .build()
        if (!locationComponent.isLocationComponentActivated) {
            val activationOptions = LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .build()
            locationComponent.activateLocationComponent(activationOptions)
        } else {
            locationComponent.applyStyle(options)
        }
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.NONE
        locationComponent.renderMode = RenderMode.COMPASS
        locationComponent.forceLocationUpdate(ownLocation)
    }
}

private fun updateLocationComparisonMap(
    map: MapLibreMap,
    shared: SharedLocationPayload,
    ownLocation: Location?,
    context: Context,
    ownHeadingDegrees: Float? = null,
    sharedDisplayName: String = context.getString(R.string.chat_location_shared_point),
    sharedStableKey: String = sharedDisplayName,
    showOwnLocationPuck: Boolean = false,
    maxZoomCap: Double? = null,
    autoAdjustCamera: Boolean = true
): Boolean {
    val currentCamera = runCatching { map.cameraPosition }.getOrNull()
    val comparisonDistanceMeters = ownLocation?.let { location ->
        calculateDistanceMeters(
            fromLatitude = location.latitude,
            fromLongitude = location.longitude,
            toLatitude = shared.latitude,
            toLongitude = shared.longitude
        )
    }
    val headingForCamera = ownHeadingDegrees
        ?.takeIf { it.isFinite() }
        ?.let(::normalizeHeadingDegrees)
        ?.toDouble()
    val targetBearing = headingForCamera ?: currentCamera?.bearing
    val markerLayout = if (showOwnLocationPuck || ownLocation == null) {
        null
    } else {
        resolveComparisonMarkerLayout(
            ownLocation = ownLocation,
            shared = shared,
            cameraBearingDegrees = targetBearing
        )
    }
    val sharedLatLng = markerLayout?.sharedMarkerLatLng ?: LatLng(shared.latitude, shared.longitude)
    val markerScale = markerScaleForComparisonDistance(
        comparisonDistanceMeters ?: Double.POSITIVE_INFINITY
    )
    val iconFactory = IconFactory.getInstance(context)
    runCatching {
        map.removeAnnotations()
    }
    val sharedMarkerTitle = sharedDisplayName.trim().ifEmpty {
        context.getString(R.string.chat_location_shared_point)
    }
    val sharedMarkerIcon = runCatching {
        iconFactory.fromBitmap(
            createSharedLocationAvatarMarkerBitmap(
                context = context,
                displayName = sharedMarkerTitle,
                stableKey = sharedStableKey,
                scale = markerScale
            )
        )
    }.getOrNull()
    runCatching {
        val markerOptions = MarkerOptions()
            .position(sharedLatLng)
            .title(sharedMarkerTitle)
        if (sharedMarkerIcon != null) {
            markerOptions.icon(sharedMarkerIcon)
        }
        map.addMarker(
            markerOptions
        )
    }
    if (!autoAdjustCamera) {
        return false
    }
    if (ownLocation != null) {
        val ownLatLng = markerLayout?.ownMarkerLatLng
            ?: LatLng(ownLocation.latitude, ownLocation.longitude)
        if (!showOwnLocationPuck) {
            val ownMarkerIcon = runCatching {
                iconFactory.fromBitmap(
                    createOwnDirectionMarkerBitmap(
                        context = context,
                        headingDegrees = ownHeadingDegrees,
                        scale = markerScale
                    )
                )
            }.getOrNull()
            runCatching {
                val markerOptions = MarkerOptions()
                    .position(ownLatLng)
                    .title(context.getString(R.string.chat_location_you_are_here))
                if (ownMarkerIcon != null) {
                    markerOptions.icon(ownMarkerIcon)
                }
                map.addMarker(
                    markerOptions
                )
            }
        }
        val distanceMeters = markerLayout?.cameraDistanceMeters ?: comparisonDistanceMeters ?: 0.0
        val midpoint = LatLng(
            (ownLocation.latitude + shared.latitude) / 2.0,
            (ownLocation.longitude + shared.longitude) / 2.0
        )
        val ownAccuracyMeters = ownLocation.accuracy
            .takeIf { it > 0f && it.isFinite() }
            ?.toDouble()
            ?.coerceIn(1.5, 60.0)
            ?: 10.0
        val sharedAccuracyMeters = shared.effectiveUncertaintyMeters(defaultMeters = 8.0)
            .coerceIn(1.5, 60.0)
        val paddedDistanceMeters = comparisonCameraSpanMeters(
            distanceMeters = distanceMeters,
            ownAccuracyMeters = ownAccuracyMeters,
            sharedAccuracyMeters = sharedAccuracyMeters,
            cameraBearingDegrees = targetBearing
        )
        var cameraApplied = runCatching {
            val zoom = clampLocationMapZoom(
                zoom = zoomForDistanceMeters(paddedDistanceMeters),
                maxZoomCap = maxZoomCap
            )
            val cameraUpdate = CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(midpoint)
                    .zoom(zoom)
                    .bearing(targetBearing ?: 0.0)
                    .tilt(0.0)
                    .build()
            )
            if (shouldAnimateCameraTransition(currentCamera, midpoint, zoom, targetBearing)) {
                map.animateCamera(cameraUpdate)
            } else {
                map.moveCamera(cameraUpdate)
            }
        }.isSuccess
        if (!cameraApplied) {
            val bounds = runCatching {
                LatLngBounds.Builder()
                    .include(sharedLatLng)
                    .include(ownLatLng)
                    .build()
            }.getOrNull()
            cameraApplied = bounds?.let { targetBounds ->
                val paddingPx = locationComparisonBoundsPaddingPx(context)
                runCatching {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(targetBounds, paddingPx)
                    )
                }.isSuccess
            } ?: false
        }
        if (!cameraApplied) {
            val fallbackZoom = clampLocationMapZoom(
                zoom = 15.0,
                maxZoomCap = maxZoomCap
            )
            runCatching {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(sharedLatLng, fallbackZoom))
            }.isSuccess.also { applied ->
                cameraApplied = applied
            }
        }
        return cameraApplied
    } else {
        return runCatching {
            val currentZoom = runCatching { map.cameraPosition.zoom }.getOrDefault(15.5)
            val targetZoom = clampLocationMapZoom(
                zoom = currentZoom.coerceIn(10.5, 16.5),
                maxZoomCap = maxZoomCap
            )
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(sharedLatLng, targetZoom))
        }.isSuccess
    }
}

private fun createOwnDirectionMarkerBitmap(
    context: Context,
    headingDegrees: Float?,
    scale: Float = 1f
): Bitmap {
    val density = context.resources.displayMetrics.density
    val markerScale = scale.coerceIn(0.64f, 1f)
    val sizePx = max(52, (46f * density * markerScale).roundToInt())
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val normalizedHeading = headingDegrees?.takeIf { it.isFinite() }?.let(::normalizeHeadingDegrees)
    val rotation = normalizedHeading ?: 0f
    val tipY = sizePx * 0.12f
    val baseY = sizePx * 0.80f

    val outerArrow = Path().apply {
        moveTo(center, tipY)
        lineTo(sizePx * 0.82f, baseY)
        lineTo(center, sizePx * 0.62f)
        lineTo(sizePx * 0.18f, baseY)
        close()
    }
    val innerArrow = Path().apply {
        moveTo(center, sizePx * 0.19f)
        lineTo(sizePx * 0.73f, sizePx * 0.73f)
        lineTo(center, sizePx * 0.57f)
        lineTo(sizePx * 0.27f, sizePx * 0.73f)
        close()
    }
    val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(70, 0, 0, 0)
        style = Paint.Style.FILL
    }
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#2D8CFF")
        style = Paint.Style.FILL
    }
    val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#1F6FD6")
        style = Paint.Style.FILL
    }

    if (normalizedHeading == null) {
        canvas.drawCircle(center + 1f, center + 2f, sizePx * 0.2f, haloPaint)
        canvas.drawCircle(center, center, sizePx * 0.18f, whitePaint)
        canvas.drawCircle(center, center, sizePx * 0.13f, centerPaint)
        return bitmap
    }

    canvas.save()
    canvas.rotate(rotation, center, center)
    canvas.drawCircle(center + 1f, center + 2f, sizePx * 0.2f, haloPaint)
    canvas.drawPath(outerArrow, whitePaint)
    canvas.drawPath(innerArrow, bluePaint)
    canvas.drawCircle(center, center, sizePx * 0.105f, whitePaint)
    canvas.drawCircle(center, center, sizePx * 0.076f, centerPaint)
    canvas.restore()
    return bitmap
}

private fun createSharedLocationAvatarMarkerBitmap(
    context: Context,
    displayName: String,
    stableKey: String,
    scale: Float = 1f
): Bitmap {
    val density = context.resources.displayMetrics.density
    val markerScale = scale.coerceIn(0.64f, 1f)
    val widthPx = max(82, (74f * density * markerScale).roundToInt())
    val heightPx = max(98, (88f * density * markerScale).roundToInt())
    val circleRadius = widthPx * 0.31f
    val cx = widthPx / 2f
    val cy = circleRadius + (8f * density)
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val initials = avatarInitials(
        displayName = displayName,
        fallback = context.getString(R.string.contact_initial_placeholder)
    )
    val backgroundColor = avatarColorForStableKey(stableKey.ifBlank { displayName })
    val pointerTop = cy + circleRadius - (3f * density)
    val pointerBottom = heightPx - (10f * density)
    val pointerHalfWidth = max(9f * density, circleRadius * 0.34f)

    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(80, 0, 0, 0)
        style = Paint.Style.FILL
    }
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(40, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = max(2f, 1.4f * density)
    }
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = circleRadius * 0.96f
    }

    val pointerPath = Path().apply {
        moveTo(cx - pointerHalfWidth, pointerTop)
        lineTo(cx, pointerBottom)
        lineTo(cx + pointerHalfWidth, pointerTop)
        close()
    }
    val pointerInnerPath = Path().apply {
        moveTo(cx - pointerHalfWidth * 0.82f, pointerTop + (1.2f * density))
        lineTo(cx, pointerBottom - (4f * density))
        lineTo(cx + pointerHalfWidth * 0.82f, pointerTop + (1.2f * density))
        close()
    }

    canvas.drawCircle(cx + (1.5f * density), cy + (3f * density), circleRadius + (2f * density), shadowPaint)
    canvas.drawPath(pointerPath, shadowPaint)
    canvas.drawPath(pointerPath, outlinePaint)
    canvas.drawPath(pointerInnerPath, avatarPaint)
    canvas.drawCircle(cx, cy, circleRadius + (2f * density), outlinePaint)
    canvas.drawCircle(cx, cy, circleRadius, avatarPaint)
    canvas.drawCircle(cx, cy, circleRadius * 0.86f, ringPaint)
    val textBaseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(initials, cx, textBaseline, textPaint)
    return bitmap
}

private fun avatarInitials(displayName: String, fallback: String): String {
    val normalizedFallback = fallback.trim().ifEmpty { "?" }
    return displayName
        .split(" ")
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(2)
        .mapNotNull { part -> part.firstOrNull()?.uppercaseChar()?.toString() }
        .joinToString(separator = "")
        .ifEmpty { normalizedFallback }
}

private fun avatarColorForStableKey(stableKey: String): Int {
    val normalized = stableKey.trim().ifEmpty { "location-peer" }
    val index = stablePositiveHash(normalized) % LOCATION_AVATAR_COLORS.size
    return LOCATION_AVATAR_COLORS[index]
}

private fun stablePositiveHash(value: String): Int {
    var hash = 17
    value.forEach { char ->
        hash = 31 * hash + char.code
    }
    return if (hash == Int.MIN_VALUE) 0 else abs(hash)
}

private fun composeColorFromArgbInt(colorInt: Int): Color {
    return Color(
        red = AndroidColor.red(colorInt) / 255f,
        green = AndroidColor.green(colorInt) / 255f,
        blue = AndroidColor.blue(colorInt) / 255f,
        alpha = AndroidColor.alpha(colorInt) / 255f
    )
}

private fun offsetFromHeading(
    center: Offset,
    headingDegrees: Float,
    distance: Float
): Offset {
    val radians = Math.toRadians((headingDegrees - 90f).toDouble())
    return Offset(
        x = center.x + (cos(radians) * distance).toFloat(),
        y = center.y + (sin(radians) * distance).toFloat()
    )
}

private fun Context.hasUsableInternetConnection(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return manager.hasUsableInternetConnection()
}

private fun ConnectivityManager.hasUsableInternetConnection(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
private fun rememberInternetAvailability(): Boolean {
    val context = LocalContext.current
    var hasInternet by remember { mutableStateOf(context.hasUsableInternetConnection()) }

    DisposableEffect(context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            hasInternet = false
            onDispose { }
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    hasInternet = manager.hasUsableInternetConnection()
                }

                override fun onLost(network: Network) {
                    hasInternet = manager.hasUsableInternetConnection()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    hasInternet = manager.hasUsableInternetConnection()
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching {
                manager.registerNetworkCallback(request, callback)
            }
            hasInternet = manager.hasUsableInternetConnection()
            onDispose {
                runCatching { manager.unregisterNetworkCallback(callback) }
            }
        }
    }

    return hasInternet
}

@Composable
private fun rememberLocationMapViewWithLifecycle(): MapView? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        createMapViewSafely(context, logTag = "ChatLocationDialogs")
    }

    DisposableEffect(lifecycleOwner.lifecycle, mapView) {
        val safeMapView = mapView ?: return@DisposableEffect onDispose { }
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> safeMapView.onStart()
                Lifecycle.Event.ON_RESUME -> safeMapView.onResume()
                Lifecycle.Event.ON_PAUSE -> safeMapView.onPause()
                Lifecycle.Event.ON_STOP -> safeMapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            safeMapView.onStart()
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            safeMapView.onResume()
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                safeMapView.onPause()
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                safeMapView.onStop()
            }
            safeMapView.onDestroy()
        }
    }

    return mapView
}
