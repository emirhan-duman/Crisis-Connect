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
import androidx.compose.ui.unit.dp

@Composable
fun ChatTextureBackground(
    modifier: Modifier = Modifier
) {
    val baseColor = MaterialTheme.colorScheme.background
    val isDark = baseColor.luminance() < 0.5f
    val gridColor = if (isDark) {
        Color.White.copy(alpha = 0.038f)
    } else {
        Color.Black.copy(alpha = 0.028f)
    }
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
                val gridSpacing = 26.dp.toPx()
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
                    var x = 0f
                    while (x <= size.width) {
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f
                        )
                        x += gridSpacing
                    }
                    var y = 0f
                    while (y <= size.height) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        y += gridSpacing
                    }
                    drawRect(brush = centerLift)
                    drawRect(brush = vignette)
                }
            }
    )
}
