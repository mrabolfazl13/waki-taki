package com.securetalk.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * SecureTalk visual language: professional radio-inspired dark theme.
 * Near-black panels, amber accent (active channel / TX state), cyan for
 * connection telemetry, desaturated greys for chrome. No chat/feeds styling.
 */
object StColors {
    val Background = Color(0xFF0B0E11)
    val Surface = Color(0xFF12161B)
    val SurfaceHigh = Color(0xFF1A2027)
    val Border = Color(0xFF2A323C)

    val Amber = Color(0xFFFFB300)      // primary accent: PTT, active channel
    val AmberDim = Color(0xFF8A6A10)
    val TxRed = Color(0xFFFF5252)      // transmitting
    val Teal = Color(0xFF26C6DA)       // connection quality / diagnostics
    val Green = Color(0xFF66BB6A)      // online
    val GreyText = Color(0xFF9AA5B1)
    val WhiteHigh = Color(0xFFE8EDF2)
}

private val DarkColors = darkColorScheme(
    primary = StColors.Amber,
    onPrimary = Color(0xFF14100A),
    secondary = StColors.Teal,
    onSecondary = Color(0xFF062226),
    tertiary = StColors.TxRed,
    background = StColors.Background,
    onBackground = StColors.WhiteHigh,
    surface = StColors.Surface,
    onSurface = StColors.WhiteHigh,
    surfaceVariant = StColors.SurfaceHigh,
    onSurfaceVariant = StColors.GreyText,
    outline = StColors.Border,
    error = StColors.TxRed,
)

private val RadioTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = 0.2.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.6.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 1.0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
    ),
)

@Composable
fun SecureTalkTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    // Radio-inspired theme is dark-first; light system theme still renders readable chrome.
    MaterialTheme(
        colorScheme = if (darkTheme || isSystemInDarkTheme()) DarkColors else DarkColors,
        typography = RadioTypography,
        content = content,
    )
}
