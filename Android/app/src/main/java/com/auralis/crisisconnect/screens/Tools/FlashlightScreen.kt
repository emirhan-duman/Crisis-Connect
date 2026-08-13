package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashlightScreen(navController: NavController) {
    val viewModel: FlashlightViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    var showInfo by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.requestToggle() else viewModel.reportPermissionDenied()
    }

    fun requestToggleWithPermission() {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (uiState.isActive || uiState.mode == FlashlightMode.SCREEN_LIGHT) {
            viewModel.requestToggle()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.requestToggle()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }

    FlashlightWindowEffect(
        activity = activity,
        isActive = uiState.isActive,
        isScreenLight = uiState.mode == FlashlightMode.SCREEN_LIGHT,
        screenBrightness = uiState.screenBrightness
    )

    if (uiState.showStrobeWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissStrobeWarning,
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text(stringResource(R.string.flashlight_strobe_warning_title)) },
            text = { Text(stringResource(R.string.flashlight_strobe_warning_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmStrobeWarning) {
                    Text(stringResource(R.string.flashlight_strobe_warning_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissStrobeWarning) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                InfoBlock(
                    title = stringResource(R.string.flashlight_info_modes_title),
                    body = stringResource(R.string.flashlight_info_modes_body)
                )
                InfoBlock(
                    title = stringResource(R.string.flashlight_info_beacon_title),
                    body = stringResource(R.string.flashlight_info_beacon_body)
                )
                InfoBlock(
                    title = stringResource(R.string.flashlight_info_safety_title),
                    body = stringResource(R.string.flashlight_info_safety_body)
                )
            }
        }
    }

    if (uiState.isActive && uiState.mode == FlashlightMode.SCREEN_LIGHT) {
        ScreenLightActiveView(
            color = uiState.screenColor,
            onStop = viewModel::stop
        )
        return
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_flashlight_title,
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
        bottomBar = { AppBottomBar(navController = navController) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FlashlightStatusCard(
                uiState = uiState,
                onToggle = ::requestToggleWithPermission
            )

            uiState.errorMessageRes?.let { messageRes ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null)
                        Text(
                            text = stringResource(messageRes),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            SettingsCard(
                title = stringResource(R.string.flashlight_mode_heading),
                icon = Icons.Filled.FlashlightOn
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FlashlightMode.entries.forEach { mode ->
                        FilterChip(
                            selected = uiState.mode == mode,
                            onClick = { viewModel.selectMode(mode) },
                            label = { Text(stringResource(mode.titleRes)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = modeIcon(mode),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
                Text(
                    text = stringResource(uiState.mode.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (uiState.mode) {
                FlashlightMode.SCREEN_LIGHT -> ScreenLightControls(uiState, viewModel)
                FlashlightMode.STROBE -> StrobeControls(uiState, viewModel)
                FlashlightMode.LOW_POWER -> LowPowerControls()
                else -> Unit
            }

            if (uiState.mode != FlashlightMode.SCREEN_LIGHT &&
                uiState.mode != FlashlightMode.LOW_POWER
            ) {
                BrightnessControls(uiState, viewModel)
            }

            AutoOffControls(uiState, viewModel)

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = stringResource(R.string.flashlight_safety_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

@Composable
private fun FlashlightStatusCard(
    uiState: FlashlightUiState,
    onToggle: () -> Unit
) {
    val activeColor = MaterialTheme.colorScheme.primary
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(
                        color = if (uiState.isActive) activeColor else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.FlashlightOn,
                    contentDescription = null,
                    tint = if (uiState.isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = if (uiState.isActive) {
                    stringResource(R.string.flashlight_status_active)
                } else {
                    stringResource(R.string.flashlight_status_ready)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(uiState.mode.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = if (uiState.isActive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = if (uiState.isActive) {
                        stringResource(R.string.flashlight_stop)
                    } else {
                        stringResource(R.string.flashlight_start)
                    },
                    modifier = Modifier.padding(start = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun BrightnessControls(
    uiState: FlashlightUiState,
    viewModel: FlashlightViewModel
) {
    SettingsCard(
        title = stringResource(R.string.flashlight_brightness_heading),
        icon = Icons.Filled.Bolt
    ) {
        if (uiState.supportsStrengthControl) {
            Slider(
                value = uiState.intensity,
                onValueChange = viewModel::setIntensity,
                valueRange = 0.1f..1f
            )
            Text(
                text = stringResource(
                    R.string.flashlight_brightness_percent,
                    (uiState.intensity * 100).roundToInt()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = stringResource(R.string.flashlight_brightness_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LowPowerControls() {
    SettingsCard(
        title = stringResource(R.string.flashlight_mode_low_power),
        icon = Icons.Filled.BatterySaver
    ) {
        Text(
            text = stringResource(R.string.flashlight_mode_low_power_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StrobeControls(
    uiState: FlashlightUiState,
    viewModel: FlashlightViewModel
) {
    SettingsCard(
        title = stringResource(R.string.flashlight_strobe_rate_heading),
        icon = Icons.Filled.Warning
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { rate ->
                FilterChip(
                    selected = uiState.strobeRate == rate,
                    onClick = { viewModel.setStrobeRate(rate) },
                    label = { Text(stringResource(R.string.flashlight_strobe_rate, rate)) }
                )
            }
        }
        Text(
            text = stringResource(R.string.flashlight_strobe_rate_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScreenLightControls(
    uiState: FlashlightUiState,
    viewModel: FlashlightViewModel
) {
    SettingsCard(
        title = stringResource(R.string.flashlight_screen_settings_heading),
        icon = Icons.Filled.LightMode
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlashlightScreenColor.entries.forEach { color ->
                FilterChip(
                    selected = uiState.screenColor == color,
                    onClick = { viewModel.setScreenColor(color) },
                    label = { Text(stringResource(color.titleRes)) }
                )
            }
        }
        Slider(
            value = uiState.screenBrightness,
            onValueChange = viewModel::setScreenBrightness,
            valueRange = 0.2f..1f
        )
        Text(
            text = stringResource(
                R.string.flashlight_screen_brightness_percent,
                (uiState.screenBrightness * 100).roundToInt()
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AutoOffControls(
    uiState: FlashlightUiState,
    viewModel: FlashlightViewModel
) {
    SettingsCard(
        title = stringResource(R.string.flashlight_auto_off_heading),
        icon = Icons.Filled.Timer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FlashlightAutoOff.entries.forEach { option ->
                FilterChip(
                    selected = uiState.autoOff == option,
                    onClick = { viewModel.setAutoOff(option) },
                    label = { Text(stringResource(option.titleRes)) }
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            content()
        }
    }
}

@Composable
private fun InfoBlock(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScreenLightActiveView(
    color: FlashlightScreenColor,
    onStop: () -> Unit
) {
    val background = when (color) {
        FlashlightScreenColor.WHITE -> Color.White
        FlashlightScreenColor.WARM -> Color(0xFFFFE2B8)
        FlashlightScreenColor.RED -> Color(0xFFB71C1C)
    }
    val foreground = if (color == FlashlightScreenColor.RED) Color.White else Color(0xFF111111)
    BackHandler(onBack = onStop)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LightMode,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(52.dp)
            )
            Text(
                text = stringResource(R.string.flashlight_screen_active),
                color = foreground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = foreground,
                    contentColor = background
                )
            ) {
                Text(stringResource(R.string.flashlight_stop))
            }
        }
    }
}

@Composable
private fun FlashlightWindowEffect(
    activity: Activity?,
    isActive: Boolean,
    isScreenLight: Boolean,
    screenBrightness: Float
) {
    DisposableEffect(activity, isActive, isScreenLight, screenBrightness) {
        val window = activity?.window
        val originalBrightness = window?.attributes?.screenBrightness
        if (isActive) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isScreenLight && window != null) {
                val attributes = window.attributes
                attributes.screenBrightness = screenBrightness.coerceIn(0.2f, 1f)
                window.attributes = attributes
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null && originalBrightness != null) {
                val attributes = window.attributes
                attributes.screenBrightness = originalBrightness
                window.attributes = attributes
            }
        }
    }
}

private fun modeIcon(mode: FlashlightMode): ImageVector = when (mode) {
    FlashlightMode.NORMAL -> Icons.Filled.FlashlightOn
    FlashlightMode.SOS -> Icons.Filled.Emergency
    FlashlightMode.STROBE -> Icons.Filled.Warning
    FlashlightMode.LOW_POWER -> Icons.Filled.BatterySaver
    FlashlightMode.SCREEN_LIGHT -> Icons.Filled.LightMode
    FlashlightMode.EMERGENCY_BEACON -> Icons.Filled.Bolt
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
