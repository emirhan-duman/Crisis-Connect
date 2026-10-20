package com.auralis.crisisconnect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkBase = Color(0xFF121212)
private val DarkSurfaceLow = Color(0xFF1A1A1A)
private val DarkSurface = Color(0xFF222222)
private val DarkSurfaceHigh = Color(0xFF2A2A2A)
private val DarkSurfaceHighest = Color(0xFF333333)
private val DarkSurfaceVariant = Color(0xFF303030)
private val DarkOnSurface = Color(0xFFF2F2F2)
private val DarkOnSurfaceVariant = Color(0xFFC4C4C4)
private val DarkOutline = Color(0xFF757575)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    onPrimary = Color(0xFF161616),
    primaryContainer = DarkSurfaceHigh,
    onPrimaryContainer = DarkOnSurface,
    inversePrimary = Color(0xFF555555),
    secondary = Color(0xFFC7C7C7),
    onSecondary = Color(0xFF181818),
    secondaryContainer = DarkSurface,
    onSecondaryContainer = DarkOnSurface,
    tertiary = Color(0xFFC7C7C7),
    onTertiary = Color(0xFF181818),
    tertiaryContainer = DarkSurfaceHigh,
    onTertiaryContainer = DarkOnSurface,
    background = DarkBase,
    onBackground = DarkOnSurface,
    surface = DarkBase,
    onSurface = DarkOnSurface,
    surfaceDim = DarkBase,
    surfaceBright = DarkSurfaceHigh,
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = DarkSurfaceLow,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHighest,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = Color.Transparent,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkBase,
    outline = DarkOutline,
    outlineVariant = DarkSurfaceVariant,
    scrim = Color(0xB3000000)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EDF9),
    onPrimaryContainer = Color(0xFF213D62),
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
        // Keep dark mode neutral even when the system uses wallpaper-derived colors.
        darkTheme -> DarkColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            dynamicLightColorScheme(context)
        }
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
