package com.auralis.crisisconnect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkBase = Color(0xFF121416)
private val DarkSurfaceLow = Color(0xFF1A1D21)
private val DarkSurface = Color(0xFF20252B)
private val DarkSurfaceHigh = Color(0xFF272D35)
private val DarkSurfaceHighest = Color(0xFF303741)
private val DarkSurfaceVariant = Color(0xFF2A3038)
private val DarkOnSurfaceVariant = Color(0xFFC0C8D4)
private val DarkOutline = Color(0xFF4B5563)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC6CDD8),
    onPrimary = Color(0xFF101216),
    primaryContainer = DarkSurfaceHigh,
    onPrimaryContainer = Gray100,
    inversePrimary = Color(0xFF8D98A8),
    secondary = Gray300,
    onSecondary = Color(0xFF101216),
    secondaryContainer = DarkSurface,
    onSecondaryContainer = Gray100,
    tertiary = Gray300,
    onTertiary = Color(0xFF101216),
    tertiaryContainer = DarkSurfaceHigh,
    onTertiaryContainer = Gray100,
    background = DarkBase,
    onBackground = Gray100,
    surface = DarkBase,
    onSurface = Gray100,
    surfaceDim = DarkBase,
    surfaceBright = DarkSurfaceHigh,
    surfaceContainerLowest = Color(0xFF0D0F11),
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = Color.Transparent,
    inverseSurface = Gray100,
    inverseOnSurface = Color(0xFF14171B),
    outline = DarkOutline,
    outlineVariant = DarkSurfaceVariant,
    scrim = Color(0xB3000000)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Gray200,
    onPrimaryContainer = MidnightBlue,
    inversePrimary = PrimaryBlueLight,
    secondary = Gray700,
    onSecondary = Color.White,
    secondaryContainer = Gray200,
    onSecondaryContainer = MidnightBlue,
    tertiary = Gray700,
    onTertiary = Color.White,
    tertiaryContainer = Gray100,
    onTertiaryContainer = MidnightBlue,
    background = Gray050,
    onBackground = MidnightBlue,
    surface = Color.White,
    onSurface = MidnightBlue,
    surfaceDim = Gray100,
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Gray050,
    surfaceContainer = Gray100,
    surfaceContainerHigh = Gray200,
    surfaceContainerHighest = Gray200,
    surfaceVariant = Gray100,
    onSurfaceVariant = Gray700,
    surfaceTint = Color.Transparent,
    inverseSurface = Gray800,
    inverseOnSurface = Gray100,
    outline = Gray300,
    outlineVariant = Gray200,
    scrim = Color(0x66000000)
)

@Composable
fun DisasterCommunicationSystemTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
