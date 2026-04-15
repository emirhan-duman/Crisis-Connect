package com.auralis.crisisconnect.screens.Tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.screens.Tools.WhistleMode
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhistleScreen(navController: NavController) {
    val viewModel: WhistleViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    var showInfo by remember { mutableStateOf(false) }

    if (uiState.showLoudnessDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLoudnessDialog,
            confirmButton = {
                TextButton(onClick = viewModel::confirmLoudnessDialog) {
                    Text(text = stringResource(R.string.whistle_loudness_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLoudnessDialog) {
                    Text(text = stringResource(R.string.whistle_loudness_dialog_cancel))
                }
            },
            title = { Text(text = stringResource(R.string.whistle_loudness_dialog_title)) },
            text = { Text(text = stringResource(R.string.whistle_loudness_dialog_body)) }
        )
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoSection(
                    title = stringResource(R.string.whistle_info_heading_usage),
                    body = stringResource(R.string.whistle_info_body_usage)
                )
                InfoSection(
                    title = stringResource(R.string.whistle_info_heading_modes),
                    body = stringResource(R.string.whistle_info_body_modes)
                )
                InfoSection(
                    title = stringResource(R.string.whistle_info_heading_safety),
                    body = stringResource(R.string.whistle_info_body_safety)
                )
            }
        }
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_whistle_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.info)
                        )
                    }
                }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (uiState.isPlaying) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (uiState.isPlaying) {
                                stringResource(R.string.whistle_status_playing)
                            } else {
                                stringResource(R.string.whistle_status_idle)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.whistle_output_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusPill(
                                text = modeLabel(mode = uiState.mode)
                            )
                            StatusPill(
                                text = stringResource(
                                    R.string.whistle_frequency_label,
                                    uiState.frequencyHz.toInt()
                                )
                            )
                            StatusPill(
                                text = stringResource(
                                    R.string.whistle_intensity_label,
                                    (uiState.intensity * 100).toInt()
                                )
                            )
                            StatusPill(
                                text = if (
                                    uiState.alarmVolumeMax > 0 &&
                                    uiState.alarmVolume >= uiState.alarmVolumeMax
                                ) {
                                    stringResource(R.string.whistle_alarm_volume_maxed)
                                } else {
                                    stringResource(
                                        R.string.whistle_alarm_volume_label,
                                        uiState.alarmVolume,
                                        uiState.alarmVolumeMax
                                    )
                                }
                            )
                            if (uiState.boostGainDb > 0) {
                                StatusPill(
                                    text = stringResource(
                                        R.string.whistle_boost_label,
                                        uiState.boostGainDb
                                    )
                                )
                            }
                            uiState.routeSummary?.let { routeSummary ->
                                StatusPill(
                                    text = stringResource(
                                        R.string.whistle_route_label,
                                        routeSummary
                                    )
                                )
                            }
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.onToggleWhistleRequested()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            Text(
                                text = if (uiState.isPlaying) {
                                    stringResource(R.string.whistle_stop_button)
                                } else {
                                    stringResource(R.string.whistle_start_button)
                                },
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.stopWhistle() },
                            enabled = uiState.isPlaying,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = stringResource(R.string.whistle_silence_button))
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.whistle_mode_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModeChip(
                                selected = uiState.mode == WhistleMode.CONTINUOUS,
                                label = stringResource(R.string.whistle_mode_continuous),
                                onClick = { viewModel.setMode(WhistleMode.CONTINUOUS) }
                            )
                            ModeChip(
                                selected = uiState.mode == WhistleMode.PULSE,
                                label = stringResource(R.string.whistle_mode_pulse),
                                onClick = { viewModel.setMode(WhistleMode.PULSE) }
                            )
                            ModeChip(
                                selected = uiState.mode == WhistleMode.SOS,
                                label = stringResource(R.string.whistle_mode_sos),
                                onClick = { viewModel.setMode(WhistleMode.SOS) }
                            )
                            ModeChip(
                                selected = uiState.mode == WhistleMode.SWEEP,
                                label = stringResource(R.string.whistle_mode_sweep),
                                onClick = { viewModel.setMode(WhistleMode.SWEEP) }
                            )
                            ModeChip(
                                selected = uiState.mode == WhistleMode.RESCUE,
                                label = stringResource(R.string.whistle_mode_rescue),
                                onClick = { viewModel.setMode(WhistleMode.RESCUE) }
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.whistle_frequency_label,
                                uiState.frequencyHz.toInt()
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.whistle_frequency_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = uiState.frequencyHz,
                            onValueChange = viewModel::setFrequency,
                            valueRange = WhistleToneGenerator.MIN_FREQUENCY_HZ..WhistleToneGenerator.MAX_FREQUENCY_HZ,
                            steps = 4,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        RowWithSpacing(
                            start = stringResource(R.string.whistle_low_label),
                            end = stringResource(R.string.whistle_high_label)
                        )
                        Text(
                            text = stringResource(
                                R.string.whistle_intensity_label,
                                (uiState.intensity * 100).toInt()
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.whistle_intensity_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = uiState.intensity,
                            onValueChange = viewModel::setIntensity,
                            valueRange = WhistleToneGenerator.MIN_INTENSITY..WhistleToneGenerator.MAX_INTENSITY,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        RowWithSpacing(
                            start = stringResource(R.string.whistle_intensity_low),
                            end = stringResource(R.string.whistle_intensity_high)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModeChip(
                                selected = isIntensityNear(uiState.intensity, 0.72f),
                                label = stringResource(R.string.whistle_intensity_preset_standard),
                                onClick = viewModel::setIntensityPresetStandard
                            )
                            ModeChip(
                                selected = isIntensityNear(uiState.intensity, 0.88f),
                                label = stringResource(R.string.whistle_intensity_preset_high),
                                onClick = viewModel::setIntensityPresetHigh
                            )
                            ModeChip(
                                selected = isIntensityNear(uiState.intensity, 1.0f),
                                label = stringResource(R.string.whistle_intensity_preset_max),
                                onClick = viewModel::setIntensityPresetMax
                            )
                        }
                    }
                }

                uiState.errorMessage?.let { errorResId ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = stringResource(errorResId),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                if (uiState.routeNeedsAttention) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.whistle_route_warning),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.whistle_emergency_hint_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.whistle_emergency_hint_one),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.whistle_emergency_hint_two),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = stringResource(R.string.whistle_emergency_hint_three),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) }
    )
}

@Composable
private fun RowWithSpacing(start: String, end: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = start,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = end,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun modeLabel(mode: WhistleMode): String {
    return when (mode) {
        WhistleMode.CONTINUOUS -> stringResource(R.string.whistle_mode_continuous)
        WhistleMode.PULSE -> stringResource(R.string.whistle_mode_pulse)
        WhistleMode.SOS -> stringResource(R.string.whistle_mode_sos)
        WhistleMode.SWEEP -> stringResource(R.string.whistle_mode_sweep)
        WhistleMode.RESCUE -> stringResource(R.string.whistle_mode_rescue)
    }
}

private fun isIntensityNear(current: Float, target: Float): Boolean {
    return abs(current - target) < 0.03f
}
