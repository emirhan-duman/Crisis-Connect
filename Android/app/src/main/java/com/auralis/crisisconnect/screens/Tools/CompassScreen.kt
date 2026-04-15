package com.auralis.crisisconnect.screens.Tools

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.components.AppBackTopBar
import com.auralis.crisisconnect.ui.components.AppBottomBar
import androidx.compose.ui.graphics.toArgb
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompassScreen(navController: NavController) {
    val viewModel: CompassViewModel = viewModel()
    val azimuth by viewModel.azimuth.collectAsStateWithLifecycle()
    val accuracy by viewModel.accuracy.collectAsStateWithLifecycle()
    val hasSensors by viewModel.hasRequiredSensors.collectAsStateWithLifecycle()
    val targetBearing by viewModel.targetBearing.collectAsStateWithLifecycle()
    val isFlat = remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val accelerometerSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    DisposableEffect(accelerometerSensor) {
        if (sensorManager == null || accelerometerSensor == null) {
            isFlat.value = true
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val accelZ = event.values.getOrNull(2) ?: return
                    isFlat.value = abs(accelZ) > 7f
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(
                listener,
                accelerometerSensor,
                SensorManager.SENSOR_DELAY_UI
            )
            onDispose {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        (context as? Activity)?.display?.rotation
    } else {
        @Suppress("DEPRECATION")
        (context as? Activity)?.windowManager?.defaultDisplay?.rotation
    } ?: Surface.ROTATION_0
    LaunchedEffect(rotation) {
        viewModel.updateDisplayRotation(rotation)
    }

    val colorScheme = MaterialTheme.colorScheme
    val directionLabel = stringResource(headingToLabelRes(azimuth))
    val normalizedHeading = normalizeAngle(azimuth)
    val headingInt = normalizedHeading.roundToInt()
    val formattedDegrees = stringResource(R.string.compass_degrees_compact, headingInt)
    val directionChipText = stringResource(
        R.string.compass_direction_chip_format,
        directionLabel,
        formattedDegrees
    )
    val needsCalibration = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    val configuration = LocalConfiguration.current
    val dialSize = (min(configuration.screenWidthDp, configuration.screenHeightDp).dp * 0.72f)
        .coerceIn(220.dp, 340.dp)
    val accuracyUi = remember(accuracy, colorScheme) {
        accuracyUiState(accuracy, colorScheme)
    }

    val haptic = LocalHapticFeedback.current
    var lastHapticTimestamp by remember { mutableStateOf(0L) }
    LaunchedEffect(azimuth) {
        val currentHeading = normalizeAngle(azimuth)
        val now = SystemClock.uptimeMillis()
        val isNearCardinal = CARDINALS.any { angleDelta(currentHeading, it) <= 2f }
        if (isNearCardinal && now - lastHapticTimestamp >= 800L) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            lastHapticTimestamp = now
        }
    }

    Scaffold(
        topBar = {
            AppBackTopBar(
                titleRes = R.string.tool_compass_title,
                onNavigateBack = { navController.popBackStack() }
            )
        },
        bottomBar = {
            AppBottomBar(navController = navController)
        }
    ) { innerPadding ->
        if (!hasSensors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.compass_no_sensor_warning),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { navController.popBackStack() }) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                AnimatedVisibility(visible = !isFlat.value) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .background(
                                Color.Red.copy(alpha = 0.75f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.compass_hold_phone_horizontal),
                            color = Color.White
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        tonalElevation = 6.dp
                    ) {
                        Text(
                            text = directionChipText,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.sp,
                            color = colorScheme.onSurface
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = accuracyUi.containerColor,
                        contentColor = accuracyUi.contentColor,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = stringResource(accuracyUi.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.compass_heading_format, headingInt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                CompassDial(
                    heading = azimuth,
                    dialSize = dialSize,
                    targetBearing = targetBearing
                )
                AnimatedVisibility(
                    visible = needsCalibration,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    CalibrationBanner(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun CompassDial(heading: Float, dialSize: Dp, targetBearing: Float?) {
    val headingAnim = remember { Animatable(heading) }
    LaunchedEffect(heading) {
        val current = headingAnim.value
        val target = heading
        val currentNormalized = normalizeAngle(current)
        val delta = shortestDelta(currentNormalized, target)
        val newTarget = current + delta
        headingAnim.animateTo(
            targetValue = newTarget,
            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
        )
    }
    val animatedHeading = normalizeAngle(headingAnim.value)
    val headingInt = animatedHeading.roundToInt()
    val targetAngle = targetBearing?.let { normalizeAngle(it) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = MaterialTheme.colorScheme.tertiary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val rimLight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val rimDark = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    val northColor = MaterialTheme.colorScheme.error

    val northLabel = stringResource(R.string.compass_north_label)
    val eastLabel = stringResource(R.string.compass_east_label)
    val southLabel = stringResource(R.string.compass_south_label)
    val westLabel = stringResource(R.string.compass_west_label)
    val cardinalLabels = remember(northLabel, eastLabel, southLabel, westLabel) {
        listOf(
            0f to northLabel,
            90f to eastLabel,
            180f to southLabel,
            270f to westLabel
        )
    }
    val labelPaint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Box(
        modifier = Modifier.size(dialSize),
        contentAlignment = Alignment.Center
    ) {
        val canvasContentDescription = stringResource(
            R.string.compass_heading_content_description,
            headingInt
        )
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .semantics {
                    contentDescription = canvasContentDescription
                }
        ) {
            val radius = size.minDimension / 2f
            val ringStroke = size.minDimension * 0.04f
            val centerOffset = center

            drawCircle(
                color = surfaceColor,
                radius = radius,
                center = centerOffset
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(rimDark, rimLight, rimDark.copy(alpha = 0.18f)),
                    center = centerOffset,
                    radius = radius
                ),
                style = Stroke(width = ringStroke * 1.1f)
            )

            val dialRadius = radius - ringStroke * 1.2f
            val tickOuterRadius = dialRadius
            val longTick = size.minDimension * 0.08f
            val mediumTick = size.minDimension * 0.06f
            val shortTick = size.minDimension * 0.04f

            rotate(-animatedHeading, pivot = centerOffset) {
                // Subtle north wedge
                val wedgeHeight = longTick * 1.5f
                val wedgeHalfWidth = longTick * 0.45f
                val wedgeTop = Offset(centerOffset.x, centerOffset.y - tickOuterRadius + ringStroke * 0.8f)
                val wedgePath = Path().apply {
                    moveTo(wedgeTop.x, wedgeTop.y)
                    lineTo(
                        wedgeTop.x - wedgeHalfWidth,
                        wedgeTop.y + wedgeHeight
                    )
                    lineTo(
                        wedgeTop.x + wedgeHalfWidth,
                        wedgeTop.y + wedgeHeight
                    )
                    close()
                }
                drawPath(
                    path = wedgePath,
                    color = primaryColor.copy(alpha = 0.18f)
                )

                for (i in 0 until 360 step 10) {
                    val angleRad = Math.toRadians(i.toDouble())
                    val sinValue = sin(angleRad).toFloat()
                    val cosValue = cos(angleRad).toFloat()
                    val tickLength = when {
                        i % 90 == 0 -> longTick
                        i % 30 == 0 -> mediumTick
                        else -> shortTick
                    }
                    val strokeWidth = when {
                        i % 90 == 0 -> ringStroke * 0.6f
                        i % 30 == 0 -> ringStroke * 0.4f
                        else -> ringStroke * 0.25f
                    }
                    val tickColor = when {
                        i % 90 == 0 -> onSurface
                        i % 30 == 0 -> onSurfaceVariant
                        else -> onSurfaceVariant.copy(alpha = 0.7f)
                    }
                    val start = Offset(
                        x = centerOffset.x + sinValue * (tickOuterRadius - tickLength),
                        y = centerOffset.y - cosValue * (tickOuterRadius - tickLength)
                    )
                    val end = Offset(
                        x = centerOffset.x + sinValue * tickOuterRadius,
                        y = centerOffset.y - cosValue * tickOuterRadius
                    )
                    drawLine(
                        color = tickColor,
                        start = start,
                        end = end,
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                val labelRadius = tickOuterRadius - longTick - ringStroke * 2.1f
                drawIntoCanvas { canvas ->
                    val textSize = size.minDimension * 0.14f
                    labelPaint.textSize = textSize
                    val baselineShift = (labelPaint.descent() + labelPaint.ascent()) / 2f
                    cardinalLabels.forEach { (angle, label) ->
                        val angleRad = Math.toRadians(angle.toDouble())
                        val sinValue = sin(angleRad).toFloat()
                        val cosValue = cos(angleRad).toFloat()
                        val position = Offset(
                            x = centerOffset.x + sinValue * labelRadius,
                            y = centerOffset.y - cosValue * labelRadius
                        )
                        labelPaint.color = if (angle == 0f) northColor.toArgb() else onSurface.toArgb()
                        canvas.nativeCanvas.drawText(
                            label,
                            position.x,
                            position.y - baselineShift,
                            labelPaint
                        )
                    }
                }

                targetAngle?.let { angle ->
                    val angleRad = Math.toRadians(angle.toDouble())
                    val sinValue = sin(angleRad).toFloat()
                    val cosValue = cos(angleRad).toFloat()
                    val outerRadius = tickOuterRadius - ringStroke * 0.4f
                    val innerRadius = outerRadius - longTick * 1.4f
                    val halfWidth = ringStroke * 0.7f
                    val tip = Offset(
                        x = centerOffset.x + sinValue * outerRadius,
                        y = centerOffset.y - cosValue * outerRadius
                    )
                    val baseCenter = Offset(
                        x = centerOffset.x + sinValue * innerRadius,
                        y = centerOffset.y - cosValue * innerRadius
                    )
                    val perpendicularX = cosValue * halfWidth
                    val perpendicularY = sinValue * halfWidth
                    val left = Offset(
                        x = baseCenter.x + perpendicularX,
                        y = baseCenter.y + perpendicularY
                    )
                    val right = Offset(
                        x = baseCenter.x - perpendicularX,
                        y = baseCenter.y - perpendicularY
                    )
                    val path = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(left.x, left.y)
                        lineTo(right.x, right.y)
                        close()
                    }
                    drawPath(path = path, color = accentColor.copy(alpha = 0.85f))
                }
            }

            val needleLength = radius * 0.78f
            val tailLength = radius * 0.3f
            val needleStroke = ringStroke * 0.85f
            val needleGlowStroke = needleStroke * 1.6f
            val needleTip = Offset(centerOffset.x, centerOffset.y - needleLength)
            val tailEnd = Offset(centerOffset.x, centerOffset.y + tailLength)

            drawLine(
                color = primaryColor.copy(alpha = 0.3f),
                start = centerOffset,
                end = tailEnd,
                strokeWidth = needleStroke * 0.8f,
                cap = StrokeCap.Round
            )

            drawLine(
                color = primaryColor.copy(alpha = 0.25f),
                start = centerOffset,
                end = needleTip,
                strokeWidth = needleGlowStroke,
                cap = StrokeCap.Round
            )

            drawLine(
                color = primaryColor,
                start = centerOffset,
                end = needleTip,
                strokeWidth = needleStroke,
                cap = StrokeCap.Round
            )

            drawCircle(
                color = primaryColor,
                radius = needleStroke * 0.9f,
                center = centerOffset
            )
        }

    }
}

@Composable
private fun CalibrationBanner(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val infiniteTransition = rememberInfiniteTransition(label = "calibration")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "calibrationAlpha"
    )

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
        color = colorScheme.errorContainer,
        modifier = modifier.graphicsLayer { alpha = pulseAlpha }
    ) {
        Text(
            text = stringResource(R.string.compass_calibration_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center
        )
    }
}

private fun normalizeAngle(value: Float): Float {
    var angle = value % 360f
    if (angle < 0f) angle += 360f
    return angle
}

private fun shortestDelta(from: Float, to: Float): Float {
    var delta = to - from
    delta = (delta + 540f) % 360f - 180f
    return delta
}

private fun angleDelta(a: Float, b: Float): Float {
    var delta = a - b
    delta = (delta + 540f) % 360f - 180f
    return abs(delta)
}

private data class AccuracyUiState(
    @androidx.annotation.StringRes val labelRes: Int,
    val containerColor: Color,
    val contentColor: Color
)

private fun accuracyUiState(accuracy: Int, colors: ColorScheme): AccuracyUiState {
    return when (accuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> AccuracyUiState(
            labelRes = R.string.compass_accuracy_high,
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer
        )
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> AccuracyUiState(
            labelRes = R.string.compass_accuracy_medium,
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer
        )
        else -> AccuracyUiState(
            labelRes = R.string.compass_accuracy_low,
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer
        )
    }
}

private val CARDINALS = listOf(0f, 90f, 180f, 270f)

@androidx.annotation.StringRes
private fun headingToLabelRes(azimuth: Float): Int {
    val directions = listOf(
        R.string.compass_direction_n,
        R.string.compass_direction_ne,
        R.string.compass_direction_e,
        R.string.compass_direction_se,
        R.string.compass_direction_s,
        R.string.compass_direction_sw,
        R.string.compass_direction_w,
        R.string.compass_direction_nw
    )
    val sector = ((azimuth + 22.5f) / 45f).toInt() % directions.size
    return directions[sector]
}
