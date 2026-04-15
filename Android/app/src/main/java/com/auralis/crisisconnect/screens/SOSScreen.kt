package com.auralis.crisisconnect.screens

import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.service.GattSOSServerService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class SosPalette(
    val backgroundStart: Color,
    val backgroundMid: Color,
    val backgroundEnd: Color,
    val backgroundGlowPrimary: Color,
    val backgroundGlowSecondary: Color,
    val topBarScrim: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val heroBorder: Color,
    val heroGlow: Color,
    val title: Color,
    val body: Color,
    val muted: Color,
    val liveSurface: Color,
    val liveBorder: Color,
    val liveText: Color,
    val liveDot: Color,
    val timerSurface: Color,
    val timerOutline: Color,
    val timerText: Color,
    val timerLabel: Color,
    val statusSurface: Color,
    val statusBorder: Color,
    val statusPrimaryIconSurface: Color,
    val statusPrimaryIconTint: Color,
    val statusSecondaryIconSurface: Color,
    val statusSecondaryIconTint: Color,
    val stopCardTop: Color,
    val stopCardBottom: Color,
    val stopCardBorder: Color,
    val trackBase: Color,
    val trackBaseOverlay: Color,
    val trackLabel: Color,
    val progressStart: Color,
    val progressEnd: Color,
    val knobSurface: Color,
    val knobBorder: Color,
    val knobText: Color
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SOSScreen(navController: NavController) {
    val isRunning by GattSOSServerService.isRunning.collectAsState()
    val startTimestamp by GattSOSServerService.startTimestampMillis.collectAsState()
    val context = LocalContext.current
    val palette = rememberSosPalette()
    var hasSeenRunning by rememberSaveable { mutableStateOf(false) }
    val elapsedMillis by rememberElapsedMillis(startTimestamp = startTimestamp, isRunning = isRunning)
    val peers by BlePeerStore.peers.collectAsState()

    LaunchedEffect(isRunning) {
        if (isRunning) hasSeenRunning = true
        if (!isRunning && hasSeenRunning) navController.popBackStack()
        if (!isRunning) {
            SosSessionRouter.reset()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.sos_screen_title),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.1.sp
                        ),
                        color = palette.title
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.sos_screen_back),
                            tint = palette.title
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = palette.topBarScrim,
                    scrolledContainerColor = palette.topBarScrim
                )
            )
        }
    ) { padding ->
        LaunchedEffect(peers, isRunning) {
            if (!isRunning) return@LaunchedEffect
            val sessions = peers.values.mapNotNull { it.sessionCode.takeIf(String::isNotBlank) }
            val newSession = SosSessionRouter.firstUnseen(sessions)
            if (newSession != null) {
                SosSessionRouter.markHandled(newSession)
                val encoded = Uri.encode(newSession)
                val route = if (newSession.startsWith("ble:", ignoreCase = true)) {
                    "ble_chat/$encoded"
                } else {
                    "chat/$encoded"
                }
                navController.navigate(route) {
                    launchSingleTop = true
                    popUpTo("sos_status") { inclusive = true }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.backgroundStart,
                            palette.backgroundMid,
                            palette.backgroundEnd
                        )
                    )
                )
                .padding(padding)
        ) {
            SosAmbientBackdrop(palette)

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val heightScale = (maxHeight / 760.dp).coerceIn(0.5f, 1.05f)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = scaledDp(560.dp, heightScale, min = 320.dp))
                        .align(Alignment.TopCenter)
                        .padding(
                            horizontal = scaledDp(18.dp, heightScale),
                            vertical = scaledDp(16.dp, heightScale)
                        ),
                    verticalArrangement = Arrangement.spacedBy(scaledDp(14.dp, heightScale))
                ) {
                    StatusHeader(
                        elapsedMillis = elapsedMillis,
                        scale = heightScale,
                        palette = palette
                    )

                    StopCard(
                        onStopConfirmed = {
                            GattSOSServerService.stopBroadcast(context)
                        },
                        scale = heightScale,
                        palette = palette
                    )
                }
            }
        }
    }
}

private object SosSessionRouter {
    private val handled = ConcurrentHashMap.newKeySet<String>()

    fun firstUnseen(sessions: List<String>): String? {
        return sessions.firstOrNull { it !in handled }
    }

    fun markHandled(sessionCode: String) {
        handled.add(sessionCode)
    }

    fun reset() {
        handled.clear()
    }
}

@Composable
private fun rememberElapsedMillis(
    startTimestamp: Long?,
    isRunning: Boolean
): androidx.compose.runtime.State<Long> {
    val elapsedMillis = remember { mutableLongStateOf(0L) }

    LaunchedEffect(startTimestamp, isRunning) {
        if (startTimestamp == null) {
            elapsedMillis.longValue = 0L
            return@LaunchedEffect
        }

        val start = startTimestamp
        if (!isRunning) {
            elapsedMillis.longValue = System.currentTimeMillis() - start
            return@LaunchedEffect
        }

        while (isActive) {
            elapsedMillis.longValue = System.currentTimeMillis() - start
            delay(1_000)
        }
    }

    return elapsedMillis
}

@Composable
private fun rememberSosPalette(): SosPalette {
    val isDark = isSystemInDarkTheme()
    return remember(isDark) {
        if (isDark) {
            SosPalette(
                backgroundStart = Color(0xFF0C1015),
                backgroundMid = Color(0xFF131922),
                backgroundEnd = Color(0xFF090D12),
                backgroundGlowPrimary = Color(0x40FF5A64),
                backgroundGlowSecondary = Color(0x262E8BFF),
                topBarScrim = Color(0xCC0F141C),
                heroTop = Color(0xFF1A212C),
                heroBottom = Color(0xFF111720),
                heroBorder = Color(0x26FF7A7F),
                heroGlow = Color(0x1CFF6A70),
                title = Color(0xFFF7F9FD),
                body = Color(0xFFD8DEE8),
                muted = Color(0xFF9EABBD),
                liveSurface = Color(0xFF38181C),
                liveBorder = Color(0x4CFF6E74),
                liveText = Color(0xFFFFD6D8),
                liveDot = Color(0xFFFF6B70),
                timerSurface = Color(0xFF0E141B),
                timerOutline = Color(0xFF2B3442),
                timerText = Color(0xFFF7F9FD),
                timerLabel = Color(0xFF9EABBD),
                statusSurface = Color(0xFF121923),
                statusBorder = Color(0xFF273243),
                statusPrimaryIconSurface = Color(0xFF3A171B),
                statusPrimaryIconTint = Color(0xFFFF8D93),
                statusSecondaryIconSurface = Color(0xFF162334),
                statusSecondaryIconTint = Color(0xFF8EB7FF),
                stopCardTop = Color(0xFF151B24),
                stopCardBottom = Color(0xFF101720),
                stopCardBorder = Color(0xFF2A3444),
                trackBase = Color(0xFF0C1117),
                trackBaseOverlay = Color(0xFF1A2330),
                trackLabel = Color(0xFFB9C2D0),
                progressStart = Color(0xFFD53A43),
                progressEnd = Color(0xFFFF7A45),
                knobSurface = Color(0xFFF7F8FB),
                knobBorder = Color(0xFFFFB2B6),
                knobText = Color(0xFFB3261E)
            )
        } else {
            SosPalette(
                backgroundStart = Color(0xFFF8FAFD),
                backgroundMid = Color(0xFFF1F4F8),
                backgroundEnd = Color(0xFFECEFF4),
                backgroundGlowPrimary = Color(0x26FF646E),
                backgroundGlowSecondary = Color(0x143D7DFF),
                topBarScrim = Color(0xDDF8FAFD),
                heroTop = Color(0xFFFFFFFF),
                heroBottom = Color(0xFFF5F8FB),
                heroBorder = Color(0x1FB3261E),
                heroGlow = Color(0x14FF6B70),
                title = Color(0xFF111827),
                body = Color(0xFF475569),
                muted = Color(0xFF64748B),
                liveSurface = Color(0xFFFFECEE),
                liveBorder = Color(0x33D32F2F),
                liveText = Color(0xFFB42318),
                liveDot = Color(0xFFE53935),
                timerSurface = Color(0xFF121B29),
                timerOutline = Color(0xFF223045),
                timerText = Color(0xFFF8FAFC),
                timerLabel = Color(0xFFB8C4D4),
                statusSurface = Color(0xFFFFFFFF),
                statusBorder = Color(0xFFE2E8F0),
                statusPrimaryIconSurface = Color(0xFFFFEEF0),
                statusPrimaryIconTint = Color(0xFFD92D20),
                statusSecondaryIconSurface = Color(0xFFEEF4FF),
                statusSecondaryIconTint = Color(0xFF175CD3),
                stopCardTop = Color(0xFFFFFFFF),
                stopCardBottom = Color(0xFFF7F9FC),
                stopCardBorder = Color(0xFFE2E8F0),
                trackBase = Color(0xFFF3F5F8),
                trackBaseOverlay = Color(0xFFE7ECF2),
                trackLabel = Color(0xFF667085),
                progressStart = Color(0xFFD92D20),
                progressEnd = Color(0xFFF97066),
                knobSurface = Color(0xFFFFFFFF),
                knobBorder = Color(0xFFFAC5C0),
                knobText = Color(0xFFB42318)
            )
        }
    }
}

@Composable
private fun SosAmbientBackdrop(palette: SosPalette) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 96.dp, y = (-40).dp)
                .size(280.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.backgroundGlowPrimary, Color.Transparent)
                    ),
                    shape = RoundedCornerShape(999.dp)
                )
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-120).dp, y = 32.dp)
                .size(260.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(palette.backgroundGlowSecondary, Color.Transparent)
                    ),
                    shape = RoundedCornerShape(999.dp)
                )
        )
    }
}

@Composable
private fun StatusHeader(elapsedMillis: Long, scale: Float, palette: SosPalette) {
    val shape = RoundedCornerShape(28.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(14.dp, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.heroTop,
                            palette.heroBottom
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = palette.heroBorder,
                    shape = shape
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 44.dp, y = (-52).dp)
                    .size(scaledDp(180.dp, scale, min = 120.dp))
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(palette.heroGlow, Color.Transparent)
                        ),
                        shape = RoundedCornerShape(999.dp)
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = scaledDp(20.dp, scale),
                        vertical = scaledDp(20.dp, scale)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textScale = textScaleFor(scale, min = 0.72f, max = 1.02f)

                LivePill(
                    modifier = Modifier.align(Alignment.Start),
                    scale = scale,
                    palette = palette
                )
                Spacer(modifier = Modifier.height(scaledDp(16.dp, scale)))
                Text(
                    text = stringResource(R.string.sos_screen_active_title),
                    style = scaledTextStyle(
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.2).sp
                        ),
                        textScale,
                        minScale = 0.72f,
                        maxScale = 1.02f
                    ),
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.title
                )
                Spacer(modifier = Modifier.height(scaledDp(8.dp, scale)))
                Text(
                    text = stringResource(R.string.sos_screen_active_body),
                    style = scaledTextStyle(
                        MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp,
                            letterSpacing = 0.15.sp
                        ),
                        textScale,
                        minScale = 0.72f,
                        maxScale = 1.02f
                    ),
                    color = palette.body,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(scaledDp(18.dp, scale)))
                TimeBadge(
                    elapsedMillis = elapsedMillis,
                    scale = scale,
                    palette = palette
                )
                Spacer(modifier = Modifier.height(scaledDp(16.dp, scale)))
                StatusDetails(
                    elapsedMillis = elapsedMillis,
                    scale = scale,
                    palette = palette
                )
            }
        }
    }
}

@Composable
private fun rememberAnimatorDurationScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ).coerceAtLeast(0f)
        }.getOrDefault(1f)
    }
}

@Composable
private fun LivePill(modifier: Modifier = Modifier, scale: Float, palette: SosPalette) {
    val durationScale = rememberAnimatorDurationScale()
    val transition = if (durationScale > 0f) rememberInfiniteTransition(label = "live-pulse") else null
    val dotScaleState = if (transition != null) {
        transition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween((1400 * durationScale).toInt(), easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "live-dot-scale"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    val dotScale by dotScaleState

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(palette.liveSurface)
            .border(
                width = 1.dp,
                color = palette.liveBorder,
                shape = RoundedCornerShape(999.dp)
            )
            .padding(
                horizontal = scaledDp(14.dp, scale),
                vertical = scaledDp(6.dp, scale)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val textScale = textScaleFor(scale, min = 0.76f, max = 1.04f)
        Box(
            modifier = Modifier
                .size(scaledDp(16.dp, scale))
                .clip(RoundedCornerShape(999.dp))
                .background(palette.liveDot.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(scaledDp(8.dp, scale, min = 6.dp) * dotScale)
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.liveDot)
            )
        }
        Spacer(modifier = Modifier.width(scaledDp(8.dp, scale)))
        Text(
            text = stringResource(R.string.sos_screen_live_badge),
            style = scaledTextStyle(
                MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.15.sp),
                textScale,
                minScale = 0.76f,
                maxScale = 1.04f
            ),
            fontWeight = FontWeight.Bold,
            color = palette.liveText
        )
    }
}

@Composable
private fun TimeBadge(elapsedMillis: Long, scale: Float, palette: SosPalette) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = palette.timerSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.timerOutline),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.timerSurface,
                            palette.timerSurface.copy(alpha = 0.92f)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = scaledDp(18.dp, scale),
                        vertical = scaledDp(14.dp, scale)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textScale = textScaleFor(scale, min = 0.74f, max = 1.02f)
                Text(
                    text = formatElapsed(elapsedMillis),
                    style = scaledTextStyle(
                        MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1.2).sp
                        ),
                        textScale,
                        minScale = 0.74f,
                        maxScale = 1.02f
                    ),
                    color = palette.timerText
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.sos_screen_elapsed_hint),
                    style = scaledTextStyle(
                        MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.18.sp),
                        textScale,
                        minScale = 0.74f,
                        maxScale = 1.02f
                    ),
                    color = palette.timerLabel
                )
            }
        }
    }
}

@Composable
private fun StatusDetails(elapsedMillis: Long, scale: Float, palette: SosPalette) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = scaledDp(2.dp, scale)),
        verticalArrangement = Arrangement.spacedBy(scaledDp(12.dp, scale))
    ) {
        val textScale = textScaleFor(scale, min = 0.72f, max = 1.02f)
        StatusRow(
            icon = Icons.Default.CheckCircle,
            text = stringResource(R.string.sos_screen_status_active),
            scale = textScale,
            palette = palette,
            iconSurface = palette.statusPrimaryIconSurface,
            iconTint = palette.statusPrimaryIconTint
        )
        StatusRow(
            icon = Icons.Default.AccessTime,
            text = stringResource(R.string.sos_screen_status_started, formatElapsed(elapsedMillis)),
            scale = textScale,
            palette = palette,
            iconSurface = palette.statusSecondaryIconSurface,
            iconTint = palette.statusSecondaryIconTint
        )
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    text: String,
    scale: Float,
    palette: SosPalette,
    iconSurface: Color,
    iconTint: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = palette.statusSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.statusBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(10.dp),
                color = iconSurface,
                tonalElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint
                    )
                }
            }
            Text(
                text = text,
                style = scaledTextStyle(
                    MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.1.sp),
                    scale,
                    minScale = 0.72f,
                    maxScale = 1.02f
                ),
                color = palette.title,
                modifier = Modifier.weight(1f),
                maxLines = 2
            )
        }
    }
}

@Composable
private fun StopCard(
    onStopConfirmed: () -> Unit,
    scale: Float,
    palette: SosPalette
) {
    val shape = RoundedCornerShape(24.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = scaledDp(12.dp, scale))
            .shadow(10.dp, shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.stopCardTop,
                            palette.stopCardBottom
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    color = palette.stopCardBorder,
                    shape = shape
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = scaledDp(20.dp, scale),
                        vertical = scaledDp(18.dp, scale)
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textScale = textScaleFor(scale, min = 0.72f, max = 1f)
                Text(
                    text = stringResource(R.string.sos_screen_slide_title),
                    style = scaledTextStyle(
                        MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.1.sp
                        ),
                        textScale,
                        minScale = 0.72f,
                        maxScale = 1f
                    ),
                    textAlign = TextAlign.Center,
                    color = palette.title
                )
                Spacer(modifier = Modifier.height(scaledDp(12.dp, scale)))
                SlideToCancel(
                    onSlideFinished = onStopConfirmed,
                    scale = scale,
                    palette = palette
                )
                Spacer(modifier = Modifier.height(scaledDp(12.dp, scale)))
                Text(
                    text = stringResource(R.string.sos_screen_slide_hint),
                    style = scaledTextStyle(
                        MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        textScale,
                        minScale = 0.72f,
                        maxScale = 1f
                    ),
                    color = palette.muted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SlideToCancel(
    onSlideFinished: () -> Unit,
    scale: Float,
    palette: SosPalette
) {
    val knobWidth = scaledDp(92.dp, scale, min = 66.dp)
    val trackHeight = scaledDp(76.dp, scale, min = 64.dp)
    val trackPadding = scaledDp(6.dp, scale, min = 5.dp)
    val knobHeight = (trackHeight - trackPadding * 2).coerceAtLeast(scaledDp(46.dp, scale, min = 42.dp))
    val knobCornerRadius = scaledDp(14.dp, scale, min = 10.dp)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val confirmThreshold = 0.97f
    val textScale = textScaleFor(scale, min = 0.72f, max = 1f)
    val trackShape = RoundedCornerShape(scaledDp(40.dp, scale, min = 24.dp))
    val confirmActionLabel = stringResource(R.string.sos_screen_slide_action_confirm)
    val accessibilityLabel = stringResource(R.string.sos_screen_slide_label)
    val idleStateDescription = stringResource(R.string.sos_screen_slider_state_idle)
    val readyStateDescription = stringResource(R.string.sos_screen_slider_state_ready)
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var hasTriggeredStart by remember { mutableStateOf(false) }
    var hasTriggeredThreshold by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(trackShape)
            .background(palette.trackBase)
            .border(
                width = 1.dp,
                color = palette.stopCardBorder,
                shape = trackShape
            )
            .padding(trackPadding)
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val knobWidthPx = with(density) { knobWidth.toPx() }
        val maxOffsetPx = (trackWidthPx - knobWidthPx).coerceAtLeast(0f)
    val progressFraction = sliderPosition.coerceIn(0f, 1f)
    val knobOffsetPx = progressFraction * maxOffsetPx
    val isArmed = progressFraction >= confirmThreshold
    val targetAccent = if (isArmed) 1f else (progressFraction / confirmThreshold).coerceIn(0f, 1f)
    val targetVisibility by animateFloatAsState(
        targetValue = if (progressFraction > 0.01f) 1f else 0f,
        animationSpec = tween(durationMillis = 140, easing = LinearOutSlowInEasing),
        label = "sos-slide-target-visibility"
    )
    val progressWidthPx = (knobOffsetPx + knobWidthPx / 2f).coerceIn(0f, trackWidthPx)
    val progressCornerRadius = scaledDp(32.dp, scale, min = 22.dp)
    val progressShape = RoundedCornerShape(
        topStart = progressCornerRadius,
        bottomStart = progressCornerRadius,
        topEnd = progressCornerRadius * progressFraction,
        bottomEnd = progressCornerRadius * progressFraction
    )

    LaunchedEffect(progressFraction) {
        if (progressFraction > 0.03f && !hasTriggeredStart) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            hasTriggeredStart = true
        }
        if (progressFraction >= confirmThreshold && !hasTriggeredThreshold) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            hasTriggeredThreshold = true
        }
        if (progressFraction < 0.03f) {
            hasTriggeredStart = false
        }
        if (progressFraction < confirmThreshold) {
            hasTriggeredThreshold = false
        }
    }

        val trackBrush = Brush.horizontalGradient(
            colors = listOf(
                palette.progressStart,
                palette.progressEnd
            )
        )
        val draggableState = rememberDraggableState { delta ->
            val currentPx = progressFraction * maxOffsetPx
            val newPx = (currentPx + delta).coerceIn(0f, maxOffsetPx)
            sliderPosition = if (maxOffsetPx == 0f) 0f else newPx / maxOffsetPx
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics(mergeDescendants = true) {
                    contentDescription = accessibilityLabel
                    stateDescription = if (progressFraction >= confirmThreshold) {
                        readyStateDescription
                    } else {
                        idleStateDescription
                    }
                    progressBarRangeInfo = ProgressBarRangeInfo(progressFraction, 0f..1f)
                    customActions = listOf(
                        CustomAccessibilityAction(confirmActionLabel) {
                            onSlideFinished()
                            sliderPosition = 0f
                            true
                        }
                    )
                    setProgress { target ->
                        if (target >= confirmThreshold) {
                            onSlideFinished()
                            sliderPosition = 0f
                        } else {
                            sliderPosition = 0f
                        }
                        true
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(scaledDp(32.dp, scale, min = 22.dp)))
                    .background(palette.trackBaseOverlay)
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = scaledDp(8.dp, scale))
                    .width(knobWidth)
                    .height(knobHeight)
                    .clip(RoundedCornerShape(knobCornerRadius))
                    .background(
                        lerp(
                            Color.Transparent,
                            lerp(
                                palette.progressStart.copy(alpha = 0.18f),
                                palette.progressEnd.copy(alpha = if (isArmed) 0.34f else 0.24f),
                                targetAccent
                            ),
                            targetVisibility
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = lerp(
                            Color.Transparent,
                            lerp(
                                palette.progressStart.copy(alpha = 0.34f),
                                palette.progressEnd.copy(alpha = 0.58f),
                                targetAccent
                            ),
                            targetVisibility
                        ),
                        shape = RoundedCornerShape(knobCornerRadius)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = lerp(
                        Color.Transparent,
                        lerp(
                            palette.progressStart,
                            Color.White,
                            if (isArmed) 1f else targetAccent * 0.45f
                        ),
                        targetVisibility
                    ),
                    modifier = Modifier.size(scaledDp(18.dp, scale, min = 16.dp))
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(with(density) { progressWidthPx.toDp() })
                    .clip(progressShape)
                    .background(trackBrush)
            )

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = scaledDp(84.dp, scale, min = 72.dp),
                        end = scaledDp(18.dp, scale)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.sos_screen_slide_label),
                    style = scaledTextStyle(
                        MaterialTheme.typography.bodyMedium.copy(letterSpacing = 0.12.sp),
                        textScale,
                        minScale = 0.72f,
                        maxScale = 1f
                    ),
                    color = lerp(
                        palette.trackLabel,
                        Color.White,
                        0.18f + (progressFraction * 0.34f)
                    ),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End
                )
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(knobOffsetPx.toInt(), 0) }
                    .width(knobWidth)
                    .height(knobHeight)
                    .shadow(elevation = if (isArmed) 10.dp else 8.dp, shape = RoundedCornerShape(knobCornerRadius))
                    .clip(RoundedCornerShape(knobCornerRadius))
                    .background(palette.knobSurface)
                    .border(
                        width = 1.dp,
                        color = lerp(
                            palette.knobBorder,
                            palette.progressEnd.copy(alpha = 0.45f),
                            progressFraction
                        ),
                        shape = RoundedCornerShape(knobCornerRadius)
                    )
                    .draggable(
                        state = draggableState,
                        orientation = Orientation.Horizontal,
                        onDragStarted = {
                            hasTriggeredStart = false
                            hasTriggeredThreshold = false
                        },
                        onDragStopped = {
                            if (sliderPosition >= confirmThreshold) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSlideFinished()
                                sliderPosition = 0f
                            } else {
                                val start = sliderPosition
                                scope.launch {
                                    val anim = Animatable(start)
                                    anim.animateTo(
                                        targetValue = 0f,
                                        animationSpec = tween(
                                            durationMillis = 200,
                                            easing = LinearOutSlowInEasing
                                        )
                                    ) {
                                        sliderPosition = value
                                    }
                                }
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.sos_screen_slide_label),
                        tint = palette.knobText,
                        modifier = Modifier.size(scaledDp(18.dp, scale, min = 16.dp))
                    )
                    Text(
                        text = stringResource(R.string.emergency_button_label),
                        style = scaledTextStyle(
                            MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.12.sp),
                            textScale,
                            minScale = 0.72f,
                            maxScale = 1f
                        ),
                        fontWeight = FontWeight.Bold,
                        color = palette.knobText
                    )
                }
            }
        }
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun textScaleFor(scale: Float, min: Float, max: Float): Float {
    return scale.coerceIn(min, max)
}

private fun scaledTextStyle(
    style: TextStyle,
    scale: Float,
    minScale: Float,
    maxScale: Float
): TextStyle {
    val clampedScale = scale.coerceIn(minimumValue = minScale, maximumValue = maxScale)
    val fontSize = if (style.fontSize != TextUnit.Unspecified) style.fontSize * clampedScale else style.fontSize
    val lineHeight = if (style.lineHeight != TextUnit.Unspecified) style.lineHeight * clampedScale else style.lineHeight
    val letterSpacing = if (style.letterSpacing != TextUnit.Unspecified) style.letterSpacing * clampedScale else style.letterSpacing

    return style.copy(
        fontSize = fontSize,
        lineHeight = lineHeight,
        letterSpacing = letterSpacing
    )
}

private fun scaledDp(base: Dp, scale: Float, min: Dp = 4.dp): Dp {
    return (base * scale).coerceAtLeast(min)
}
