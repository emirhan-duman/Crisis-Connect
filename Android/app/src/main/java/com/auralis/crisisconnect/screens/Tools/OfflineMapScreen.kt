package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.domain.offline.StartDownloadUseCase
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.util.createMapViewSafely
import com.auralis.crisisconnect.util.isMapLibreRuntimeSupported
import com.auralis.crisisconnect.ui.tools.offline.OfflineRegionEditorSheet
import com.auralis.crisisconnect.ui.tools.offline.OfflineRegionListScreen
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLngBounds
import org.json.JSONArray
import com.auralis.crisisconnect.ai.CrisisSentinelMapPoint

/** Parses the `points` nav arg (JSON array of {lat,lng,label,details?}) leniently. */
private fun parseMapPointsJson(pointsJson: String?): List<CrisisSentinelMapPoint> {
    if (pointsJson.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(pointsJson)
        buildList {
            for (index in 0 until array.length()) {
                val point = array.optJSONObject(index) ?: continue
                val lat = point.optDouble("lat", Double.NaN)
                val lng = point.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                add(
                    CrisisSentinelMapPoint(
                        lat = lat,
                        lng = lng,
                        label = point.optString("label"),
                        details = point.optString("details").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

@Composable
fun OfflineMapScreen(
    navController: NavController,
    initialLat: Double? = null,
    initialLng: Double? = null,
    initialLabel: String? = null,
    pointsJson: String? = null
) {
    val context = LocalContext.current
    // The offline-map tool needs the MapLibre GL runtime (OpenGL ES 3.0). On devices without it,
    // instantiating the ViewModel below builds an OfflineManager and throws a
    // MapLibreConfigurationException — the crash reported on the J4 Plus. Gate the screen here, BEFORE
    // the ViewModel is created, so unsupported devices see a graceful message instead of crashing.
    if (!isMapLibreRuntimeSupported(context)) {
        OfflineMapUnavailableScreen(navController)
        return
    }
    OfflineMapScreenContent(
        navController,
        initialLat = initialLat,
        initialLng = initialLng,
        initialLabel = initialLabel,
        pointsJson = pointsJson
    )
}

/** Graceful fallback shown when the device can't run the MapLibre GL offline-map runtime. */
@Composable
private fun OfflineMapUnavailableScreen(navController: NavController) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(id = R.string.offline_map_render_unavailable),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { navController.popBackStack() }) {
                Text(text = stringResource(id = R.string.back))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun OfflineMapScreenContent(
    navController: NavController,
    viewModel: OfflineMapViewModel = viewModel(factory = OfflineMapViewModel.factory(LocalContext.current)),
    initialLat: Double? = null,
    initialLng: Double? = null,
    initialLabel: String? = null,
    pointsJson: String? = null
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mapView = rememberMapViewWithLifecycle()
    val mapRuntimeAvailable = mapView != null
    val styleProvider = remember { OfflineServiceLocator.provideStyleUrlProvider(context) }
    val mapLibreMapState = remember { mutableStateOf<MapLibreMap?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val managedRegions = uiState.regions.filter { it.status != OfflineRegionStatus.Deleted }
    val activeRegion = managedRegions.firstOrNull {
        it.status == OfflineRegionStatus.Downloading && !it.isPaused
    }
    val readyCount = managedRegions.count { it.status == OfflineRegionStatus.Complete }
    val downloadedBytes = managedRegions.sumOf { it.bytesDownloaded.coerceAtLeast(0L) }

    LaunchedEffect(mapLibreMapState.value) {
        val map = mapLibreMapState.value ?: return@LaunchedEffect
        val points = parseMapPointsJson(pointsJson)
        if (points.size > 1) {
            // Multi-point payload (e.g. AI "show on map"): drop all markers, fit the camera.
            val bounds = LatLngBounds.Builder()
            points.forEach { point ->
                val latLng = LatLng(point.lat, point.lng)
                bounds.include(latLng)
                map.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title(point.label.ifBlank { null })
                        .snippet(point.details)
                )
            }
            runCatching {
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 96))
            }
        } else if (initialLat != null && initialLng != null) {
            val latLng = LatLng(initialLat, initialLng)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 6.0))
            map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(
                        initialLabel?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.offline_map_marker_disaster_location)
                    )
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.updatePixelRatio(context.resources.displayMetrics.density)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        val text = context.getString(message.messageRes, *message.formatArgs.toTypedArray())
        snackbarHostState.showSnackbar(text)
        viewModel.onMessageShown()
    }

    LaunchedEffect(mapView) {
        val safeMapView = mapView ?: return@LaunchedEffect
        viewModel.onStyleLoading()
        safeMapView.getMapAsync { map ->
            mapLibreMapState.value = map
            var fallbackApplied = false
            val failListener = object : MapView.OnDidFailLoadingMapListener {
                override fun onDidFailLoadingMap(error: String?) {
                    if (!fallbackApplied) {
                        fallbackApplied = true
                        map.setStyle(styleProvider.fallback()) { style ->
                            viewModel.onStyleLoaded()
                            viewModel.onStyleFallbackApplied()
                            if (
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                map.enableLocationComponent(context, style)
                            }
                        }
                    }
                }
            }
            safeMapView.addOnDidFailLoadingMapListener(failListener)
            map.addOnCameraIdleListener {
                viewModel.updateVisibleBounds(map.projection.visibleRegion?.latLngBounds)
            }
            val primaryStyle = styleProvider.primary()
            if (primaryStyle.isBlank()) {
                map.setStyle(styleProvider.fallback()) { style ->
                    viewModel.onStyleLoaded()
                    viewModel.onStyleFallbackApplied()
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        map.enableLocationComponent(context, style)
                    }
                }
            } else {
                map.setStyle(primaryStyle) { style ->
                    viewModel.onStyleLoaded()
                    if (
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        map.enableLocationComponent(context, style)
                    }
                }
            }
        }
    }

    val onMyLocationClick: () -> Unit = {
        val map = mapLibreMapState.value
        if (map == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.offline_map_location_unavailable)
                )
            }
        } else if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.offline_map_location_permission_required)
                )
            }
        } else {
            map.style?.let { style ->
                map.enableLocationComponent(context, style)
            }
            val location = getBestLastKnownLocation(context)
            if (location != null) {
                val latLng = LatLng(location.latitude, location.longitude)
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14.0))
            } else {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.offline_map_location_unavailable)
                    )
                }
            }
        }
    }

    val onViewRegion: (OfflineRegionUiModel) -> Unit = { region ->
        val map = mapLibreMapState.value
        viewModel.setListVisibility(false)
        if (map == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.offline_map_focus_region_error)
                )
            }
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(region.bounds, 64))
        }
    }

    val openDownloadEditor: () -> Unit = {
        if (uiState.isStyleLoaded) {
            viewModel.setEditorVisibility(true)
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.offline_map_wait_for_map)
                )
            }
        }
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_offline_maps_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { viewModel.setListVisibility(true) }) {
                        Icon(
                            imageVector = Icons.Filled.List,
                            contentDescription = stringResource(id = R.string.offline_map_view_regions)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (mapView != null) {
                val activeMapView = mapView
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { activeMapView }
                )
                MapInstructionChip(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
                SelectionOverlay(modifier = Modifier.align(Alignment.Center))
                SmallFloatingActionButton(
                    onClick = onMyLocationClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 58.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = stringResource(id = R.string.offline_map_my_location)
                    )
                }
                if (!uiState.isStyleLoaded) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.offline_map_loading),
                                style = MaterialTheme.typography.titleSmall,
                                textAlign = TextAlign.Start
                            )
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(id = R.string.offline_map_loading_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(id = R.string.offline_map_render_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            DownloadStatusCard(
                activeRegion = activeRegion,
                totalRegions = managedRegions.size,
                readyRegions = readyCount,
                downloadedBytes = downloadedBytes,
                onManageRegions = { viewModel.setListVisibility(true) },
                onStartDownload = openDownloadEditor,
                isMapReady = mapRuntimeAvailable && uiState.isStyleLoaded,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
            )
        }
    }

    if (uiState.isEditorVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setEditorVisibility(false) }
        ) {
            OfflineRegionEditorSheet(
                state = uiState.editorState,
                onNameChange = viewModel::updateSelectionName,
                onMinZoomChange = viewModel::updateMinZoom,
                onMaxZoomChange = viewModel::updateMaxZoom,
                onCancel = { viewModel.setEditorVisibility(false) },
                onDownload = { viewModel.startOfflineDownload() },
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    if (uiState.isListVisible) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.setListVisibility(false) }
        ) {
            OfflineRegionListScreen(
                regions = uiState.regions,
                onPause = viewModel::pauseRegion,
                onResume = viewModel::resumeRegion,
                onDelete = viewModel::deleteRegion,
                onRename = viewModel::renameRegion,
                onViewOnMap = onViewRegion,
                onDismiss = { viewModel.setListVisibility(false) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (uiState.showWarningDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissWarnings() },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmWarnings() }) {
                    Text(text = stringResource(id = R.string.offline_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissWarnings() }) {
                    Text(text = stringResource(id = R.string.offline_warning_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.offline_warning_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.pendingWarnings.forEach { warning ->
                        Text(text = warningText(context, warning))
                    }
                }
            }
        )
    }
}

@SuppressLint("MissingPermission")
private fun MapLibreMap.enableLocationComponent(context: Context, style: Style) {
    val activationOptions = LocationComponentActivationOptions.builder(context, style).build()
    locationComponent.apply {
        activateLocationComponent(activationOptions)
        isLocationComponentEnabled = true
        cameraMode = CameraMode.NONE
        renderMode = RenderMode.COMPASS
    }
}

private fun getBestLastKnownLocation(context: Context): Location? {
    val hasFineLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
        return null
    }

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return try {
        val providers = locationManager.getProviders(true)
        var bestLocation: Location? = null
        for (provider in providers) {
            val location = locationManager.getLastKnownLocation(provider) ?: continue
            if (bestLocation == null || location.accuracy < bestLocation.accuracy) {
                bestLocation = location
            }
        }
        bestLocation
    } catch (_: SecurityException) {
        null
    }
}

@Composable
private fun MapInstructionChip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 2.dp
    ) {
        Text(
            text = stringResource(id = R.string.offline_map_selection_overlay),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SelectionOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(0.72f)
            .aspectRatio(1.15f)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                RoundedCornerShape(16.dp)
            )
    )
}

@Composable
private fun DownloadStatusCard(
    activeRegion: OfflineRegionUiModel?,
    totalRegions: Int,
    readyRegions: Int,
    downloadedBytes: Long,
    onManageRegions: () -> Unit,
    onStartDownload: () -> Unit,
    isMapReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (activeRegion == null) {
                val storageText = Formatter.formatShortFileSize(context, downloadedBytes)
                val titleRes = if (totalRegions == 0) {
                    R.string.offline_map_no_regions_title
                } else {
                    R.string.offline_map_library_title
                }
                Text(
                    text = stringResource(id = titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                val bodyText = if (totalRegions == 0) {
                    stringResource(id = R.string.offline_map_no_regions_hint)
                } else {
                    stringResource(
                        id = R.string.offline_map_saved_summary_with_size,
                        totalRegions,
                        readyRegions,
                        storageText
                    )
                }
                Text(
                    text = bodyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(id = R.string.offline_map_active_download, activeRegion.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                LinearProgressIndicator(
                    progress = activeRegion.progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                val percent = (activeRegion.progress * 100).toInt().coerceIn(0, 100)
                Text(
                    text = stringResource(id = R.string.offline_region_progress_percentage, percent),
                    style = MaterialTheme.typography.bodySmall
                )
                val sizeText = Formatter.formatShortFileSize(context, activeRegion.bytesDownloaded)
                Text(
                    text = stringResource(id = R.string.offline_region_size_downloaded, sizeText),
                    style = MaterialTheme.typography.bodySmall
                )
                activeRegion.etaSeconds?.let {
                    Text(
                        text = formatEta(context, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!isMapReady) {
                Text(
                    text = stringResource(id = R.string.offline_map_wait_for_map),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = onManageRegions,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(imageVector = Icons.Filled.List, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.offline_map_manage_regions_action),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                Button(
                    onClick = onStartDownload,
                    enabled = isMapReady,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(id = R.string.offline_map_open_editor),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun warningText(context: android.content.Context, warning: StartDownloadUseCase.DownloadWarning): String {
    return when (warning) {
        is StartDownloadUseCase.DownloadWarning.LowStorage -> context.getString(
            R.string.offline_warning_low_storage,
            Formatter.formatShortFileSize(context, warning.availableBytes),
            Formatter.formatShortFileSize(context, warning.requiredBytes)
        )

        StartDownloadUseCase.DownloadWarning.MeteredNetwork -> context.getString(R.string.offline_warning_metered)
        StartDownloadUseCase.DownloadWarning.Roaming -> context.getString(R.string.offline_warning_roaming)
        StartDownloadUseCase.DownloadWarning.PowerSave -> context.getString(R.string.offline_warning_power_save)
    }
}

private fun formatEta(context: android.content.Context, seconds: Long): String {
    val minutes = (seconds / 60).coerceAtLeast(1)
    return if (minutes < 60) {
        context.getString(R.string.offline_map_eta_minutes, minutes)
    } else {
        val hours = (minutes / 60).coerceAtLeast(1)
        context.getString(R.string.offline_map_eta_hours, hours)
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView? {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        createMapViewSafely(context, logTag = "OfflineMapScreen")
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
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            safeMapView.onDestroy()
        }
    }

    return mapView
}
