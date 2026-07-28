package com.auralis.crisisconnect.screens

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.service.GattSOSServerService
import kotlinx.coroutines.delay

private const val COUNTDOWN_SECONDS = 5

private data class CountdownPalette(
    val backgroundTop: Color,
    val backgroundMid: Color,
    val backgroundBottom: Color,
    val accent: Color,
    val accentSoft: Color,
    val title: Color,
    val body: Color,
    val digit: Color,
    val dialFill: Color,
    val dialBorder: Color,
    val track: Color,
    val trackBorder: Color,
    val trackLabel: Color,
    val knobSurface: Color,
    val knobIcon: Color
)

@Composable
private fun rememberCountdownPalette(): CountdownPalette {
    val isDark = isSystemInDarkTheme()
    return remember(isDark) {
        if (isDark) {
            CountdownPalette(
                backgroundTop = Color(0xFF200A0E),
                backgroundMid = Color(0xFF34101A),
                backgroundBottom = Color(0xFF12060A),
                accent = Color(0xFFFF4B55),
                accentSoft = Color(0xFFFF7A6B),
                title = Color(0xFFFDF4F4),
                body = Color(0xFFE3C8CB),
                digit = Color(0xFFFDF4F4),
                dialFill = Color(0xFFFF4B55).copy(alpha = 0.26f),
                dialBorder = Color(0xFFFF4B55).copy(alpha = 0.5f),
                track = Color(0x33FFFFFF),
                trackBorder = Color(0x4DFFFFFF),
                trackLabel = Color(0xFFFDF4F4),
                knobSurface = Color(0xFFFFFFFF),
                knobIcon = Color(0xFFB3261E)
            )
        } else {
            CountdownPalette(
                backgroundTop = Color(0xFFFFF6F4),
                backgroundMid = Color(0xFFFDEDE9),
                backgroundBottom = Color(0xFFF8E4E0),
                accent = Color(0xFFD92D20),
                accentSoft = Color(0xFFF97066),
                title = Color(0xFF1F1315),
                body = Color(0xFF6B4E52),
                digit = Color(0xFFB42318),
                dialFill = Color(0xFFD92D20).copy(alpha = 0.10f),
                dialBorder = Color(0xFFD92D20).copy(alpha = 0.38f),
                track = Color(0x12220A0D),
                trackBorder = Color(0x26B3261E),
                trackLabel = Color(0xFF7A2E28),
                knobSurface = Color(0xFFFFFFFF),
                knobIcon = Color(0xFFB3261E)
            )
        }
    }
}

/**
 * Apple-style arming screen for the SOS broadcast: a 5-second haptic countdown that the user can
 * abort with a deliberate slide gesture. When the countdown reaches zero the foreground SOS
 * service starts and the flow lands on the live SOS status screen.
 */
@Composable
fun SosCountdownScreen(navController: NavController) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val palette = rememberCountdownPalette()
    val isRunning by GattSOSServerService.isDeclared.collectAsStateWithLifecycle()
    var secondsLeft by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }
    var fired by remember { mutableStateOf(false) }

    val activity = context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val goToStatus: () -> Unit = {
        navController.navigate("sos_status") {
            popUpTo("sos_countdown") { inclusive = true }
            launchSingleTop = true
        }
    }

    // SOS may already be live (relaunch, service restart) — skip straight to the status screen.
    LaunchedEffect(isRunning) {
        if (isRunning && !fired) {
            fired = true
            goToStatus()
        }
    }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(1_000L)
            secondsLeft -= 1
        }
        if (!fired) {
            fired = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            ContextCompat.startForegroundService(
                context,
                Intent(context, GattSOSServerService::class.java).putExtra(
                    // The ONLY place a real emergency is declared. Without this extra the service
                    // just hosts the shared GATT server and tells nobody anything.
                    GattSOSServerService.EXTRA_USER_DECLARED,
                    true
                )
            )
            goToStatus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.backgroundTop,
                        palette.backgroundMid,
                        palette.backgroundBottom
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .align(Alignment.Center)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.sos_countdown_title),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp
                    ),
                    color = palette.title,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.sos_countdown_body),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = palette.body,
                    textAlign = TextAlign.Center
                )
            }

            CountdownDial(secondsLeft = secondsLeft, palette = palette)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SosSlideAction(
                    label = stringResource(R.string.sos_countdown_slide_label),
                    confirmActionLabel = stringResource(R.string.sos_countdown_cancel_action),
                    style = SosSlideStyle(
                        track = palette.track,
                        trackBorder = palette.trackBorder,
                        progressStart = palette.accent,
                        progressEnd = palette.accentSoft,
                        label = palette.trackLabel,
                        knob = palette.knobSurface,
                        knobIcon = palette.knobIcon
                    ),
                    onConfirmed = { navController.popBackStack() }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sos_countdown_slide_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.body.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun CountdownDial(secondsLeft: Int, palette: CountdownPalette) {
    val pulse = rememberInfiniteTransition(label = "sos-countdown-pulse")
    val ringScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sos-countdown-ring-scale"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sos-countdown-ring-alpha"
    )
    val secondsDescription = stringResource(R.string.sos_countdown_seconds_left, secondsLeft)

    Box(
        modifier = Modifier
            .size(280.dp)
            .semantics { contentDescription = secondsDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(230.dp)
                .scale(ringScale)
                .graphicsLayer { alpha = ringAlpha }
                .border(width = 2.dp, color = palette.accentSoft, shape = CircleShape)
        )
        Box(
            modifier = Modifier
                .size(230.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            palette.dialFill,
                            palette.dialFill.copy(alpha = palette.dialFill.alpha * 0.2f),
                            Color.Transparent
                        )
                    )
                )
                .border(width = 1.5.dp, color = palette.dialBorder, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = secondsLeft,
                transitionSpec = {
                    (scaleIn(initialScale = 1.45f) + fadeIn(tween(180))) togetherWith
                        fadeOut(tween(120))
                },
                label = "sos-countdown-digit"
            ) { value ->
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 128.sp,
                        letterSpacing = (-4).sp
                    ),
                    color = palette.digit
                )
            }
        }
    }
}
