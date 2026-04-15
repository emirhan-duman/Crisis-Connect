package com.auralis.crisisconnect.screens.Tools

import android.Manifest
import android.hardware.SensorManager
import android.os.Build
import android.widget.Toast
import com.auralis.crisisconnect.R
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import com.auralis.crisisconnect.screens.Tools.data.MetalDetectorSettings
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetalDetectorScreen(navController: NavController) {
    val viewModel: MetalDetectorViewModel = viewModel()
    val hasMagnetometer by viewModel.hasMagnetometer.collectAsStateWithLifecycle()
    val fieldStrength by viewModel.fieldStrength.collectAsStateWithLifecycle()
    val isNear by viewModel.isNearFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val showCalibrationHint by viewModel.showCalibrationHint.collectAsStateWithLifecycle()

    var showInfo by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val context = LocalContext.current

    var hasHighSamplingPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cardColor by animateColorAsState(
        targetValue = if (isNear) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        label = "proximityCardColor"
    )

    val highSamplingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.setSensorDelay(SensorManager.SENSOR_DELAY_FASTEST)
            hasHighSamplingPermission = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasHighSamplingPermission = false
            Toast.makeText(
                context,
                context.getString(R.string.high_sampling_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
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
                    title = stringResource(R.string.metal_info_heading_materials),
                    body = stringResource(R.string.metal_info_body_materials)
                )
                InfoSection(
                    title = stringResource(R.string.metal_info_heading_environment),
                    body = stringResource(R.string.metal_info_body_environment)
                )
                InfoSection(
                    title = stringResource(R.string.metal_info_heading_sensitivity),
                    body = stringResource(R.string.metal_info_body_sensitivity)
                )
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsContent(
                viewModel = viewModel,
                settings = settings,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .padding(bottom = 12.dp),
                scrollable = true
            )
        }
    }

    if (showCalibrationHint) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCalibrationHint() },
            title = { Text(stringResource(R.string.calibration_required_title)) },
            text = { Text(stringResource(R.string.calibration_required_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissCalibrationHint() }) {
                    Text(stringResource(R.string.ok_understood))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissCalibrationHint()
                    showInfo = true
                }) {
                    Text(stringResource(R.string.how))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_metal_detector_title,
                onNavigateBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.info))
                    }
                }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        if (!hasMagnetometer) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.no_magnetometer_warning),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { navController.popBackStack() }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(
                                R.string.metal_detector_field_strength_microtesla,
                                fieldStrength.roundToInt()
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (isNear) stringResource(R.string.status_near)
                                    else stringResource(R.string.status_normal)
                                )
                            }
                        )
                    }
                }
                val history = viewModel.fieldStrengthHistory
                if (history.isNotEmpty()) {
                    FieldStrengthHistoryGraph(
                        history = history,
                        thresholdOn = settings.thresholdOn,
                        thresholdOff = settings.thresholdOff,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )
                }
                Button(onClick = { viewModel.recalibrate() }) {
                    Text(stringResource(R.string.recalibrate))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasHighSamplingPermission) {
                    TextButton(onClick = {
                        highSamplingLauncher.launch(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
                    }) {
                        Text(stringResource(R.string.request_high_sampling_permission))
                    }
                }
                TextButton(onClick = { showSettings = true }) {
                    Text(stringResource(R.string.settings))
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun FieldStrengthHistoryGraph(
    history: List<Float>,
    thresholdOn: Float,
    thresholdOff: Float,
    modifier: Modifier = Modifier
) {
    val historySnapshot = history.takeLast(100)
    if (historySnapshot.isEmpty()) {
        return
    }

    val minValue = listOf(
        historySnapshot.minOrNull() ?: 0f,
        thresholdOn,
        thresholdOff
    ).minOrNull() ?: 0f
    val maxValue = listOf(
        historySnapshot.maxOrNull() ?: 0f,
        thresholdOn,
        thresholdOff
    ).maxOrNull() ?: 0f

    val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f

    val normalizedPoints = historySnapshot.map { (it - minValue) / range }
    val thresholdOnPosition = ((thresholdOn - minValue) / range).coerceIn(0f, 1f)
    val thresholdOffPosition = ((thresholdOff - minValue) / range).coerceIn(0f, 1f)

    val chartColor = MaterialTheme.colorScheme.primary
    val thresholdOnColor = MaterialTheme.colorScheme.error
    val thresholdOffColor = MaterialTheme.colorScheme.tertiary

    val historyDescription = stringResource(R.string.metal_detector_history_description)

    Surface(
        modifier = modifier.semantics {
            contentDescription = historyDescription
        },
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val pointCount = normalizedPoints.size
            if (pointCount == 0) {
                return@Canvas
            }

            val width = size.width
            val height = size.height
            val stepX = if (pointCount > 1) width / (pointCount - 1) else 0f
            val lineStrokeWidth = 3.dp.toPx()
            val thresholdStrokeWidth = 2.dp.toPx()
            val dashInterval = floatArrayOf(8.dp.toPx(), 8.dp.toPx())

            val path = Path()
            normalizedPoints.forEachIndexed { index, value ->
                val x = if (pointCount > 1) stepX * index else width / 2f
                val y = height * (1f - value)
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = chartColor,
                style = Stroke(width = lineStrokeWidth, cap = StrokeCap.Round)
            )

            val thresholdOnY = height * (1f - thresholdOnPosition)
            val thresholdOffY = height * (1f - thresholdOffPosition)
            val dashEffect = PathEffect.dashPathEffect(dashInterval)

            drawLine(
                color = thresholdOnColor,
                start = Offset(0f, thresholdOnY),
                end = Offset(width, thresholdOnY),
                strokeWidth = thresholdStrokeWidth,
                cap = StrokeCap.Round,
                pathEffect = dashEffect
            )

            drawLine(
                color = thresholdOffColor,
                start = Offset(0f, thresholdOffY),
                end = Offset(width, thresholdOffY),
                strokeWidth = thresholdStrokeWidth,
                cap = StrokeCap.Round,
                pathEffect = dashEffect
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    viewModel: MetalDetectorViewModel,
    settings: MetalDetectorSettings,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false
) {
    val mode = SensitivityMode.from(settings.sensitivityMode)
    var sensExpanded by remember { mutableStateOf(false) }
    var delayExpanded by remember { mutableStateOf(false) }
    val contentModifier = if (scrollable) {
        modifier.verticalScroll(rememberScrollState())
    } else {
        modifier
    }

    Column(
        modifier = contentModifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ExposedDropdownMenuBox(expanded = sensExpanded, onExpandedChange = { sensExpanded = it }) {
            TextField(
                value = when (mode) {
                    SensitivityMode.LOW -> stringResource(R.string.sensitivity_low)
                    SensitivityMode.MEDIUM -> stringResource(R.string.sensitivity_medium)
                    SensitivityMode.HIGH -> stringResource(R.string.sensitivity_high)
                    SensitivityMode.CUSTOM -> stringResource(R.string.sensitivity_custom)
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sensitivity)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sensExpanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(expanded = sensExpanded, onDismissRequest = { sensExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.sensitivity_low)) }, onClick = {
                    sensExpanded = false
                    viewModel.applySensitivity(SensitivityMode.LOW)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.sensitivity_medium)) }, onClick = {
                    sensExpanded = false
                    viewModel.applySensitivity(SensitivityMode.MEDIUM)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.sensitivity_high)) }, onClick = {
                    sensExpanded = false
                    viewModel.applySensitivity(SensitivityMode.HIGH)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.sensitivity_custom)) }, onClick = {
                    sensExpanded = false
                    viewModel.applySensitivity(SensitivityMode.CUSTOM)
                })
            }
        }

        if (mode == SensitivityMode.CUSTOM) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.threshold_on))
            Slider(
                value = settings.thresholdOn,
                onValueChange = { viewModel.setThresholds(it, settings.thresholdOff) },
                valueRange = 0f..50f
            )
            Text(stringResource(R.string.threshold_off))
            Slider(
                value = settings.thresholdOff,
                onValueChange = { viewModel.setThresholds(settings.thresholdOn, it) },
                valueRange = 0f..50f
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.freeze_baseline))
            Spacer(Modifier.weight(1f))
            Switch(
                checked = settings.freezeBaselineOnDetection,
                onCheckedChange = { viewModel.setFreezeBaseline(it) }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.baseline_adaptation))
            Spacer(Modifier.weight(1f))
            Switch(
                checked = settings.baselineAdaptationEnabled,
                onCheckedChange = { viewModel.setBaselineAdaptation(it) }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sound_alert))
            Spacer(Modifier.weight(1f))
            Switch(
                checked = settings.soundEnabled,
                onCheckedChange = { viewModel.setSoundEnabled(it) }
            )
        }

        ExposedDropdownMenuBox(expanded = delayExpanded, onExpandedChange = { delayExpanded = it }) {
            TextField(
                value = when (settings.sensorDelay) {
                    SensorManager.SENSOR_DELAY_UI -> stringResource(R.string.delay_ui)
                    SensorManager.SENSOR_DELAY_GAME -> stringResource(R.string.delay_game)
                    SensorManager.SENSOR_DELAY_NORMAL -> stringResource(R.string.delay_normal)
                    SensorManager.SENSOR_DELAY_FASTEST -> stringResource(R.string.delay_fastest)
                    else -> stringResource(R.string.delay_normal)
                },
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.sampling_rate)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = delayExpanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(expanded = delayExpanded, onDismissRequest = { delayExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.delay_ui)) }, onClick = {
                    delayExpanded = false
                    viewModel.setSensorDelay(SensorManager.SENSOR_DELAY_UI)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.delay_normal)) }, onClick = {
                    delayExpanded = false
                    viewModel.setSensorDelay(SensorManager.SENSOR_DELAY_NORMAL)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.delay_game)) }, onClick = {
                    delayExpanded = false
                    viewModel.setSensorDelay(SensorManager.SENSOR_DELAY_GAME)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.delay_fastest)) }, onClick = {
                    delayExpanded = false
                    viewModel.setSensorDelay(SensorManager.SENSOR_DELAY_FASTEST)
                })
            }
        }
    }
}
