package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storm
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Volcano
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Tools.data.DisasterErrorType
import com.auralis.crisisconnect.screens.Tools.data.DisasterEvent
import com.auralis.crisisconnect.screens.Tools.data.haversineDistanceKm
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ─── Icon mapping for each disaster type ───────────────────────────────────────
fun getEventTypeIcon(type: String): ImageVector {
    return when (type.uppercase()) {
        "EQ" -> Icons.Default.Vibration            // earthquake (shaking)
        "FL" -> Icons.Default.Water                // flood
        "TC" -> Icons.Default.Storm                // tropical cyclone / storm
        "VO" -> Icons.Default.Volcano              // volcano
        "WF" -> Icons.Default.LocalFireDepartment  // wildfire
        "DR" -> Icons.Default.WbSunny              // drought
        else -> Icons.Default.Warning
    }
}

fun formatDistanceKm(km: Double): String {
    return if (km < 1.0) {
        "${(km * 1000).roundToInt()} m"
    } else if (km < 100.0) {
        "%.1f km".format(km)
    } else {
        "${km.roundToInt()} km"
    }
}

// ─── Location helper (re-uses the pattern from OfflineMapScreen) ────────────
fun getBestLastKnownLocationForDisasters(context: Context): Location? {
    val hasFine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null

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
    } catch (e: SecurityException) {
        null
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

// ─── Main Screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentDisastersScreen(
    navController: NavController,
    viewModel: RecentDisastersViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // User location is fetched lazily once permission is available and pushed to
    // the ViewModel so the nearby/other partition stays in sync.
    var userLocation by remember { mutableStateOf<Location?>(null) }

    val refreshLocation = {
        getBestLastKnownLocationForDisasters(context)?.let { loc ->
            userLocation = loc
            viewModel.updateLocation(loc.latitude, loc.longitude)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) refreshLocation()
    }

    val requestLocation = {
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) refreshLocation()
    }

    val hasEvents = uiState.nearbyEvents.isNotEmpty() || uiState.otherEvents.isNotEmpty()

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_recent_disasters_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = {
                        viewModel.loadData(forceRefresh = true)
                        refreshLocation()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.recent_disasters_refresh_cd)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Segmented Tab Selector — your-country tab first, Global second.
            // Shown only once the user's country has been detected.
            if (uiState.hasRegionalTab) {
                SegmentedTabSelector(
                    selectedTab = uiState.selectedTab,
                    regionalLabel = uiState.regionalTabLabel,
                    globalLabel = stringResource(R.string.recent_disasters_tab_global),
                    onTabSelected = { viewModel.changeTab(it) }
                )
            }

            // Search Bar Row (Modern, pill-shaped design)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.recent_disasters_search_hint),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.recent_disasters_clear_cd),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }

            // Category & High Severity Filter Chips
            if (uiState.selectedTab == "Global") {
                CategorySelector(
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = { viewModel.selectCategory(it) },
                    selectedAlertLevel = uiState.selectedAlertLevel,
                    onAlertLevelSelected = { viewModel.selectAlertLevel(it) }
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isRedOnly = uiState.selectedAlertLevel == "Red"
                    FilterChip(
                        selected = isRedOnly,
                        onClick = { viewModel.selectAlertLevel(if (isRedOnly) "All" else "Red") },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color(0xFFD32F2F), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = stringResource(R.string.recent_disasters_filter_high_severity), fontSize = 12.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFD32F2F).copy(alpha = 0.12f),
                            selectedLabelColor = Color(0xFFD32F2F)
                        )
                    )
                }
            }

            // Stale-data banner — shown ONLY when a refresh failed and we are
            // serving cached data. Fresh-cache reads do NOT trigger this.
            if (uiState.isStale) {
                OfflineBanner()
            }

            // Main Content Area with pull-to-refresh
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = {
                    viewModel.loadData(forceRefresh = true)
                    refreshLocation()
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    uiState.isLoading -> DisasterSkeletonList()

                    !uiState.hasAnyEvents && uiState.error != null ->
                        ErrorState(
                            error = uiState.error!!,
                            onRetry = { viewModel.loadData(forceRefresh = true) }
                        )

                    !hasEvents -> EmptyState(isFiltered = uiState.hasAnyEvents)

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // ── Nearby Section ──────────────────────────────────
                        item { NearbyHeader() }

                        if (!uiState.hasLocation) {
                            item { NearbyNoLocationCard(onEnableLocation = requestLocation) }
                        } else if (uiState.nearbyEvents.isEmpty()) {
                            item { NearbySafeCard() }
                        } else {
                            items(
                                uiState.nearbyEvents,
                                key = { "n-${it.id}-${it.eventTimeMillis}" }
                            ) { event ->
                                NearbyDisasterCard(
                                    event = event,
                                    userLocation = userLocation,
                                    onClick = { viewModel.showDetail(event) }
                                )
                            }
                        }

                        // ── All Events Section ──────────────────────────────
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.recent_disasters_all_events_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (uiState.lastUpdatedMillis > 0L) {
                                    Text(
                                        text = stringResource(
                                            R.string.recent_disasters_last_updated,
                                            relativeTime(uiState.lastUpdatedMillis)
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        if (uiState.otherEvents.isEmpty()) {
                            item { OtherEventsEmptyRow(isFiltered = uiState.hasAnyEvents) }
                        } else {
                            items(
                                uiState.otherEvents,
                                key = { "o-${it.id}-${it.eventTimeMillis}" }
                            ) { event ->
                                DisasterEventItem(
                                    event = event,
                                    userLocation = userLocation,
                                    onClick = { viewModel.showDetail(event) }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }

                        // ── Source attribution + disclaimer footer ──────────
                        item { SourceFooter(source = uiState.primarySource) }
                    }
                }
            }
        }

        // Details Bottom Sheet
        if (uiState.detailEvent != null) {
            val event = uiState.detailEvent!!
            ModalBottomSheet(
                onDismissRequest = { viewModel.showDetail(null) },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                DisasterDetailsSheetContent(
                    event = event,
                    userLocation = userLocation,
                    navController = navController,
                    onDismiss = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                viewModel.showDetail(null)
                            }
                        }
                    }
                )
            }
        }
    }
}

// ─── Segmented Tab Selector ─────────────────────────────────────────────────

@Composable
fun SegmentedTabSelector(
    selectedTab: String,
    regionalLabel: String,
    globalLabel: String,
    onTabSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Your-country tab first, then Global.
        listOf(
            "Local" to regionalLabel,
            "Global" to globalLabel
        ).forEach { (tabName, label) ->
            val isSelected = selectedTab == tabName
            val background = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
            val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(background)
                    .clickable { onTabSelected(tabName) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = textColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ─── Category Selector ──────────────────────────────────────────────────────

@Composable
fun CategorySelector(
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    selectedAlertLevel: String,
    onAlertLevelSelected: (String) -> Unit
) {
    val categories = listOf(
        "All" to R.string.recent_disasters_filter_all,
        "EQ" to R.string.recent_disasters_filter_eq,
        "FL" to R.string.recent_disasters_filter_fl,
        "TC" to R.string.recent_disasters_filter_tc,
        "VO" to R.string.recent_disasters_filter_vo,
        "WF" to R.string.recent_disasters_filter_wf,
        "DR" to R.string.recent_disasters_filter_dr
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isRedOnly = selectedAlertLevel == "Red"
        FilterChip(
            selected = isRedOnly,
            onClick = { onAlertLevelSelected(if (isRedOnly) "All" else "Red") },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFFD32F2F), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.recent_disasters_filter_high_severity), fontSize = 12.sp)
                }
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFFD32F2F).copy(alpha = 0.12f),
                selectedLabelColor = Color(0xFFD32F2F)
            )
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        )

        categories.forEach { (code, stringResId) ->
            FilterChip(
                selected = selectedCategory == code,
                onClick = { onCategorySelected(code) },
                label = { Text(text = stringResource(stringResId), fontSize = 12.sp) }
            )
        }
    }
}

// ─── Severity / type helpers ──────────────────────────────────────────────────

fun alertColorFor(alertLevel: String): Color = when (alertLevel.lowercase()) {
    "red" -> Color(0xFFD32F2F)
    "orange" -> Color(0xFFF57C00)
    "green" -> Color(0xFF388E3C)
    else -> Color.Gray
}

@Composable
fun friendlyEventType(type: String): String = when (type.uppercase()) {
    "EQ" -> stringResource(R.string.recent_disasters_type_eq)
    "FL" -> stringResource(R.string.recent_disasters_type_fl)
    "TC" -> stringResource(R.string.recent_disasters_type_tc)
    "VO" -> stringResource(R.string.recent_disasters_type_vo)
    "WF" -> stringResource(R.string.recent_disasters_type_wf)
    "DR" -> stringResource(R.string.recent_disasters_type_dr)
    else -> type
}

/** Leading badge: shows magnitude (e.g. "M5.2") for earthquakes, else the type icon. */
@Composable
fun EventLeadingBadge(
    event: DisasterEvent,
    alertColor: Color,
    boxSize: Dp,
    iconSize: Dp,
    magFontSize: TextUnit
) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .background(alertColor.copy(alpha = 0.12f), RoundedCornerShape(if (boxSize >= 40.dp) 12.dp else 8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val mag = event.magnitude
        if (event.eventType.equals("EQ", ignoreCase = true) && mag != null && mag > 0.0) {
            Text(
                text = "M${"%.1f".format(mag)}",
                color = alertColor,
                fontWeight = FontWeight.ExtraBold,
                fontSize = magFontSize,
                maxLines = 1
            )
        } else {
            Icon(
                imageVector = getEventTypeIcon(event.eventType),
                contentDescription = null,
                tint = alertColor,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

// ─── Disaster Event List Item (with badge + distance + relative time) ────────

@Composable
fun DisasterEventItem(
    event: DisasterEvent,
    userLocation: Location?,
    onClick: () -> Unit
) {
    val alertColor = alertColorFor(event.alertLevel)

    val distanceText = if (userLocation != null && event.latitude != 0.0 && event.longitude != 0.0) {
        formatDistanceKm(
            haversineDistanceKm(userLocation.latitude, userLocation.longitude, event.latitude, event.longitude)
        )
    } else null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${event.country.uppercase()} • ${friendlyEventType(event.eventType)}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            val timeText = if (event.eventTimeMillis > 0L) {
                relativeTime(event.eventTimeMillis)
            } else {
                formatIsoDate(event.fromDate)
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventLeadingBadge(
                event = event,
                alertColor = alertColor,
                boxSize = 32.dp,
                iconSize = 18.dp,
                magFontSize = 11.sp
            )
            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.severityText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = event.severityText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            if (distanceText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.recent_disasters_distance_away, distanceText),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

// ─── Detail Bottom Sheet ────────────────────────────────────────────────────

@Composable
fun DisasterDetailsSheetContent(
    event: DisasterEvent,
    userLocation: Location?,
    navController: NavController,
    onDismiss: () -> Unit
) {
    val alertColor = alertColorFor(event.alertLevel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        if (event.alertLevel.equals("Red", ignoreCase = true)) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F).copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFD32F2F),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.recent_disasters_fema_red_alert_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFD32F2F)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (event.eventType.equals("EQ", ignoreCase = true)) {
                                stringResource(R.string.recent_disasters_fema_red_alert_desc_local)
                            } else {
                                stringResource(R.string.recent_disasters_fema_red_alert_desc_global)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD32F2F).copy(alpha = 0.85f),
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = getEventTypeIcon(event.eventType),
                    contentDescription = null,
                    tint = alertColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${friendlyEventType(event.eventType)} • ${event.country}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = alertColor
                )
            }

            Box(
                modifier = Modifier
                    .background(alertColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = event.alertLevel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = alertColor
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = event.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Time metadata card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.recent_disasters_impact_date,
                        formatIsoDate(event.fromDate),
                        formatIsoDate(event.toDate)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (event.severityText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.recent_disasters_detail_severity, event.severityText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = event.description.takeIf { it.isNotBlank() } ?: event.title,
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Direction dial + coordinates + distance
        val hasCoords = event.latitude != 0.0 || event.longitude != 0.0
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (userLocation != null && hasCoords) {
                EventDirectionDial(userLocation = userLocation, event = event, alertColor = alertColor)
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = alertColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.recent_disasters_impact_coordinates,
                            event.latitude,
                            event.longitude
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (userLocation != null && hasCoords) {
                    val km = haversineDistanceKm(
                        userLocation.latitude, userLocation.longitude,
                        event.latitude, event.longitude
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .background(alertColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.recent_disasters_distance_away, formatDistanceKm(km)),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = alertColor
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.recent_disasters_source, event.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Show on Map (full width)
        Button(
            onClick = {
                onDismiss()
                navController.navigate("offline_map?lat=${event.latitude}&lng=${event.longitude}")
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.recent_disasters_action_map),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(text = stringResource(R.string.close))
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ─── Direction dial (offline compass to the event) ───────────────────────────

@Composable
fun EventDirectionDial(
    userLocation: Location,
    event: DisasterEvent,
    alertColor: Color
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val primary = MaterialTheme.colorScheme.primary
    val bearing = remember(userLocation.latitude, userLocation.longitude, event.latitude, event.longitude) {
        bearingDegrees(userLocation.latitude, userLocation.longitude, event.latitude, event.longitude)
    }

    Canvas(modifier = Modifier.size(92.dp)) {
        val radius = min(size.width, size.height) / 2f * 0.82f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = gridColor, radius = radius, center = center, style = Stroke(width = 2f))
        drawCircle(color = gridColor, radius = radius * 0.5f, center = center, style = Stroke(width = 1.5f))
        drawLine(gridColor, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), strokeWidth = 1.5f)
        drawLine(gridColor, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), strokeWidth = 1.5f)

        val rad = Math.toRadians(bearing)
        val dotX = center.x + (radius * 0.78f) * sin(rad).toFloat()
        val dotY = center.y - (radius * 0.78f) * cos(rad).toFloat()
        drawLine(alertColor.copy(alpha = 0.6f), center, Offset(dotX, dotY), strokeWidth = 3f)
        drawCircle(alertColor, radius = 7f, center = Offset(dotX, dotY))
        drawCircle(primary, radius = 5f, center = center)
    }
}

fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(Math.toRadians(lat2))
    val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
        sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

// ─── Banners / states ────────────────────────────────────────────────────────

@Composable
fun OfflineBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFE65100))
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color.White
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = stringResource(R.string.recent_disasters_offline_mode),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 14.sp
            )
            Text(
                text = stringResource(R.string.recent_disasters_offline_message),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun ErrorState(
    error: DisasterErrorType,
    onRetry: () -> Unit
) {
    val message = when (error) {
        DisasterErrorType.TIMEOUT -> stringResource(R.string.recent_disasters_error_timeout)
        else -> stringResource(R.string.recent_disasters_error_offline)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(12.dp)) {
            Text(text = stringResource(R.string.recent_disasters_retry))
        }
    }
}

@Composable
fun EmptyState(isFiltered: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(
                if (isFiltered) R.string.recent_disasters_no_match else R.string.recent_disasters_no_events
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun OtherEventsEmptyRow(isFiltered: Boolean) {
    Text(
        text = stringResource(
            if (isFiltered) R.string.recent_disasters_no_match else R.string.recent_disasters_no_events
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
    )
}

@Composable
fun SourceFooter(source: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.recent_disasters_source, source),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.recent_disasters_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            lineHeight = 14.sp
        )
    }
}

// ─── Skeleton loading list ────────────────────────────────────────────────────

@Composable
fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    return Brush.linearGradient(
        colors = listOf(base.copy(alpha = 0.35f), base.copy(alpha = 0.12f), base.copy(alpha = 0.35f)),
        start = Offset(translate - 350f, 0f),
        end = Offset(translate, 0f)
    )
}

@Composable
fun DisasterSkeletonList() {
    val brush = shimmerBrush()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        repeat(7) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(brush)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(brush)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.45f)
                            .height(11.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(brush)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(48.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(brush)
                )
            }
        }
    }
}

// ─── Nearby Section Composables ─────────────────────────────────────────────

@Composable
fun NearbyHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.recent_disasters_nearby_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun NearbySafeCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF388E3C).copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF388E3C).copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF388E3C)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.recent_disasters_nearby_safe_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF388E3C)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.recent_disasters_nearby_safe_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF388E3C).copy(alpha = 0.8f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun NearbyNoLocationCard(onEnableLocation: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onEnableLocation),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.recent_disasters_nearby_no_location),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.recent_disasters_enable_location),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun NearbyDisasterCard(
    event: DisasterEvent,
    userLocation: Location?,
    onClick: () -> Unit
) {
    val alertColor = alertColorFor(event.alertLevel)

    val distanceText = if (userLocation != null && event.latitude != 0.0 && event.longitude != 0.0) {
        formatDistanceKm(
            haversineDistanceKm(userLocation.latitude, userLocation.longitude, event.latitude, event.longitude)
        )
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = alertColor.copy(alpha = 0.06f)
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EventLeadingBadge(
                event = event,
                alertColor = alertColor,
                boxSize = 40.dp,
                iconSize = 22.dp,
                magFontSize = 13.sp
            )
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (event.severityText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = event.severityText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (distanceText != null) {
                    Box(
                        modifier = Modifier
                            .background(alertColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = distanceText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = alertColor
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = event.alertLevel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = alertColor.copy(alpha = 0.7f),
                    fontSize = 9.sp
                )
            }
        }
    }
}

// ─── Time helpers ──────────────────────────────────────────────────────────────

@Composable
fun relativeTime(millis: Long): String {
    if (millis <= 0L) return ""
    val diff = (System.currentTimeMillis() - millis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> stringResource(R.string.recent_disasters_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.recent_disasters_time_minutes, (diff / 60_000L).toInt())
        diff < 86_400_000L -> stringResource(R.string.recent_disasters_time_hours, (diff / 3_600_000L).toInt())
        diff < 604_800_000L -> stringResource(R.string.recent_disasters_time_days, (diff / 86_400_000L).toInt())
        else -> formatAbsoluteMillis(millis)
    }
}

fun formatAbsoluteMillis(millis: Long): String {
    return try {
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(millis))
    } catch (e: Exception) {
        ""
    }
}

fun formatIsoDate(dateStr: String): String {
    if (dateStr.isBlank()) return ""
    return try {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val date = inputFormat.parse(dateStr)
        if (date != null) {
            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(date)
        } else {
            dateStr.replace("T", " ")
        }
    } catch (e: Exception) {
        dateStr.replace("T", " ")
    }
}
