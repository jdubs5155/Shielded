package com.aggregatorx.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * AggregatorX - DARK NEON GREEN THEME
 * Deep dark green-black backgrounds with darker neon green accents
 */
private val DarkNeonGreenColorScheme = darkColorScheme(
    primary = CyberCyan, // Darker neon green
    onPrimary = DarkBackground,
    primaryContainer = CyberCyanDark,
    onPrimaryContainer = TextPrimary,
    secondary = CyberBlue, // Bright mint green
    onSecondary = DarkBackground,
    secondaryContainer = CyberBlueDark,
    onSecondaryContainer = TextPrimary,
    tertiary = CyberPurple, // Lime highlight
    onTertiary = DarkBackground,
    tertiaryContainer = CyberPurple.copy(alpha = 0.3f),
    onTertiaryContainer = TextPrimary,
    error = AccentRed,
    onError = TextPrimary,
    errorContainer = AccentRed.copy(alpha = 0.2f),
    onErrorContainer = AccentRed,
    background = DarkBackground, // Near-black with green tint
    onBackground = TextPrimary,
    surface = DarkSurface, // Very dark green-black
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = TextTertiary,
    outlineVariant = DarkCardHover,
    inverseSurface = TextPrimary,
    inverseOnSurface = DarkBackground,
    inversePrimary = CyberCyanDark,
    surfaceTint = CyberCyan.copy(alpha = 0.12f) // Neon green tint
)

@Composable
fun AggregatorXTheme(
    darkTheme: Boolean = true, // Always dark - neon green theme
    content: @Composable () -> Unit
) {
    val colorScheme = DarkNeonGreenColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = DarkBackground.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
