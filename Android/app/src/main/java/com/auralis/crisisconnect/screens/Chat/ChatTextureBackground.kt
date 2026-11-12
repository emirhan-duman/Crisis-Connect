package com.auralis.crisisconnect.screens.Chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

@Composable
fun ChatTextureBackground(
    modifier: Modifier = Modifier
) {
    val baseColor = MaterialTheme.colorScheme.background
    val isDark = baseColor.luminance() < 0.5f
    val vignetteColor = if (isDark) {
        Color.Black.copy(alpha = 0.24f)
    } else {
        Color.Black.copy(alpha = 0.09f)
    }
    val centerLiftColor = if (isDark) {
        Color.White.copy(alpha = 0.03f)
    } else {
        Color.White.copy(alpha = 0.018f)
    }

    Box(
        modifier = modifier
            .background(baseColor)
            .drawWithCache {
                val centerLift = Brush.radialGradient(
                    colors = listOf(
                        centerLiftColor,
                        Color.Transparent,
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.42f),
                    radius = size.maxDimension * 0.64f
                )
                val vignette = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        vignetteColor
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.52f),
                    radius = size.maxDimension * 0.92f
                )
                onDrawBehind {
                    drawRect(brush = centerLift)
                    drawRect(brush = vignette)
                }
            }
    )
}
