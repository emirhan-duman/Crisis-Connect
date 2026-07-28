package com.auralis.crisisconnect.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.service.GattSOSServerService
import com.auralis.crisisconnect.service.sos.EmergencyNumberResolver
import com.auralis.crisisconnect.service.sos.SosCloudReporter
import com.auralis.crisisconnect.service.sos.SosContactNotifier
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
    val title: Color,
    val body: Color,
    val muted: Color,
    val liveSurface: Color,
    val liveBorder: Color,
    val liveText: Color,
    val liveDot: Color,
    val timerText: Color,
    val timerLabel: Color,
    val statusSurface: Color,
    val statusBorder: Color,
    val statusPrimaryIconSurface: Color,
    val statusPrimaryIconTint: Color,
    val statusSecondaryIconSurface: Color,
    val statusSecondaryIconTint: Color,
    val trackBase: Color,
    val trackLabel: Color,
    val progressStart: Color,
    val progressEnd: Color,
    val knobSurface: Color,
    val knobText: Color
)

/**
 * Live SOS screen — a single-viewport (no scroll) three-stage escalation: the automatic broadcast
 * condensed into a hero card with status chips, a one-tap regional emergency call, the notified
 * contacts as avatar chips, and the stop slider pinned to the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SOSScreen(navController: NavController) {
    val isRunning by GattSOSServerService.isDeclared.collectAsStateWithLifecycle()
    val startTimestamp by GattSOSServerService.startTimestampMillis.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val palette = rememberSosPalette()
    var hasSeenRunning by rememberSaveable { mutableStateOf(false) }
    val elapsedMillis by rememberElapsedMillis(startTimestamp = startTimestamp, isRunning = isRunning)
    val peers by BlePeerStore.peers.collectAsStateWithLifecycle()
    val cloudReportState by SosCloudReporter.state.collectAsStateWithLifecycle()
    val recipients by SosContactNotifier.recipients.collectAsStateWithLifecycle()
    val recipientsReady by SosContactNotifier.selectionReady.collectAsStateWithLifecycle()
    val recipientsSource by SosContactNotifier.selectionSource.collectAsStateWithLifecycle()
    val emergencyNumber = remember(context) { EmergencyNumberResolver.resolve(context) }

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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                HeroCard(
                    palette = palette,
                    elapsedMillis = elapsedMillis,
                    peerCount = peers.size,
                    cloudReportState = cloudReportState
                )

                CallCard(
                    palette = palette,
                    emergencyNumber = emergencyNumber,
                    onCall = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$emergencyNumber"))
                            )
                        }
                    }
                )

                ContactsCard(
                    palette = palette,
                    recipients = recipients,
                    selectionReady = recipientsReady,
                    selectionSource = recipientsSource
                )

                Spacer(modifier = Modifier.weight(1f))

                SosSlideAction(
                    label = stringResource(R.string.sos_screen_slide_label),
                    confirmActionLabel = stringResource(R.string.sos_screen_slide_action_confirm),
                    idleStateDescription = stringResource(R.string.sos_screen_slider_state_idle),
                    readyStateDescription = stringResource(R.string.sos_screen_slider_state_ready),
                    style = SosSlideStyle(
                        track = palette.trackBase,
                        trackBorder = palette.statusBorder,
                        progressStart = palette.progressStart,
                        progressEnd = palette.progressEnd,
                        label = palette.trackLabel,
                        knob = palette.knobSurface,
                        knobIcon = palette.knobText
                    ),
                    height = 68.dp,
                    onConfirmed = { GattSOSServerService.stopBroadcast(context) }
                )
                Text(
                    text = stringResource(R.string.sos_screen_slide_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp)
                )
            }
        }
    }
}

// ─── Hero: beacon + timer + status chips ──────────────────────────────────────

@Composable
private fun HeroCard(
    palette: SosPalette,
    elapsedMillis: Long,
    peerCount: Int,
    cloudReportState: SosCloudReporter.State
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(palette.heroTop, palette.heroBottom)))
            .border(width = 1.dp, color = palette.heroBorder, shape = shape)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SosBeacon(palette = palette)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    LivePill(palette = palette)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatElapsed(elapsedMillis),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        ),
                        color = palette.timerText
                    )
                    Text(
                        text = stringResource(R.string.sos_screen_elapsed_hint),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.2.sp),
                        color = palette.timerLabel
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(palette.statusBorder.copy(alpha = 0.7f))
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    icon = Icons.Default.CheckCircle,
                    text = stringResource(R.string.sos_stage_broadcast_title),
                    tint = palette.statusPrimaryIconTint,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    icon = if (peerCount > 0) {
                        Icons.Default.BluetoothConnected
                    } else {
                        Icons.Default.BluetoothSearching
                    },
                    text = if (peerCount > 0) {
                        stringResource(R.string.sos_chip_peers, peerCount)
                    } else {
                        stringResource(R.string.sos_chip_searching)
                    },
                    tint = palette.statusSecondaryIconTint,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
                val (cloudIcon, cloudText) = cloudChipContent(cloudReportState)
                StatusChip(
                    icon = cloudIcon,
                    text = cloudText,
                    tint = palette.statusSecondaryIconTint,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Short cloud-uplink chip label; the detailed wording lives in the reporter's log/tooltips. */
@Composable
private fun cloudChipContent(state: SosCloudReporter.State): Pair<ImageVector, String> {
    return when (state) {
        SosCloudReporter.State.Idle,
        SosCloudReporter.State.WaitingForNetwork ->
            Icons.Default.CloudOff to stringResource(R.string.sos_chip_no_internet)
        SosCloudReporter.State.Sending ->
            Icons.Default.CloudUpload to stringResource(R.string.sos_contact_status_sending)
        is SosCloudReporter.State.Reported -> {
            val placeName = state.placeName
            Icons.Default.CloudDone to if (placeName.isNullOrBlank()) {
                stringResource(R.string.sos_chip_reported)
            } else {
                placeName
            }
        }
        SosCloudReporter.State.Failed ->
            Icons.Default.CloudOff to stringResource(R.string.sos_contact_status_failed)
    }
}

@Composable
private fun StatusChip(
    icon: ImageVector,
    text: String,
    tint: Color,
    palette: SosPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.statusSurface)
            .border(1.dp, palette.statusBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = palette.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SosBeacon(palette: SosPalette) {
    val durationScale = rememberAnimatorDurationScale()
    var ringScale = 1.3f
    var ringAlpha = 0.2f
    if (durationScale > 0f) {
        val transition = rememberInfiniteTransition(label = "sos-beacon")
        val duration = (1_800 * durationScale).toInt().coerceAtLeast(1)
        ringScale = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.55f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sos-beacon-ring-scale"
        ).value
        ringAlpha = transition.animateFloat(
            initialValue = 0.5f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(duration, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sos-beacon-ring-alpha"
        ).value
    }

    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .scale(ringScale)
                .alpha(ringAlpha)
                .border(width = 2.dp, color = palette.liveDot, shape = CircleShape)
        )
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(listOf(palette.progressStart, palette.progressEnd))
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_alarm_sos),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun LivePill(palette: SosPalette) {
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
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.liveSurface)
            .border(width = 1.dp, color = palette.liveBorder, shape = CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(palette.liveDot.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp * dotScale)
                    .clip(CircleShape)
                    .background(palette.liveDot)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.sos_screen_live_badge),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.15.sp),
            fontWeight = FontWeight.Bold,
            color = palette.liveText
        )
    }
}

// ─── Emergency call ───────────────────────────────────────────────────────────

@Composable
private fun CallCard(
    palette: SosPalette,
    emergencyNumber: String,
    onCall: () -> Unit
) {
    SectionCard(palette) {
        TitleRow(
            title = stringResource(R.string.sos_stage_call_title),
            badge = stringResource(R.string.sos_stage_tap_badge),
            palette = palette
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onCall,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.progressStart,
                contentColor = Color.White
            )
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.sos_call_button_label, emergencyNumber),
                style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.1.sp),
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.sos_call_hint),
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
            color = palette.muted
        )
    }
}

// ─── Notified contacts ───────────────────────────────────────────────────────

@Composable
private fun ContactsCard(
    palette: SosPalette,
    recipients: List<SosContactNotifier.Recipient>,
    selectionReady: Boolean,
    selectionSource: SosContactNotifier.SelectionSource
) {
    SectionCard(palette) {
        TitleRow(
            title = stringResource(R.string.sos_stage_contacts_title),
            badge = if (selectionSource == SosContactNotifier.SelectionSource.Custom) {
                stringResource(R.string.sos_chip_custom_selection)
            } else {
                stringResource(R.string.sos_stage_auto_badge)
            },
            palette = palette
        )
        Spacer(modifier = Modifier.height(10.dp))
        when {
            !selectionReady -> {
                Text(
                    text = stringResource(R.string.sos_contacts_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.body
                )
            }
            recipients.isEmpty() -> {
                Text(
                    text = stringResource(R.string.sos_contacts_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.body
                )
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    recipients.forEach { recipient ->
                        RecipientChip(recipient = recipient, palette = palette)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val sentCount =
                    recipients.count { it.status == SosContactNotifier.RecipientStatus.Sent }
                Text(
                    text = stringResource(
                        R.string.sos_contacts_summary,
                        sentCount,
                        recipients.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
        }
    }
}

/** A contact as a small avatar (initial) with a delivery-status badge in its corner. */
@Composable
private fun RecipientChip(
    recipient: SosContactNotifier.Recipient,
    palette: SosPalette
) {
    val initial = recipient.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val sent = recipient.status == SosContactNotifier.RecipientStatus.Sent
    Box(modifier = Modifier.size(44.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.statusSecondaryIconSurface)
                .border(1.dp, palette.statusBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = palette.statusSecondaryIconTint
            )
        }
        Box(
            modifier = Modifier
                .size(16.dp)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(
                    if (sent) palette.statusSecondaryIconTint else palette.muted
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (sent) Icons.Default.Check else Icons.Default.Schedule,
                contentDescription = null,
                tint = palette.statusSurface,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

// ─── Shared building blocks ───────────────────────────────────────────────────

@Composable
private fun SectionCard(
    palette: SosPalette,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.statusSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.statusBorder),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            content = content
        )
    }
}

@Composable
private fun TitleRow(
    title: String,
    badge: String,
    palette: SosPalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.1.sp),
            fontWeight = FontWeight.SemiBold,
            color = palette.title,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.3.sp),
            color = palette.muted
        )
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
                    shape = CircleShape
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
                    shape = CircleShape
                )
        )
    }
}

// ─── State helpers ────────────────────────────────────────────────────────────

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
                title = Color(0xFFF7F9FD),
                body = Color(0xFFD8DEE8),
                muted = Color(0xFF9EABBD),
                liveSurface = Color(0xFF38181C),
                liveBorder = Color(0x4CFF6E74),
                liveText = Color(0xFFFFD6D8),
                liveDot = Color(0xFFFF6B70),
                timerText = Color(0xFFF7F9FD),
                timerLabel = Color(0xFF9EABBD),
                statusSurface = Color(0xFF121923),
                statusBorder = Color(0xFF273243),
                statusPrimaryIconSurface = Color(0xFF3A171B),
                statusPrimaryIconTint = Color(0xFFFF8D93),
                statusSecondaryIconSurface = Color(0xFF162334),
                statusSecondaryIconTint = Color(0xFF8EB7FF),
                trackBase = Color(0xFF0C1117),
                trackLabel = Color(0xFFB9C2D0),
                progressStart = Color(0xFFD53A43),
                progressEnd = Color(0xFFFF7A45),
                knobSurface = Color(0xFFF7F8FB),
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
                title = Color(0xFF111827),
                body = Color(0xFF475569),
                muted = Color(0xFF64748B),
                liveSurface = Color(0xFFFFECEE),
                liveBorder = Color(0x33D32F2F),
                liveText = Color(0xFFB42318),
                liveDot = Color(0xFFE53935),
                timerText = Color(0xFF111827),
                timerLabel = Color(0xFF64748B),
                statusSurface = Color(0xFFFFFFFF),
                statusBorder = Color(0xFFE2E8F0),
                statusPrimaryIconSurface = Color(0xFFFFEEF0),
                statusPrimaryIconTint = Color(0xFFD92D20),
                statusSecondaryIconSurface = Color(0xFFEEF4FF),
                statusSecondaryIconTint = Color(0xFF175CD3),
                trackBase = Color(0xFFF3F5F8),
                trackLabel = Color(0xFF667085),
                progressStart = Color(0xFFD92D20),
                progressEnd = Color(0xFFF97066),
                knobSurface = Color(0xFFFFFFFF),
                knobText = Color(0xFFB42318)
            )
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
