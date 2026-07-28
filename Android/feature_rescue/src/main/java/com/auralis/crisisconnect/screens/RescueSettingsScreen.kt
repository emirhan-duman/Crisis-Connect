package com.auralis.crisisconnect.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.AnyRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBottomBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescueSettingsScreen(
    navController: NavController,
    onBottomBarRouteSelected: ((String) -> Unit)? = null
) {
    val viewModel: RescueSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hasInternet = rememberInternetAvailability()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            RescueSettingsTopBar(onNavigateBack = { navController.popBackStack() })
        },
        bottomBar = {
            AppBottomBar(
                navController = navController,
                onRouteSelected = onBottomBarRouteSelected
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (!uiState.isLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Spacer for nice alignment under top bar
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Section 1: Scanning & Connection Configurations
                item {
                    SettingsSectionHeader(title = stringResource(R.string.rescue_settings_section_scanning))
                    GroupedSettingsCard {
                        // Auto-Scan Broadcasts
                        SettingsRow(
                            icon = Icons.Default.Refresh,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.rescue_setting_auto_scan_title),
                            description = stringResource(R.string.rescue_setting_auto_scan_description),
                            checked = uiState.autoStartScanning,
                            onCheckedChange = viewModel::setAutoStartScanning
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Auto-Connect to Scanned Broadcasts
                        SettingsRow(
                            icon = Icons.Default.Sync,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.rescue_setting_auto_connect_title),
                            description = stringResource(R.string.rescue_setting_auto_connect_description),
                            checked = uiState.autoConnectToScannedBroadcasts,
                            onCheckedChange = viewModel::setAutoConnectToScannedBroadcasts
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Show Active Signals Only
                        SettingsRow(
                            icon = Icons.Default.Warning,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.rescue_setting_only_active_title),
                            description = stringResource(R.string.rescue_setting_only_active_description),
                            checked = uiState.showOnlyActiveSignals,
                            onCheckedChange = viewModel::setShowOnlyActiveSignals
                        )
                    }
                }

                // Section 2: Emergency Connectivity Configurations
                item {
                    SettingsSectionHeader(title = stringResource(R.string.rescue_settings_section_connectivity))
                    GroupedSettingsCard {
                        // RescueMesh Always On
                        SettingsRow(
                            icon = Icons.Default.Link,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.rescue_setting_mesh_always_on_title),
                            description = stringResource(R.string.rescue_setting_mesh_always_on_description),
                            checked = uiState.meshAlwaysOn,
                            onCheckedChange = viewModel::setMeshAlwaysOnEnabled,
                            supportingNote = if (uiState.canUseMeshAlwaysOn) {
                                null
                            } else {
                                stringResource(R.string.rescue_mesh_error_unauthorized)
                            },
                            enabled = uiState.canUseMeshAlwaysOn
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Crisis Link Gateway
                        val crisisLinkStatusNote = when {
                            !uiState.canUseCrisisLink -> stringResource(R.string.rescue_crisis_link_status_unauthorized)
                            !uiState.crisisLinkEnabled -> stringResource(R.string.rescue_crisis_link_status_off)
                            !hasInternet -> {
                                stringResource(R.string.rescue_crisis_link_status_ready) + " • " +
                                    stringResource(R.string.rescue_setting_crisis_link_offline_behavior)
                            }
                            else -> {
                                stringResource(R.string.rescue_crisis_link_status_ready)
                            }
                        }

                        SettingsRow(
                            icon = null,
                            iconResId = R.drawable.dcslogo,
                            iconTint = Color(0xFF2E6FE7),
                            title = stringResource(R.string.rescue_setting_crisis_link_title),
                            description = stringResource(R.string.rescue_setting_crisis_link_description),
                            checked = uiState.crisisLinkEnabled,
                            onCheckedChange = viewModel::setCrisisLinkEnabled,
                            supportingNote = crisisLinkStatusNote,
                            enabled = uiState.canUseCrisisLink
                        )

                        // Crisis Link Live Location Expanded Section
                        AnimatedVisibility(
                            visible = uiState.crisisLinkEnabled && uiState.canUseCrisisLink,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 70.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CrisisLinkLiveLocationSection(
                                        enabled = uiState.crisisLinkLiveLocationEnabled,
                                        selectedIntervalSeconds = uiState.crisisLinkLiveLocationIntervalSeconds,
                                        onEnabledChange = viewModel::setCrisisLinkLiveLocationEnabled,
                                        onIntervalSelected = viewModel::setCrisisLinkLiveLocationIntervalSeconds
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 3: Display & UI Options
                item {
                    SettingsSectionHeader(title = stringResource(R.string.rescue_settings_section_display))
                    GroupedSettingsCard {
                        // Show Device Address
                        SettingsRow(
                            icon = Icons.Default.LocationOn,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.rescue_setting_show_address_title),
                            description = stringResource(R.string.rescue_setting_show_address_description),
                            checked = uiState.showDeviceAddress,
                            onCheckedChange = viewModel::setShowDeviceAddress
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Show Rescue in Navbar
                        SettingsRow(
                            icon = null,
                            iconResId = R.drawable.searchandrescue,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.rescue_setting_show_in_navbar_title),
                            description = stringResource(R.string.rescue_setting_show_in_navbar_description),
                            checked = uiState.showRescueInNavbar,
                            onCheckedChange = viewModel::setShowRescueInNavbar
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RescueSettingsTopBar(
    onNavigateBack: () -> Unit
) {
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.15.sp
    )

    CenterAlignedTopAppBar(
        title = {
            Text(
                text = stringResource(R.string.rescue_settings_title),
                style = titleStyle,
                color = if (isDarkTheme) Color.White else Color(0xFF0F172A),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
        )
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.25.sp
        ),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun GroupedSettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // Clean flat M3 look
        content = content
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector?,
    @AnyRes iconResId: Int? = null,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingNote: String? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val animatedBgColor by animateColorAsState(
        targetValue = if (checked && enabled) {
            iconTint.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        label = "icon_bg_color"
    )

    val animatedIconTint by animateColorAsState(
        targetValue = if (enabled) {
            iconTint
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
        label = "icon_tint"
    )

    val rowAlpha = if (enabled) 1f else 0.5f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp) // Outer margin inside card
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp), // Inner padding
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = animatedBgColor
        ) {
            if (iconResId != null) {
                Image(
                    painter = painterResource(id = iconResId),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(24.dp),
                    contentScale = ContentScale.Fit
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedIconTint,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 17.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = rowAlpha)
            )
            supportingNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = rowAlpha)
                )
            }
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = iconTint,
                uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                uncheckedThumbColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

@Composable
private fun CrisisLinkLiveLocationSection(
    enabled: Boolean,
    selectedIntervalSeconds: Int,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalSelected: (Int) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.rescue_setting_crisis_link_live_location_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.rescue_setting_crisis_link_live_location_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }

        AnimatedVisibility(visible = enabled) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.rescue_setting_crisis_link_live_location_interval_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LIVE_LOCATION_INTERVAL_OPTIONS_SECONDS.forEach { intervalSeconds ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            selected = selectedIntervalSeconds == intervalSeconds,
                            onClick = { onIntervalSelected(intervalSeconds) },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.rescue_setting_crisis_link_live_location_interval_option_seconds,
                                        intervalSeconds
                                    ),
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
            }
        }
    }
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

private fun Context.hasUsableInternetConnection(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    return manager.hasUsableInternetConnection()
}

private fun ConnectivityManager.hasUsableInternetConnection(): Boolean {
    val capabilities = getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private val LIVE_LOCATION_INTERVAL_OPTIONS_SECONDS = listOf(30, 60, 90, 300)
