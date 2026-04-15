package com.auralis.crisisconnect.ui.components

import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.audio.AudioWaveformRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val BAR_SPACING_DP = 2f
private const val DEFAULT_PLACEHOLDER_BARS = 48
private const val LIVE_BLEND_FACTOR = 0.2f

@OptIn(UnstableApi::class)
@Composable
fun AudioMessageCard(
    uri: Uri,
    useLiveVisualizer: Boolean = false,
    modifier: Modifier = Modifier,
    initialDurationMillis: Long? = null,
    waveBaseColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
    waveActiveColor: Color = MaterialTheme.colorScheme.primary,
    timeTextColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
    controlContainerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.18f),
    controlContentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val context = LocalContext.current
    val repository = remember(context) { AudioWaveformRepository(context.applicationContext) }
    val coroutineScope = rememberCoroutineScope()

    var waveform by remember(uri) { mutableStateOf<List<Float>>(emptyList()) }
    var waveformJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(uri) {
        waveformJob?.cancel()
        waveformJob = coroutineScope.launch {
            waveform = repository.loadWaveform(uri)
        }
        onDispose { waveformJob?.cancel() }
    }

    var player by remember(uri) { mutableStateOf<ExoPlayer?>(null) }
    // TODO: Consider hoisting a shared ExoPlayer instance into a ViewModel or parent state when
    // simultaneous playback across cards is not required.

    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember(initialDurationMillis) { mutableStateOf(initialDurationMillis ?: 0L) }
    var positionMs by remember { mutableStateOf(0L) }
    var audioSessionId by remember { mutableStateOf(C.AUDIO_SESSION_ID_UNSET) }

    fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        val newPlayer = ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(uri)
            setMediaItem(mediaItem)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            prepare()
        }
        player = newPlayer
        audioSessionId = newPlayer.audioSessionId
        return newPlayer
    }

    fun releasePlayer(resetPosition: Boolean) {
        val current = player ?: return
        current.pause()
        if (resetPosition) {
            current.seekTo(0)
            positionMs = 0L
        }
        player = null
        isPlaying = false
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    DisposableEffect(player) {
        val currentPlayer = player
        if (currentPlayer == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    isPlaying = player.isPlaying
                    val sessionId = (player as? ExoPlayer)?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
                        audioSessionId = sessionId
                    }
                    val duration = player.duration
                    if (duration != C.TIME_UNSET) {
                        durationMs = duration
                    }
                }

                override fun onAudioSessionIdChanged(newAudioSessionId: Int) {
                    audioSessionId = newAudioSessionId
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        releasePlayer(resetPosition = true)
                    }
                }
            }
            currentPlayer.addListener(listener)
            onDispose {
                currentPlayer.removeListener(listener)
                currentPlayer.release()
            }
        }
    }

    LaunchedEffect(player) {
        val currentPlayer = player ?: return@LaunchedEffect
        while (isActive && player === currentPlayer) {
            withFrameNanos {
                positionMs = currentPlayer.currentPosition
                val duration = currentPlayer.duration
                if (duration != C.TIME_UNSET && duration > 0) {
                    durationMs = duration
                }
                isPlaying = currentPlayer.isPlaying
            }
        }
    }

    val liveVisualizerData by rememberLiveWaveform(useLiveVisualizer, audioSessionId)
    val blendedWaveform = remember(waveform, liveVisualizerData) {
        AudioWaveformRepository.blendWaveforms(waveform, liveVisualizerData, LIVE_BLEND_FACTOR)
            .takeIf { it.hasMeaningfulAmplitude() }
            ?: waveform
    }

    val displayWaveform = if (blendedWaveform.hasMeaningfulAmplitude()) {
        blendedWaveform
    } else {
        placeholderWaveform()
    }
    val progress = calculateProgress(positionMs, durationMs)
    val progressState by animateFloatAsState(targetValue = progress, label = "playback-progress")
    val playButtonIconColor by animateColorAsState(
        targetValue = if (isPlaying) {
            waveActiveColor
        } else {
            controlContentColor
        },
        label = "audio_play_button_icon"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(
            onClick = {
                val currentPlayer = player
                if (currentPlayer != null && (isPlaying || currentPlayer.isPlaying)) {
                    releasePlayer(resetPosition = true)
                } else {
                    val readyPlayer = ensurePlayer()
                    if (durationMs > 0 && positionMs >= durationMs) {
                        readyPlayer.seekTo(0)
                    }
                    readyPlayer.play()
                }
            },
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) {
                    context.getString(R.string.chat_voice_pause)
                } else {
                    context.getString(R.string.chat_voice_play)
                },
                modifier = Modifier.size(29.dp),
                tint = playButtonIconColor
            )
        }

        var waveformWidthPx by remember { mutableStateOf(0f) }

        val seekToFraction: (Float) -> Unit = remember(player, durationMs) {
            val activePlayer = player
            if (activePlayer == null) {
                { _ -> }
            } else {
                { fraction ->
                    if (durationMs > 0) {
                        val clamped = fraction.coerceIn(0f, 1f)
                        val targetPosition = (durationMs * clamped).toLong()
                        activePlayer.seekTo(targetPosition)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .onSizeChanged { waveformWidthPx = it.width.toFloat() }
                .pointerInput(player, durationMs, waveformWidthPx) {
                    if (waveformWidthPx <= 0f) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        seekToFraction(down.position.x / waveformWidthPx)
                        drag(down.id) { change ->
                            seekToFraction(change.position.x / waveformWidthPx)
                            change.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            AudioWaveformCanvas(
                waveform = displayWaveform,
                progress = progressState,
                baseColor = waveBaseColor,
                activeColor = waveActiveColor,
                spacing = BAR_SPACING_DP.dp
            )
        }

        Text(
            text = stringResource(
                R.string.audio_message_progress_time,
                formatTimestamp(positionMs),
                formatTimestamp(durationMs)
            ),
            style = MaterialTheme.typography.labelMedium,
            color = timeTextColor,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 72.dp)
        )
    }
}

@Composable
@OptIn(UnstableApi::class)
private fun rememberLiveWaveform(enabled: Boolean, audioSessionId: Int): State<List<Float>?> {
    val state = remember { mutableStateOf<List<Float>?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(enabled, audioSessionId) {
        if (!enabled || audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            state.value = null
            onDispose { }
        } else {
            var visualizer: Visualizer? = null
            fun createVisualizerForSession(sessionId: Int): Visualizer? {
                val rawVisualizer = runCatching { Visualizer(sessionId) }.getOrElse { return null }
                return runCatching {
                    rawVisualizer.apply {
                        val captureRange = Visualizer.getCaptureSizeRange()
                        val minCapture = captureRange.getOrNull(0) ?: 0
                        val maxCapture = captureRange.getOrNull(1) ?: minCapture
                        val captureSize = when {
                            maxCapture > 0 -> maxCapture
                            minCapture > 0 -> minCapture
                            else -> 0
                        }
                        if (captureSize > 0) {
                            this.captureSize = captureSize
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                            measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
                            scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                        }
                        val desiredRate = Visualizer.getMaxCaptureRate() / 2
                        val captureRate = if (desiredRate > 0) desiredRate else Visualizer.getMaxCaptureRate()
                        setDataCaptureListener(
                            object : Visualizer.OnDataCaptureListener {
                                override fun onWaveFormDataCapture(
                                    visualizer: Visualizer?,
                                    waveform: ByteArray?,
                                    samplingRate: Int
                                ) {
                                    if (waveform == null) return
                                    val transformed = AudioWaveformRepository.transformVisualizerData(waveform)
                                    handler.post { state.value = transformed }
                                }

                                override fun onFftDataCapture(
                                    visualizer: Visualizer?,
                                    fft: ByteArray?,
                                    samplingRate: Int
                                ) = Unit
                            },
                            captureRate,
                            true,
                            false
                        )
                        this.enabled = true
                    }
                }.getOrElse {
                    rawVisualizer.release()
                    null
                }
            }

            visualizer = createVisualizerForSession(audioSessionId)
            if (visualizer == null && audioSessionId != 0) {
                visualizer = createVisualizerForSession(0)
            }
            if (visualizer == null) {
                state.value = null
            }
            onDispose {
                visualizer?.release()
            }
        }
    }

    return state
}

@Composable
private fun AudioWaveformCanvas(
    waveform: List<Float>,
    progress: Float,
    baseColor: Color,
    activeColor: Color,
    spacing: Dp
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.toPx() }
    val minBarWidthPx = with(density) { 1.dp.toPx() }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        if (waveform.isEmpty()) return@Canvas
        val maxBars = ((size.width + spacingPx) / (spacingPx + minBarWidthPx))
            .toInt()
            .coerceAtLeast(1)
        val adjustedWaveform = if (waveform.size > maxBars) {
            val step = waveform.size.toFloat() / maxBars.toFloat()
            List(maxBars) { index ->
                val sampleIndex = ((index + 0.5f) * step).toInt().coerceIn(0, waveform.size - 1)
                waveform[sampleIndex]
            }
        } else {
            waveform
        }
        val barCount = adjustedWaveform.size
        val totalSpacing = spacingPx * (barCount - 1)
        val availableWidth = size.width - totalSpacing
        if (availableWidth <= 0f) return@Canvas
        val barWidth = availableWidth / barCount
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val centerY = size.height / 2f
        val progressIndex = ((barCount - 1) * progress).roundToInt()
        adjustedWaveform.forEachIndexed { index, amplitude ->
            val clamped = amplitude.coerceIn(0f, 1f)
            val height = clamped * size.height
            val barHeight = height.coerceAtLeast(barWidth)
            val top = centerY - barHeight / 2f
            val left = index * (barWidth + spacingPx)
            val color = if (index <= progressIndex) activeColor else baseColor
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }
    }
}

private fun calculateProgress(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = (millis / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun placeholderWaveform(): List<Float> = List(DEFAULT_PLACEHOLDER_BARS) { index ->
    val center = (DEFAULT_PLACEHOLDER_BARS - 1) / 2f
    val distance = abs(index - center) / center
    (1f - distance).coerceIn(0.2f, 0.8f)
}

private fun List<Float>.hasMeaningfulAmplitude(): Boolean = any { it > 0f }
