package com.auralis.crisisconnect.screens.Chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

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

    // One grid cell rendered once into a tiny tile; the repeated ImageShader then covers any size
    // with a single drawRect. The previous line-by-line loop re-recorded dozens of drawLine
    // commands on every repaint (notably each frame of the IME resize animation).
    val density = LocalDensity.current
    val gridTile = remember(gridColor, density) {
        val spacingPx = with(density) { 26.dp.toPx() }.roundToInt().coerceAtLeast(2)
        ImageBitmap(spacingPx, spacingPx).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint().apply {
                color = gridColor
                strokeWidth = 1f
            }
            canvas.drawLine(
                p1 = Offset(0.5f, 0f),
                p2 = Offset(0.5f, spacingPx.toFloat()),
                paint = paint
            )
            canvas.drawLine(
                p1 = Offset(0f, 0.5f),
                p2 = Offset(spacingPx.toFloat(), 0.5f),
                paint = paint
            )
        }
    }

    Box(
        modifier = modifier
            .background(baseColor)
            .drawWithCache {
                val gridBrush = ShaderBrush(
                    ImageShader(gridTile, TileMode.Repeated, TileMode.Repeated)
                )
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
                    drawRect(brush = gridBrush)
                    drawRect(brush = centerLift)
                    drawRect(brush = vignette)
                }
            }
    )
}
