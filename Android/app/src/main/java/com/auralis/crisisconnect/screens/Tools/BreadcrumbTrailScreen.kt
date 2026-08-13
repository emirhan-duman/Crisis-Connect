package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

@Composable
fun BreadcrumbTrailScreen(navController: NavController) {
    val viewModel: BreadcrumbTrailViewModel = viewModel()
    val compassViewModel: CompassViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heading by compassViewModel.azimuth.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showReturnDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            pendingPermissionAction?.invoke()
        } else {
            viewModel.reportPermissionDenied()
        }
        pendingPermissionAction = null
    }

    fun withPreciseLocation(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingPermissionAction = action
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    DisposableEffect(Unit) {
        compassViewModel.start()
        onDispose { compassViewModel.stop() }
    }

    LaunchedEffect(uiState.currentLocation) {
        uiState.currentLocation?.let { point ->
            compassViewModel.autoSetTrueNorth(
                lat = point.latitude,
                lon = point.longitude,
                altMeters = point.altitudeMeters ?: 0.0,
            )
        }
    }

    LaunchedEffect(uiState.targetBearingDegrees) {
        compassViewModel.setTargetBearing(uiState.targetBearingDegrees?.toFloat())
    }

    if (showReturnDialog) {
        AlertDialog(
            onDismissRequest = { showReturnDialog = false },
            icon = { Icon(Icons.Filled.Navigation, contentDescription = null) },
            title = { Text(stringResource(R.string.breadcrumb_return_choose_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.breadcrumb_return_choose_body))
                    Button(
                        onClick = {
                            showReturnDialog = false
                            withPreciseLocation { viewModel.startReturn(BreadcrumbReturnTarget.START) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.breadcrumb_return_to_start))
                    }
                    OutlinedButton(
                        onClick = {
                            showReturnDialog = false
                            withPreciseLocation { viewModel.startReturn(BreadcrumbReturnTarget.LAST_SAFE) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.breadcrumb_return_to_safe))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReturnDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.breadcrumb_clear_title)) },
            text = { Text(stringResource(R.string.breadcrumb_clear_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearTrail()
                    }
                ) { Text(stringResource(R.string.breadcrumb_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_breadcrumb_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    if (uiState.session != null) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.breadcrumb_clear_confirm),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { AppBottomBar(navController) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val session = uiState.session
            when {
                session == null -> BreadcrumbEmptyState(
                    onStart = { withPreciseLocation(viewModel::startNewTrail) }
                )
                session.mode == BreadcrumbTrailMode.RETURNING ||
                    session.mode == BreadcrumbTrailMode.ARRIVED -> BreadcrumbReturnCard(
                    uiState = uiState,
                    headingDegrees = heading,
                    onPause = viewModel::pause,
                )
                else -> BreadcrumbStatusCard(
                    uiState = uiState,
                    onPause = viewModel::pause,
                    onResume = { withPreciseLocation(viewModel::resumeRecording) },
                )
            }

            uiState.errorMessageRes?.let { errorRes ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Text(stringResource(errorRes), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            session?.takeIf { it.points.isNotEmpty() }?.let {
                BreadcrumbMetrics(uiState)
                BreadcrumbTrailPreview(
                    points = it.points,
                    current = uiState.currentLocation,
                    safeIndex = it.safePointIndex,
                )

                if (it.mode != BreadcrumbTrailMode.RETURNING && it.mode != BreadcrumbTrailMode.ARRIVED) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = viewModel::markSafeLocation,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null)
                            Text(
                                stringResource(R.string.breadcrumb_mark_safe),
                                modifier = Modifier.padding(start = 7.dp),
                            )
                        }
                        Button(
                            onClick = { showReturnDialog = true },
                            enabled = it.points.size >= 2,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Filled.Navigation, contentDescription = null)
                            Text(
                                stringResource(R.string.breadcrumb_return_action),
                                modifier = Modifier.padding(start = 7.dp),
                            )
                        }
                    }
                }

                BreadcrumbMapCard(
                    uiState = uiState,
                    onOpenMap = {
                        val payload = JSONArray().apply {
                            viewModel.trailForMap().forEach { point ->
                                put(JSONObject().apply {
                                    put("lat", point.latitude)
                                    put("lng", point.longitude)
                                })
                            }
                        }.toString()
                        navController.navigate("offline_map?trail=${Uri.encode(payload)}")
                    },
                )
            }

            BreadcrumbSafetyCard()
        }
    }
}

@Composable
private fun BreadcrumbEmptyState(onStart: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                stringResource(R.string.breadcrumb_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.breadcrumb_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    stringResource(R.string.breadcrumb_start),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbStatusCard(
    uiState: BreadcrumbTrailUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    val session = uiState.session ?: return
    val recording = session.mode == BreadcrumbTrailMode.RECORDING
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        if (recording) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Filled.MyLocation else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = if (recording) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (recording) R.string.breadcrumb_status_recording
                        else R.string.breadcrumb_status_paused
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.breadcrumb_point_count, session.points.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = if (recording) onPause else onResume) {
                Icon(
                    if (recording) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Text(
                    stringResource(
                        if (recording) R.string.breadcrumb_pause else R.string.breadcrumb_resume
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbReturnCard(
    uiState: BreadcrumbTrailUiState,
    headingDegrees: Float,
    onPause: () -> Unit,
) {
    val arrived = uiState.session?.mode == BreadcrumbTrailMode.ARRIVED
    val relativeRotation = uiState.targetBearingDegrees?.let {
        (((it - headingDegrees + 540.0) % 360.0) - 180.0).toFloat()
    } ?: 0f
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (arrived) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (arrived) Icons.Filled.Flag else Icons.Filled.Navigation,
                contentDescription = null,
                modifier = Modifier
                    .size(74.dp)
                    .graphicsLayer(rotationZ = if (arrived) 0f else relativeRotation),
                tint = if (arrived) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(
                    if (arrived) R.string.breadcrumb_arrived
                    else R.string.breadcrumb_follow_arrow
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (!arrived) {
                Text(
                    formatDistance(uiState.nextBreadcrumbMeters),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        R.string.breadcrumb_remaining_format,
                        formatDistance(uiState.remainingRouteMeters)
                    ),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                LinearProgressIndicator(
                    progress = { uiState.returnProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onPause, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Pause, contentDescription = null)
                    Text(
                        stringResource(R.string.breadcrumb_pause),
                        modifier = Modifier.padding(start = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbMetrics(uiState: BreadcrumbTrailUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricCard(
            title = stringResource(R.string.breadcrumb_distance_start),
            value = formatDistance(uiState.distanceToStartMeters),
            icon = Icons.Filled.Flag,
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            title = stringResource(R.string.breadcrumb_distance_safe),
            value = formatDistance(uiState.distanceToSafeMeters),
            icon = Icons.Filled.Place,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BreadcrumbTrailPreview(
    points: List<BreadcrumbPoint>,
    current: BreadcrumbPoint?,
    safeIndex: Int,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.tertiary
    val safeColor = MaterialTheme.colorScheme.secondary
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.breadcrumb_trail_preview),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                    .padding(12.dp)
            ) {
                if (points.isEmpty()) return@Canvas
                val all = if (current != null) points + current else points
                val minLat = all.minOf { it.latitude }
                val maxLat = all.maxOf { it.latitude }
                val meanLat = all.map { it.latitude }.average()
                val lonScale = cos(Math.toRadians(meanLat)).coerceAtLeast(0.15)
                val minLon = all.minOf { it.longitude * lonScale }
                val maxLon = all.maxOf { it.longitude * lonScale }
                val latSpan = (maxLat - minLat).coerceAtLeast(0.00001)
                val lonSpan = (maxLon - minLon).coerceAtLeast(0.00001)
                fun offset(point: BreadcrumbPoint): Offset = Offset(
                    x = (((point.longitude * lonScale - minLon) / lonSpan) * size.width).toFloat(),
                    y = (size.height - ((point.latitude - minLat) / latSpan * size.height)).toFloat(),
                )
                val path = Path()
                points.forEachIndexed { index, point ->
                    val offset = offset(point)
                    if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                }
                drawPath(path, lineColor, style = Stroke(width = 6f, cap = StrokeCap.Round))
                drawCircle(startColor, radius = 9f, center = offset(points.first()))
                points.getOrNull(safeIndex)?.let { drawCircle(safeColor, radius = 8f, center = offset(it)) }
                current?.let { drawCircle(Color.White, radius = 9f, center = offset(it)) }
            }
        }
    }
}

@Composable
private fun BreadcrumbMapCard(
    uiState: BreadcrumbTrailUiState,
    onOpenMap: () -> Unit,
) {
    val hasOfflineMap = uiState.offlineRegionName != null
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            if (hasOfflineMap) R.string.breadcrumb_offline_map_ready
                            else R.string.breadcrumb_offline_map_missing
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        uiState.offlineRegionName
                            ?: stringResource(R.string.breadcrumb_offline_map_fallback),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (hasOfflineMap) R.string.breadcrumb_open_offline_map
                        else R.string.breadcrumb_open_map
                    )
                )
            }
        }
    }
}

@Composable
private fun BreadcrumbSafetyCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                stringResource(R.string.breadcrumb_safety_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatDistance(meters: Double?): String {
    if (meters == null || !meters.isFinite()) return stringResource(R.string.breadcrumb_distance_waiting)
    return if (meters >= 1_000) {
        stringResource(R.string.breadcrumb_distance_km, meters / 1_000.0)
    } else {
        stringResource(R.string.breadcrumb_distance_m, meters.toInt().coerceAtLeast(0))
    }
}
