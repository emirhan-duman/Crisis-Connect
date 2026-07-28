package com.auralis.crisisconnect.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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

                // Section 0: Privacy
                item {
                    SettingsSectionHeader(title = stringResource(R.string.advanced_settings_section_privacy))
                    GroupedSettingsCard {
                        SettingsRow(
                            icon = Icons.Default.Visibility,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.advanced_setting_share_last_seen_title),
                            description = stringResource(R.string.advanced_setting_share_last_seen_description),
                            checked = uiState.shareLastSeenEnabled,
                            onCheckedChange = viewModel::setShareLastSeenEnabled
                        )
                    }
                }

                // Section 1: Mesh Networking Configuration
                item {
                    SettingsSectionHeader(title = stringResource(R.string.advanced_settings_section_mesh))
                    GroupedSettingsCard {
                        // Public Mesh Mode
                        SettingsRow(
                            icon = Icons.Default.Link,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.advanced_setting_public_mesh_title),
                            description = stringResource(R.string.advanced_setting_public_mesh_description),
                            checked = uiState.publicMeshEnabled,
                            onCheckedChange = viewModel::setPublicMeshEnabled
                        )

                        // GATT Mesh Notifications (Only visible if Public Mesh Mode is active)
                        AnimatedVisibility(
                            visible = uiState.publicMeshEnabled,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                )
                                SettingsRow(
                                    icon = Icons.Default.Notifications,
                                    iconTint = MaterialTheme.colorScheme.secondary,
                                    title = stringResource(R.string.advanced_setting_gatt_mesh_notifications_title),
                                    description = stringResource(R.string.advanced_setting_gatt_mesh_notifications_description),
                                    checked = uiState.gattMeshNotificationsEnabled,
                                    onCheckedChange = viewModel::setGattMeshNotificationsEnabled,
                                    modifier = Modifier.padding(start = 12.dp) // Subtle indent
                                )
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // High Range Mode
                        SettingsRow(
                            icon = Icons.Default.LocationOn,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            title = stringResource(R.string.advanced_setting_high_range_title),
                            description = stringResource(R.string.advanced_setting_high_range_description),
                            checked = uiState.highRangeModeEnabled,
                            onCheckedChange = viewModel::setHighRangeModeEnabled,
                            enabled = !uiState.highRangeModeForcedByBleContacts
                        )

                        // High Range Mode Lock Warning
                        AnimatedVisibility(
                            visible = uiState.highRangeModeForcedByBleContacts,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.24f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.advanced_setting_high_range_locked_reason),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 2: Performance & Optimization
                item {
                    SettingsSectionHeader(title = stringResource(R.string.advanced_settings_section_performance))
                    GroupedSettingsCard {
                        // Battery Saver
                        SettingsRow(
                            icon = Icons.Default.Refresh,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.advanced_setting_battery_saver_title),
                            description = stringResource(R.string.advanced_setting_battery_saver_description),
                            checked = uiState.batterySaverMode,
                            onCheckedChange = viewModel::setBatterySaverMode
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Message Delivery Retries
                        SettingsRow(
                            icon = Icons.Default.Sync,
                            iconTint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.advanced_setting_delivery_retry_title),
                            description = stringResource(R.string.advanced_setting_delivery_retry_description),
                            checked = uiState.deliveryRetryEnabled,
                            onCheckedChange = viewModel::setDeliveryRetryEnabled
                        )
                    }
                }

                // Section 3: Diagnostics & Labs
                item {
                    SettingsSectionHeader(title = stringResource(R.string.advanced_settings_section_diagnostics))
                    GroupedSettingsCard {
                        // Diagnostics Upload
                        SettingsRow(
                            icon = Icons.Default.Warning,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            title = stringResource(R.string.advanced_setting_diagnostics_upload_title),
                            description = stringResource(R.string.advanced_setting_diagnostics_upload_description),
                            checked = uiState.diagnosticsUploadEnabled,
                            onCheckedChange = viewModel::setDiagnosticsUploadEnabled
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )

                        // Experimental Features
                        SettingsRow(
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedSettingsTopBar(
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
                text = stringResource(R.string.advanced_settings_title),
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
internal fun SettingsSectionHeader(title: String) {
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
internal fun GroupedSettingsCard(
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
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // Clean flat look prevents shadow bugs
        content = content
    )
}

@Composable
internal fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
            .padding(horizontal = 6.dp, vertical = 4.dp) // Safe outer margin inside the card
            .clip(RoundedCornerShape(12.dp)) // Ripple will match this clean rounded shape
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = animatedIconTint,
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp)
            )
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
