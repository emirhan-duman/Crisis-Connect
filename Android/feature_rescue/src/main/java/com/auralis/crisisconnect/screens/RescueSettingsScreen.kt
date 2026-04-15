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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val uiState by viewModel.uiState.collectAsState()
    val hasInternet = rememberInternetAvailability()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.rescue_settings_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
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
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
                        )
                    )
                )
        ) {
            if (!uiState.isLoaded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    RescueSettingsSwitchCard(
                        icon = Icons.Default.Refresh,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.rescue_setting_auto_scan_title),
                        description = stringResource(R.string.rescue_setting_auto_scan_description),
                        checked = uiState.autoStartScanning,
                        onCheckedChange = viewModel::setAutoStartScanning
                    )
                }

                item {
                    RescueSettingsSwitchCard(
                        icon = Icons.Default.Sync,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.rescue_setting_auto_connect_title),
                        description = stringResource(R.string.rescue_setting_auto_connect_description),
                        checked = uiState.autoConnectToScannedBroadcasts,
                        onCheckedChange = viewModel::setAutoConnectToScannedBroadcasts
                    )
                }

                item {
                    RescueSettingsSwitchCard(
                        icon = Icons.Default.Warning,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.rescue_setting_only_active_title),
                        description = stringResource(R.string.rescue_setting_only_active_description),
                        checked = uiState.showOnlyActiveSignals,
                        onCheckedChange = viewModel::setShowOnlyActiveSignals
                    )
                }

                item {
                    RescueSettingsSwitchCard(
                        icon = Icons.Default.Link,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.rescue_setting_mesh_always_on_title),
                        description = stringResource(R.string.rescue_setting_mesh_always_on_description),
                        checked = uiState.meshAlwaysOn,
                        supportingNote = if (uiState.canUseMeshAlwaysOn) {
                            null
                        } else {
                            stringResource(R.string.rescue_mesh_error_unauthorized)
                        },
                        enabled = uiState.canUseMeshAlwaysOn,
                        onCheckedChange = viewModel::setMeshAlwaysOnEnabled
                    )
                }

                item {
                    RescueSettingsSwitchCard(
                        icon = null,
                        iconResId = R.drawable.dcslogo,
                        iconTint = Color(0xFF2E6FE7),
                        title = stringResource(R.string.rescue_setting_crisis_link_title),
                        description = stringResource(R.string.rescue_setting_crisis_link_description),
                        checked = uiState.crisisLinkEnabled,
                        supportingNote = when {
                            !uiState.canUseCrisisLink -> stringResource(R.string.rescue_crisis_link_status_unauthorized)
                            !uiState.crisisLinkEnabled -> stringResource(R.string.rescue_crisis_link_status_off)
                            !hasInternet -> {
                                stringResource(R.string.rescue_crisis_link_status_ready) + " • " +
                                    stringResource(R.string.rescue_setting_crisis_link_offline_behavior)
                            }
                            else -> {
                                stringResource(R.string.rescue_crisis_link_status_ready)
                            }
                        },
                        enabled = uiState.canUseCrisisLink,
                        onCheckedChange = viewModel::setCrisisLinkEnabled,
                        expandedContent = {
                            CrisisLinkLiveLocationSection(
                                enabled = uiState.crisisLinkLiveLocationEnabled,
                                selectedIntervalSeconds = uiState.crisisLinkLiveLocationIntervalSeconds,
                                onEnabledChange = viewModel::setCrisisLinkLiveLocationEnabled,
                                onIntervalSelected = viewModel::setCrisisLinkLiveLocationIntervalSeconds
                            )
                        }
                    )
                }

                item {
                    RescueSettingsSwitchCard(
                        icon = Icons.Default.LocationOn,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.rescue_setting_show_address_title),
                        description = stringResource(R.string.rescue_setting_show_address_description),
                        checked = uiState.showDeviceAddress,
                        onCheckedChange = viewModel::setShowDeviceAddress
                    )
                }
            }
        }
    }
}

@Composable
private fun RescueSettingsSwitchCard(
    icon: ImageVector?,
    @AnyRes iconResId: Int? = null,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    supportingNote: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    expandedContent: (@Composable () -> Unit)? = null,
) {
    val borderColor by animateColorAsState(
        targetValue = if (checked) {
            iconTint.copy(alpha = 0.40f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f)
        },
        label = "rescue_settings_border_color"
    )
    val iconBgColor by animateColorAsState(
        targetValue = if (checked) {
            iconTint.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
        },
        label = "rescue_settings_icon_bg"
    )
    val switchTrackColor by animateColorAsState(
        targetValue = if (checked) {
            iconTint
        } else {
            MaterialTheme.colorScheme.outline
        },
        label = "rescue_settings_switch_track"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (checked) 6.dp else 2.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            iconTint.copy(alpha = if (checked) 0.10f else 0.04f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = iconBgColor
                    ) {
                        if (iconResId != null) {
                            Image(
                                painter = painterResource(id = iconResId),
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(28.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .size(24.dp),
                                tint = iconTint
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        supportingNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Switch(
                            checked = checked,
                            enabled = enabled,
                            onCheckedChange = onCheckedChange,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = switchTrackColor,
                                checkedBorderColor = switchTrackColor.copy(alpha = 0.68f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                uncheckedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Box(
                            modifier = Modifier
                                .width(34.dp)
                                .height(3.dp)
                                .background(
                                    color = if (checked) {
                                        switchTrackColor
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = RoundedCornerShape(999.dp)
                                )
                        )
                    }
                }

                AnimatedVisibility(
                    visible = checked && enabled && expandedContent != null
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 70.dp, end = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        expandedContent?.invoke()
                    }
                }
            }
        }
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
