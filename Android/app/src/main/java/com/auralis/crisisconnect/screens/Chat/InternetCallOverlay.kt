package com.auralis.crisisconnect.screens.Chat

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.messaging.call.InternetCallManager
import com.auralis.crisisconnect.messaging.call.sfu.ScreenShareQualityPreset
import com.auralis.crisisconnect.ui.components.ContactAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import java.util.Locale

// Semantic action colors shared by both themes (iOS system palette — instantly readable).
private val EndRed = Color(0xFFFF453A)
private val AcceptGreen = Color(0xFF30D158)
private val WarnAmber = Color(0xFFFFB020)

/** Theme-resolved colors for the call chrome (background, text, frosted glass, buttons). */
private data class CallPalette(
    val bgTop: Color,
    val bgBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val glassFill: Color,
    val glassStroke: Color,
    val buttonFill: Color,
    val buttonContent: Color,
    val activeFill: Color,
    val activeContent: Color,
    val avatarWellFill: Color,
    val avatarWellStroke: Color,
    val pulse: Color
)

private val DarkCallPalette = CallPalette(
    bgTop = Color(0xFF16202E),
    bgBottom = Color(0xFF05070C),
    textPrimary = Color.White,
    textSecondary = Color.White.copy(alpha = 0.68f),
    glassFill = Color.White.copy(alpha = 0.08f),
    glassStroke = Color.White.copy(alpha = 0.12f),
    buttonFill = Color.White.copy(alpha = 0.14f),
    buttonContent = Color.White,
    activeFill = Color.White,
    activeContent = Color(0xFF0B1220),
    avatarWellFill = Color.White.copy(alpha = 0.06f),
    avatarWellStroke = Color.White.copy(alpha = 0.15f),
    pulse = Color.White
)

private val LightCallPalette = CallPalette(
    bgTop = Color(0xFFF4F6FB),
    bgBottom = Color(0xFFDCE4EF),
    textPrimary = Color(0xFF101828),
    textSecondary = Color(0xFF101828).copy(alpha = 0.62f),
    glassFill = Color(0xFF101828).copy(alpha = 0.05f),
    glassStroke = Color(0xFF101828).copy(alpha = 0.09f),
    buttonFill = Color(0xFF101828).copy(alpha = 0.08f),
    buttonContent = Color(0xFF101828),
    activeFill = Color(0xFF101828),
    activeContent = Color.White,
    avatarWellFill = Color(0xFF101828).copy(alpha = 0.04f),
    avatarWellStroke = Color(0xFF101828).copy(alpha = 0.10f),
    pulse = Color(0xFF101828)
)

/**
 * Full-screen overlay for an internet (WebRTC) call, driven by [InternetCallManager]'s state
 * (exposed via ChatScreenViewModel.internetCall). Kept separate from the Bluetooth [CallOverlay]
 * because internet calls are the only ones that carry video: when the call is ACTIVE and a video
 * m-line was negotiated ([CallInfo.canUseVideo]), camera and screen-share controls appear and the
 * remote/local video render here. Bluetooth calls use their own overlay and never show these — which
 * is exactly the requirement (video/screen-share only over the internet). The peer may be on web.
 *
 * Design: immersive gradient that follows the app's light/dark theme (dark chrome is forced while
 * video is on screen — text sits on dark scrims there), pulsing avatar rings while ringing, a
 * frosted-glass control tray, labelled accept/decline for incoming calls, and tap-to-hide controls
 * during video.
 */
@Composable
fun InternetCallOverlay(
    call: InternetCallManager.CallInfo,
    muted: Boolean,
    videoStreams: InternetCallManager.VideoStreams,
    eglContext: EglBase.Context?,
    screenShareQuality: ScreenShareQualityPreset? = null,
    onScreenShareQualityChanged: (ScreenShareQualityPreset) -> Unit = {},
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onSwitchCamera: () -> Unit,
    onMinimize: (() -> Unit)? = null
) {
    val isIncomingRinging = call.incoming && call.state == InternetCallManager.State.INCOMING
    val isRingingPhase = call.state == InternetCallManager.State.INCOMING ||
        call.state == InternetCallManager.State.DIALING ||
        call.state == InternetCallManager.State.CONNECTING
    val showVideoControls = call.state == InternetCallManager.State.ACTIVE && call.canUseVideo
    // The peer's screen owns the stage when present; their camera then drops into a PiP.
    val hasRemoteStage = call.remoteScreen || call.remoteVideo
    val hasLocalVideo = call.cameraOn || call.sharingScreen
    val hasAnyVideo = hasRemoteStage || hasLocalVideo
    val immersiveVideo = hasAnyVideo && call.state == InternetCallManager.State.ACTIVE
    val context = LocalContext.current

    // Follow the app theme; while any video renders, the chrome sits on dark scrims → force dark.
    val appIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (appIsDark || hasAnyVideo) DarkCallPalette else LightCallPalette

    // The saved contact photo for the big avatar (falls back to initials inside ContactAvatar).
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, call.sessionCode) {
        value = if (call.sessionCode.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { ContactAvatarStorage.loadContactAvatar(context, call.sessionCode) }.getOrNull()
        }
    }

    // FaceTime-style: during a video call a tap anywhere toggles the chrome, and it auto-hides.
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(immersiveVideo, controlsVisible, call.state) {
        if (immersiveVideo && controlsVisible) {
            delay(5_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(call.state) { controlsVisible = true }
    val chromeVisible = controlsVisible || !immersiveVideo

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))
            .pointerInput(immersiveVideo) {
                if (immersiveVideo) {
                    detectTapGestures { controlsVisible = !controlsVisible }
                }
            }
    ) {
        // Remote screen (preferred) or camera, full-screen behind everything (only once frames arrive).
        if (hasRemoteStage) {
            // Letterbox by SIZING THE VIEW, not by asking for SCALE_ASPECT_FIT. FIT alone does
            // nothing here: libwebrtc only honours it while measuring, and RendererCommon yields to
            // any EXACTLY spec — which fillMaxSize (and AndroidView's default MATCH_PARENT
            // LayoutParams) always produce. onLayout then feeds EglRenderer the raw 1080/2340 = 0.462
            // view aspect and its draw matrix center-crops the texture (a 1.45-aspect iPad frame kept
            // only its middle 32%: the reported sliced-off edges, with no bars, because the renderer
            // has no bar-drawing path at all).
            // So: track the incoming frame aspect and constrain the view to it. Then
            // frameAspectRatio == layoutAspectRatio, the draw matrix collapses to identity, and the
            // bars are just the empty space Compose leaves around a centred child.
            var stageAspect by remember { mutableStateOf(0f) }
            WebRtcVideoView(
                track = if (call.remoteScreen) videoStreams.remoteScreen else videoStreams.remote,
                eglContext = eglContext,
                scaling = RendererCommon.ScalingType.SCALE_ASPECT_FIT,
                // Before the first frame the aspect is unknown → fill, exactly as before.
                modifier = if (stageAspect > 0f) {
                    Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center)
                        .aspectRatio(stageAspect)
                } else {
                    Modifier.fillMaxSize()
                },
                onFrameGeometry = { w, h, rotation ->
                    // Rotation rides as frame metadata (an iPad in landscape sends 90/270), so the
                    // DISPLAYED dimensions are swapped for the odd quarter-turns.
                    val rw = if (rotation == 0 || rotation == 180) w else h
                    val rh = if (rotation == 0 || rotation == 180) h else w
                    if (rw > 0 && rh > 0) stageAspect = rw / rh.toFloat()
                }
            )
        }

        // Legibility scrims over video: darken the strips where text/controls sit.
        if (hasAnyVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
            )
        }

        // Minimize: shrink the call into the floating banner. Hidden while an incoming call rings —
        // the accept/decline actions must stay on screen.
        if (onMinimize != null && !isIncomingRinging) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.align(Alignment.TopStart)
            ) {
                Box(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 12.dp, start = 16.dp)
                ) {
                    CallActionButton(
                        icon = Icons.Filled.KeyboardArrowDown,
                        background = palette.buttonFill,
                        tint = palette.buttonContent,
                        contentDescription = stringResource(R.string.chat_call_cancel),
                        size = 44.dp,
                        iconSize = 26.dp,
                        onClick = onMinimize
                    )
                }
            }
        }

        // Header: transport pill, peer name, state, ticking duration.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 3 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 18.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(palette.glassFill)
                        .border(1.dp, palette.glassStroke, RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_call_via_internet),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.textSecondary
                    )
                }
                Spacer(Modifier.height(26.dp))
                Text(
                    text = call.peerName.ifBlank { stringResource(R.string.internet_call_unknown_peer) },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                when {
                    call.reconnecting -> Text(
                        text = stringResource(R.string.internet_call_reconnecting),
                        style = MaterialTheme.typography.bodyLarge,
                        color = WarnAmber
                    )
                    call.state == InternetCallManager.State.ACTIVE && call.connectedAt != null -> Text(
                        text = rememberInternetCallDuration(call.connectedAt),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = palette.textSecondary
                    )
                    else -> Text(
                        text = stringResource(stateLabelRes(call.state, call.incoming)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.textSecondary
                    )
                }
                // Live network-quality warning (from getStats RTT + loss); silent while all is well.
                if (!call.reconnecting &&
                    call.state == InternetCallManager.State.ACTIVE &&
                    call.quality != InternetCallManager.CallQuality.GOOD
                ) {
                    Spacer(Modifier.height(8.dp))
                    val poor = call.quality == InternetCallManager.CallQuality.POOR
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background((if (poor) EndRed else WarnAmber).copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SignalCellularAlt,
                            contentDescription = null,
                            tint = if (poor) EndRed else WarnAmber,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(
                                if (poor) R.string.internet_call_quality_poor
                                else R.string.internet_call_quality_fair
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (poor) EndRed else WarnAmber
                        )
                    }
                }
            }
        }

        // Big avatar with pulsing rings while ringing — hidden once remote video takes the stage.
        if (!hasRemoteStage) {
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                if (isRingingPhase) {
                    PulseRing(diameter = 176.dp, startOffsetMs = 0, color = palette.pulse)
                    PulseRing(diameter = 176.dp, startOffsetMs = 900, color = palette.pulse)
                }
                Box(
                    modifier = Modifier
                        .size(176.dp)
                        .clip(CircleShape)
                        .background(palette.avatarWellFill)
                        .border(1.dp, palette.avatarWellStroke, CircleShape)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ContactAvatar(
                        displayName = call.peerName.ifBlank {
                            stringResource(R.string.internet_call_unknown_peer)
                        },
                        stableKey = call.sessionCode.ifBlank { call.peerUid },
                        bitmap = avatarBitmap,
                        modifier = Modifier.size(156.dp),
                        textStyle = MaterialTheme.typography.displaySmall
                    )
                }
            }
        }

        // Picture-in-picture stack: own self-view (camera preferred over screen preview) on top, and
        // the peer's camera beneath it while their screen owns the stage.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 12.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (hasLocalVideo) {
                WebRtcVideoView(
                    track = videoStreams.local ?: videoStreams.localScreen,
                    eglContext = eglContext,
                    mirror = videoStreams.local != null && call.cameraOn && call.usingFrontCamera,
                    overlay = true,
                    modifier = Modifier
                        .width(108.dp)
                        .height(156.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                )
            }
            if (call.remoteScreen && call.remoteVideo) {
                WebRtcVideoView(
                    track = videoStreams.remote,
                    eglContext = eglContext,
                    overlay = true,
                    modifier = Modifier
                        .width(108.dp)
                        .height(156.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                )
            }
        }

        // Bottom chrome: incoming = labelled accept/decline; otherwise the frosted control tray.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            if (isIncomingRinging) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 48.dp, start = 40.dp, end = 40.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LabelledCallButton(
                        icon = Icons.Filled.CallEnd,
                        background = EndRed,
                        tint = Color.White,
                        label = stringResource(R.string.internet_call_reject),
                        labelColor = palette.textSecondary,
                        onClick = onReject
                    )
                    LabelledCallButton(
                        icon = Icons.Filled.Call,
                        background = AcceptGreen,
                        tint = Color.White,
                        label = stringResource(R.string.internet_call_accept),
                        labelColor = palette.textSecondary,
                        pulse = true,
                        onClick = onAccept
                    )
                }
            } else {
                // 4 fixed 60dp buttons + gaps overflow narrow windows (e.g. Samsung screen zoom):
                // Row then squeezes the LAST child — the red hang-up button — into the leftover
                // width and CircleShape renders it with flat, cut-off sides. Shrink every button
                // evenly so the tray always fits instead.
                BoxWithConstraints {
                    val mainButtonCount = if (showVideoControls) 4 else 3
                    val buttonSpacing = 18.dp
                    val trayInnerWidth = maxWidth - 40.dp - 36.dp // outer + tray padding
                    val mainButtonSize =
                        ((trayInnerWidth - buttonSpacing * (mainButtonCount - 1)) / mainButtonCount)
                            .coerceIn(44.dp, 60.dp)
                    // Integer icon dp on purpose — see the 2026-07-08 "kenarları kırpılmış" report.
                    val toggleIconSize = (mainButtonSize.value * 26f / 60f).toInt().dp
                    val endIconSize = (mainButtonSize.value * 28f / 60f).toInt().dp
                    Column(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = 28.dp, start = 20.dp, end = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(36.dp))
                                .background(palette.glassFill)
                                .border(1.dp, palette.glassStroke, RoundedCornerShape(36.dp))
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (showVideoControls) {
                                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    if (call.cameraOn) {
                                        GlassCallButton(
                                            icon = Icons.Filled.Cameraswitch,
                                            active = false,
                                            palette = palette,
                                            contentDescription = stringResource(R.string.internet_call_switch_camera),
                                            size = 48.dp,
                                            iconSize = 22.dp,
                                            onClick = onSwitchCamera
                                        )
                                    }
                                    GlassCallButton(
                                        icon = if (call.sharingScreen) Icons.AutoMirrored.Filled.StopScreenShare
                                        else Icons.AutoMirrored.Filled.ScreenShare,
                                        active = call.sharingScreen,
                                        palette = palette,
                                        contentDescription = stringResource(
                                            if (call.sharingScreen) R.string.internet_call_screen_share_stop
                                            else R.string.internet_call_screen_share_start
                                        ),
                                        size = 48.dp,
                                        iconSize = 22.dp,
                                        onClick = onToggleScreenShare
                                    )
                                    if (screenShareQuality != null) {
                                        Box {
                                            GlassCallButton(
                                                icon = Icons.Filled.Settings,
                                                active = false,
                                                palette = palette,
                                                contentDescription = "Screen share quality: ${screenShareQuality.displayName}",
                                                size = 48.dp,
                                                iconSize = 22.dp,
                                                onClick = { qualityMenuExpanded = true },
                                            )
                                            DropdownMenu(
                                                expanded = qualityMenuExpanded,
                                                onDismissRequest = { qualityMenuExpanded = false },
                                            ) {
                                                ScreenShareQualityPreset.entries.forEach { preset ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                text = if (preset == screenShareQuality) {
                                                                    "✓ ${preset.displayName}"
                                                                } else {
                                                                    preset.displayName
                                                                },
                                                            )
                                                        },
                                                        onClick = {
                                                            qualityMenuExpanded = false
                                                            onScreenShareQualityChanged(preset)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(14.dp))
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlassCallButton(
                                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                    active = muted,
                                    palette = palette,
                                    contentDescription = stringResource(
                                        if (muted) R.string.internet_call_unmute else R.string.internet_call_mute
                                    ),
                                    size = mainButtonSize,
                                    iconSize = toggleIconSize,
                                    onClick = onToggleMute
                                )
                                GlassCallButton(
                                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                                    active = call.speakerOn,
                                    palette = palette,
                                    contentDescription = stringResource(
                                        if (call.speakerOn) R.string.chat_call_speaker_off
                                        else R.string.chat_call_speaker_on
                                    ),
                                    size = mainButtonSize,
                                    iconSize = toggleIconSize,
                                    onClick = onToggleSpeaker
                                )
                                if (showVideoControls) {
                                    GlassCallButton(
                                        icon = if (call.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                        active = call.cameraOn,
                                        palette = palette,
                                        contentDescription = stringResource(
                                            if (call.cameraOn) R.string.internet_call_video_off
                                            else R.string.internet_call_video_on
                                        ),
                                        size = mainButtonSize,
                                        iconSize = toggleIconSize,
                                        onClick = onToggleVideo
                                    )
                                }
                                CallActionButton(
                                    icon = Icons.Filled.CallEnd,
                                    background = EndRed,
                                    tint = Color.White,
                                    contentDescription = stringResource(R.string.internet_call_end),
                                    size = mainButtonSize,
                                    iconSize = endIconSize,
                                    onClick = onEnd
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val ScreenShareQualityPreset.displayName: String
    get() = when (this) {
        ScreenShareQualityPreset.AUTO -> "Auto"
        ScreenShareQualityPreset.SMOOTH -> "Smooth"
        ScreenShareQualityPreset.HIGH -> "High"
        ScreenShareQualityPreset.ULTRA -> "Ultra"
    }

/** One expanding ring of the "ringing" pulse; two of these are staggered for a sonar effect. */
@Composable
private fun PulseRing(diameter: Dp, startOffsetMs: Int, color: Color) {
    val transition = rememberInfiniteTransition(label = "call_pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_000, easing = FastOutSlowInEasing),
            initialStartOffset = StartOffset(startOffsetMs)
        ),
        label = "call_pulse_progress"
    )
    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer {
                val s = 1f + 0.45f * progress
                scaleX = s
                scaleY = s
                alpha = (1f - progress) * 0.35f
            }
            .border(1.5.dp, color, CircleShape)
    )
}

/** Frosted translucent toggle button: fills solid with the palette's active color when [active]. */
@Composable
private fun GlassCallButton(
    icon: ImageVector,
    active: Boolean,
    palette: CallPalette,
    contentDescription: String,
    size: Dp = 60.dp,
    iconSize: Dp = 26.dp,
    onClick: () -> Unit
) {
    CallActionButton(
        icon = icon,
        background = if (active) palette.activeFill else palette.buttonFill,
        tint = if (active) palette.activeContent else palette.buttonContent,
        contentDescription = contentDescription,
        size = size,
        iconSize = iconSize,
        onClick = onClick
    )
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    background: Color,
    tint: Color,
    contentDescription: String,
    size: Dp = 60.dp,
    iconSize: Dp = 26.dp,
    onClick: () -> Unit
) {
    // Hand-rolled circle (clip + background + clickable) instead of a clickable M3 Surface: the
    // Surface path rendered as a corner-cut squircle on some devices (visibly NOT a circle on the
    // high-contrast red hang-up button).
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Big incoming-call action with a text label beneath (and a gentle breathing pulse for accept). */
@Composable
private fun LabelledCallButton(
    icon: ImageVector,
    background: Color,
    tint: Color,
    label: String,
    labelColor: Color,
    pulse: Boolean = false,
    onClick: () -> Unit
) {
    val scale = if (pulse) {
        val transition = rememberInfiniteTransition(label = "accept_pulse")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "accept_pulse_scale"
        )
        value
    } else 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(background)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(32.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = labelColor
        )
    }
}

/**
 * Compact floating banner for a MINIMIZED internet call — rendered app-wide by the overlay hosts so
 * the user can keep using the app mid-call. Tap to reopen the full call screen; red circle hangs up.
 */
@Composable
fun InternetCallMiniBanner(
    call: InternetCallManager.CallInfo,
    onOpen: () -> Unit,
    onHangup: () -> Unit
) {
    val appIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (appIsDark) DarkCallPalette else LightCallPalette
    val context = LocalContext.current
    val avatarBitmap by produceState<Bitmap?>(initialValue = null, call.sessionCode) {
        value = if (call.sessionCode.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { ContactAvatarStorage.loadContactAvatar(context, call.sessionCode) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(28.dp),
            color = palette.bgTop,
            border = BorderStroke(1.dp, palette.glassStroke),
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ContactAvatar(
                    displayName = call.peerName.ifBlank {
                        stringResource(R.string.internet_call_unknown_peer)
                    },
                    stableKey = call.sessionCode.ifBlank { call.peerUid },
                    bitmap = avatarBitmap,
                    modifier = Modifier.size(40.dp),
                    textStyle = MaterialTheme.typography.titleMedium
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = call.peerName.ifBlank {
                            stringResource(R.string.internet_call_unknown_peer)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val active = call.state == InternetCallManager.State.ACTIVE && call.connectedAt != null
                    Text(
                        text = when {
                            call.reconnecting -> stringResource(R.string.internet_call_reconnecting)
                            active -> rememberInternetCallDuration(call.connectedAt!!)
                            else -> stringResource(stateLabelRes(call.state, call.incoming))
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = if (active && !call.reconnecting) FontFamily.Monospace else FontFamily.Default,
                        color = if (call.reconnecting) WarnAmber else palette.textSecondary,
                        maxLines = 1
                    )
                }
                CallActionButton(
                    icon = Icons.Filled.CallEnd,
                    background = EndRed,
                    tint = Color.White,
                    contentDescription = stringResource(R.string.internet_call_end),
                    size = 40.dp,
                    iconSize = 20.dp,
                    onClick = onHangup
                )
            }
        }
    }
}

/** Ticking mm:ss (or h:mm:ss) since [connectedAt], re-rendering once per second. */
@Composable
private fun rememberInternetCallDuration(connectedAt: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val totalSeconds = ((now - connectedAt).coerceAtLeast(0L)) / 1_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun stateLabelRes(state: InternetCallManager.State, incoming: Boolean): Int = when (state) {
    InternetCallManager.State.INCOMING -> R.string.internet_call_incoming
    InternetCallManager.State.DIALING -> R.string.internet_call_dialing
    InternetCallManager.State.CONNECTING -> R.string.internet_call_connecting
    InternetCallManager.State.ACTIVE -> R.string.internet_call_active
    else -> if (incoming) R.string.internet_call_incoming else R.string.internet_call_dialing
}
