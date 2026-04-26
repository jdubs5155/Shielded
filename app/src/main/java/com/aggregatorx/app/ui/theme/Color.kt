package com.aggregatorx.app.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// AggregatorX - DARK NEON GREEN THEME
// Deep blacks with darker neon green accents
// ==========================================

// Primary Colors - DARKER NEON GREEN palette
val CyberCyan = Color(0xFF00C853)        // Darker neon green (primary accent)
val CyberCyanDark = Color(0xFF007B2E)    // Deep dark green
val CyberBlue = Color(0xFF5EFC82)        // Bright mint green (secondary)
val CyberBlueDark = Color(0xFF00431A)    // Very dark green
val CyberPurple = Color(0xFFCCFF90)      // Lime highlight accent
val CyberPink = Color(0xFF00C853)        // Same as primary

// Background Colors - PURE BLACKS & DEEP DARK
val DarkBackground = Color(0xFF050F07)   // Near-black with faint green tint
val DarkSurface = Color(0xFF0A1A0D)      // Very dark green-black
val DarkSurfaceVariant = Color(0xFF122016) // Dark green charcoal
val DarkCard = Color(0xFF172B1C)         // Card background - dark green-gray
val DarkCardHover = Color(0xFF1F3825)    // Hover state

// Accent Colors - GREEN SPECTRUM
val AccentGreen = Color(0xFF00C853)      // Primary neon green
val AccentOrange = Color(0xFFFFAB00)     // Amber accent (contrast)
val AccentRed = Color(0xFFFF1744)        // Red for errors / warnings
val AccentYellow = Color(0xFFEEFF41)     // Electric yellow-green

// Text Colors - HIGH CONTRAST for dark theme
val TextPrimary = Color(0xFFF1FFF3)      // Slightly green-tinted white
val TextSecondary = Color(0xFFB2DFBC)    // Light mint gray
val TextTertiary = Color(0xFF5E8B68)     // Medium green-gray
val TextMuted = Color(0xFF3D5C44)        // Muted dark green

// Gradient Colors - GREEN gradients
val GradientStart = Color(0xFF00C853)    // Darker neon green
val GradientMid = Color(0xFF007B2E)      // Deep green
val GradientEnd = Color(0xFF5EFC82)      // Bright mint

// Status Colors - Clear indicators
val StatusSuccess = Color(0xFF00C853)    // Green for success
val StatusWarning = Color(0xFFFFAB00)    // Amber for warning
val StatusError = Color(0xFFFF1744)      // Red for error
val StatusInfo = Color(0xFF5EFC82)       // Bright mint for info

// Provider Category Colors - GREEN THEME variations
val CategoryStreaming = Color(0xFF00C853)  // Neon green
val CategoryTorrent = Color(0xFF5EFC82)   // Bright mint
val CategoryNews = Color(0xFFCCFF90)      // Lime accent
val CategoryMedia = Color(0xFF69F0AE)     // Medium mint
val CategoryGeneral = Color(0xFF00C853)   // Neon green
val CategoryAPI = Color(0xFFFFAB00)       // Amber gold

// AI/Smart Feature Colors - GLOWING GREEN
val AIAccent = Color(0xFF00C853)          // AI neon green glow
val SmartFeature = Color(0xFF5EFC82)      // Smart feature bright mint

// Download/Media Colors - GREEN THEMED
val DownloadActive = Color(0xFF00C853)    // Active download - neon green
val DownloadComplete = Color(0xFF5EFC82)  // Complete - bright mint
val DownloadPaused = Color(0xFFFFAB00)    // Paused - amber
val DownloadError = Color(0xFFFF1744)     // Error - red

// Score Colors - Progressive green focus
fun getScoreColor(score: Float): Color {
    return when {
        score >= 80f -> Color(0xFF00C853)  // High scores - neon green
        score >= 60f -> Color(0xFF69F0AE)  // Good - mint
        score >= 40f -> AccentOrange       // Medium - amber
        score >= 20f -> AccentYellow       // Low - electric yellow
        else -> AccentRed                  // Very low - red
    }
}

// Security Score Colors
fun getSecurityColor(score: Float): Color {
    return when {
        score >= 80f -> Color(0xFF00C853)
        score >= 60f -> Color(0xFF69F0AE)
        score >= 40f -> AccentYellow
        score >= 20f -> AccentOrange
        else -> AccentRed
    }
}

// Quality Badge Colors
fun getQualityColor(quality: String): Color {
    return when {
        quality.contains("4k", ignoreCase = true) || quality.contains("2160") -> Color(0xFF5EFC82)
        quality.contains("1080") || quality.contains("full hd", ignoreCase = true) -> Color(0xFF00C853)
        quality.contains("720") -> Color(0xFF69F0AE)
        quality.contains("480") -> AccentOrange
        else -> TextTertiary
    }
}
