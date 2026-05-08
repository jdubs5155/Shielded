package com.aggregatorx.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Custom Cyber Theme Colors ─────────────────────────────────────
val CyberCyan = Color(0xFF00F5FF)
val CyberBlue = Color(0xFF00BFFF)
val CyberPurple = Color(0xFF9D4EDD)
val DarkBackground = Color(0xFF0A0A0A)
val DarkSurface = Color(0xFF121212)
val DarkCard = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2A2A2A)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val TextTertiary = Color(0xFF888888)

val AccentGreen = Color(0xFF00E676)
val AccentRed = Color(0xFFFF5252)
val AccentYellow = Color(0xFFFFD600)
val AccentOrange = Color(0xFFFF9800)

private val DarkColorScheme = darkColorScheme(
    primary = CyberCyan,
    secondary = CyberBlue,
    tertiary = CyberPurple,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun AggregatorXTheme(
    darkTheme: Boolean = true,   // Force dark theme for cyber look
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,           // Make sure this exists or we'll add it later
        content = content
    )
}
