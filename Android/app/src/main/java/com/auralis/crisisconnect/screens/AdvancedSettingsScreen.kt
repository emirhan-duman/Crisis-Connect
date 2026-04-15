package com.auralis.crisisconnect.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
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
fun AdvancedSettingsScreen(navController: NavController) {
    val viewModel: AdvancedSettingsViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            AdvancedSettingsTopBar(onNavigateBack = { navController.popBackStack() })
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.background
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.advanced_settings_screen_description),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    AdvancedSettingsPublicMeshCard(
                        publicMeshEnabled = uiState.publicMeshEnabled,
                        gattMeshNotificationsEnabled = uiState.gattMeshNotificationsEnabled,
                        onPublicMeshCheckedChange = viewModel::setPublicMeshEnabled,
                        onGattMeshNotificationsCheckedChange = viewModel::setGattMeshNotificationsEnabled
                    )
                }

                item {
                    AdvancedSettingsSwitchCard(
                        icon = Icons.Default.LocationOn,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.advanced_setting_high_range_title),
                        description = stringResource(R.string.advanced_setting_high_range_description),
                        supportingNote = if (uiState.highRangeModeForcedByBleContacts) {
                            stringResource(R.string.advanced_setting_high_range_locked_reason)
                        } else {
                            null
                        },
                        checked = uiState.highRangeModeEnabled,
                        onCheckedChange = viewModel::setHighRangeModeEnabled,
                        enabled = !uiState.highRangeModeForcedByBleContacts
                    )
                }

                item {
                    AdvancedSettingsSwitchCard(
                        icon = Icons.Default.Refresh,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.advanced_setting_battery_saver_title),
                        description = stringResource(R.string.advanced_setting_battery_saver_description),
                        checked = uiState.batterySaverMode,
                        onCheckedChange = viewModel::setBatterySaverMode
                    )
                }

                item {
                    AdvancedSettingsSwitchCard(
                        icon = Icons.Default.Sync,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = stringResource(R.string.advanced_setting_delivery_retry_title),
                        description = stringResource(R.string.advanced_setting_delivery_retry_description),
                        checked = uiState.deliveryRetryEnabled,
                        onCheckedChange = viewModel::setDeliveryRetryEnabled
                    )
                }

                item {
                    AdvancedSettingsSwitchCard(
                        icon = Icons.Default.Warning,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        title = stringResource(R.string.advanced_setting_diagnostics_upload_title),
                        description = stringResource(R.string.advanced_setting_diagnostics_upload_description),
                        checked = uiState.diagnosticsUploadEnabled,
                        onCheckedChange = viewModel::setDiagnosticsUploadEnabled
                    )
                }

                item {
                    AdvancedSettingsSwitchCard(
                        icon = Icons.Default.Settings,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        title = stringResource(R.string.advanced_setting_experimental_features_title),
                        description = stringResource(R.string.advanced_setting_experimental_features_description),
                        checked = uiState.experimentalFeaturesEnabled,
                        onCheckedChange = viewModel::setExperimentalFeaturesEnabled
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSettingsTopBar(
    onNavigateBack: () -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    val scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    val isDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val titleStyle = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp
    )

    Column {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.advanced_settings_title),
                    style = titleStyle,
                    color = if (isDarkTheme) Color.White else Color(0xFF042C43),
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
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor
            )
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
        )
    }
}

@Composable
private fun AdvancedSettingsPublicMeshCard(
    publicMeshEnabled: Boolean,
    gattMeshNotificationsEnabled: Boolean,
    onPublicMeshCheckedChange: (Boolean) -> Unit,
    onGattMeshNotificationsCheckedChange: (Boolean) -> Unit,
) {
    val primaryTint = MaterialTheme.colorScheme.secondary
    val borderColor by animateColorAsState(
        targetValue = if (publicMeshEnabled) {
            primaryTint.copy(alpha = 0.40f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.86f)
        },
        label = "advanced_public_mesh_border"
    )
    val primaryIconBgColor by animateColorAsState(
        targetValue = if (publicMeshEnabled) {
            primaryTint.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
        },
        label = "advanced_public_mesh_icon_bg"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (publicMeshEnabled) 3.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = primaryIconBgColor
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(20.dp),
                    tint = primaryTint
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.advanced_setting_public_mesh_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.advanced_setting_public_mesh_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = publicMeshEnabled,
                onCheckedChange = onPublicMeshCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = primaryTint,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            )
        }

        if (publicMeshEnabled) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(7.dp)
                            .size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.advanced_setting_gatt_mesh_notifications_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.advanced_setting_gatt_mesh_notifications_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = gattMeshNotificationsEnabled,
                    onCheckedChange = onGattMeshNotificationsCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettingsSwitchCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    supportingNote: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val borderColor by animateColorAsState(
        targetValue = if (checked) {
            iconTint.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.80f)
        },
        label = "advanced_settings_border_color"
    )
    val iconBgColor by animateColorAsState(
        targetValue = if (checked) {
            iconTint.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
        },
        label = "advanced_settings_icon_bg"
    )
    val switchTrackColor by animateColorAsState(
        targetValue = if (checked) {
            iconTint
        } else {
            MaterialTheme.colorScheme.outline
        },
        label = "advanced_settings_switch_track"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (checked) 3.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = iconBgColor
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(9.dp)
                        .size(20.dp),
                    tint = iconTint
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                    }
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    }
                )
                supportingNote
                    ?.takeIf { it.isNotBlank() }
                    ?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = switchTrackColor,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            )
        }
    }
}
