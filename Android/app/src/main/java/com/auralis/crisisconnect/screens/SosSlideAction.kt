package com.auralis.crisisconnect.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Colors for [SosSlideAction], supplied by each screen's palette. */
data class SosSlideStyle(
    val track: Color,
    val trackBorder: Color,
    val progressStart: Color,
    val progressEnd: Color,
    val label: Color,
    val knob: Color,
    val knobIcon: Color
)

/**
 * The one slide-to-confirm control of the SOS flow, shared by the countdown screen (cancel before
 * start) and the active screen (stop the live broadcast). A deliberate full drag is required so a
 * pocket touch can't end an emergency, while accessibility services get an explicit confirm
 * action and progress semantics instead of the gesture.
 */
@Composable
fun SosSlideAction(
    label: String,
    confirmActionLabel: String,
    style: SosSlideStyle,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 76.dp,
    idleStateDescription: String? = null,
    readyStateDescription: String? = null
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val confirmThreshold = 0.92f
    val trackPadding = 6.dp
    val knobSize = (height - trackPadding * 2).coerceAtLeast(44.dp)
    val trackShape = RoundedCornerShape(999.dp)
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var hasTriggeredStart by remember { mutableStateOf(false) }
    var hasTriggeredThreshold by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(trackShape)
            .background(style.track)
            .border(width = 1.dp, color = style.trackBorder, shape = trackShape)
            .padding(trackPadding)
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val knobSizePx = with(density) { knobSize.toPx() }
        val maxOffsetPx = (trackWidthPx - knobSizePx).coerceAtLeast(0f)
        val progress = sliderPosition.coerceIn(0f, 1f)
        val knobOffsetPx = progress * maxOffsetPx
        val isArmed = progress >= confirmThreshold

        LaunchedEffect(progress) {
            if (progress > 0.03f && !hasTriggeredStart) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                hasTriggeredStart = true
            }
            if (progress >= confirmThreshold && !hasTriggeredThreshold) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                hasTriggeredThreshold = true
            }
            if (progress < 0.03f) hasTriggeredStart = false
            if (progress < confirmThreshold) hasTriggeredThreshold = false
        }

        val draggableState = rememberDraggableState { delta ->
            val currentPx = progress * maxOffsetPx
            val nextPx = (currentPx + delta).coerceIn(0f, maxOffsetPx)
            sliderPosition = if (maxOffsetPx == 0f) 0f else nextPx / maxOffsetPx
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics(mergeDescendants = true) {
                    contentDescription = label
                    val state = if (isArmed) readyStateDescription else idleStateDescription
                    if (state != null) {
                        stateDescription = state
                    }
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                    customActions = listOf(
                        CustomAccessibilityAction(confirmActionLabel) {
                            onConfirmed()
                            sliderPosition = 0f
                            true
                        }
                    )
                    setProgress { target ->
                        if (target >= confirmThreshold) {
                            onConfirmed()
                        }
                        sliderPosition = 0f
                        true
                    }
                }
        ) {
            if (progress > 0.01f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(with(density) { (knobOffsetPx + knobSizePx * 0.6f).toDp() })
                        .clip(trackShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(style.progressStart, style.progressEnd)
                            )
                        )
                        .alpha(0.9f)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = knobSize),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.2.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = style.label.copy(
                        alpha = (0.92f - progress * 0.6f).coerceIn(0.2f, 1f)
                    ),
                    maxLines = 1
                )
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(knobOffsetPx.toInt(), 0) }
                    .size(knobSize)
                    .shadow(elevation = if (isArmed) 10.dp else 6.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(style.knob)
                    .border(
                        width = 1.dp,
                        color = if (isArmed) style.progressEnd else style.trackBorder,
                        shape = CircleShape
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
                                sliderPosition = 0f
                                onConfirmed()
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
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = style.knobIcon,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
