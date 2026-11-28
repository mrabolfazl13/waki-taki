package com.securetalk.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// WakiTaki Design Language — "Command Center"
// Dark graphite + deep navy base, emerald accent (secure/online), electric
// lime (action/PTT), restrained glass and depth layering. Custom, premium,
// not a default chat app or dashboard look. Tokens drive every component.
// ============================================================================

object StColors {
    // Base layers (dark)
    val Background = Color(0xFF0A0E14)
    val Navy = Color(0xFF0F1B33)
    val Surface = Color(0xFF111721)
    val SurfaceHigh = Color(0xFF1A2230)
    val SurfaceOver = Color(0xFF223041)
    val Border = Color(0xFF253041)
    val BorderStrong = Color(0xFF34455E)

    // Accents
    val Emerald = Color(0xFF2EE6A8)     // secure / online / success
    val EmeraldDim = Color(0xFF14553F)
    val Lime = Color(0xFFC6F655)        // action / PTT / CTA
    val LimeDim = Color(0xFF5D7226)
    val Teal = Color(0xFF26C6DA)        // telemetry / diagnostics
    val TxRed = Color(0xFFFF5470)       // transmitting / error
    val Amber = Color(0xFFFFB300)       // warning / degraded / queued

    // Text
    val GreyText = Color(0xFF8CA0B3)
    val WhiteHigh = Color(0xFFEDF2F7)

    // Gradients (backgrounds, PTT rings)
    val NavyGradient = Brush.verticalGradient(listOf(Navy.copy(alpha = 0.85f), Background))
    val EmeraldGlow = Brush.radialGradient(listOf(Emerald.copy(alpha = 0.22f), Color.Transparent))
    val LimeGlow = Brush.radialGradient(listOf(Lime.copy(alpha = 0.28f), Color.Transparent))
    val RedGlow = Brush.radialGradient(listOf(TxRed.copy(alpha = 0.30f), Color.Transparent))

    // Light mode
    val LightBackground = Color(0xFFF4F7F5)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFE8EFEA)
    val LightBorder = Color(0xFFD3DED7)
    val LightText = Color(0xFF14201A)
    val LightGreyText = Color(0xFF5C6B62)
    val LightEmerald = Color(0xFF0B8A5F)
    val LightLime = Color(0xFF4A7A0A)
}

/** Motion tokens — durations and easing shared by all animated components. */
object StMotion {
    const val FastMs = 120
    const val NormalMs = 220
    const val SlowMs = 380
}

val StShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** Extended design tokens exposed to components beyond the Material scheme. */
data class StExtended(
    val emerald: Color,
    val lime: Color,
    val txRed: Color,
    val amber: Color,
    val teal: Color,
    val greyText: Color,
    val border: Color,
    val surfaceHigh: Color,
    val isDark: Boolean,
)

val LocalStExtended = staticCompositionLocalOf {
    StExtended(
        emerald = StColors.Emerald,
        lime = StColors.Lime,
        txRed = StColors.TxRed,
        amber = StColors.Amber,
        teal = StColors.Teal,
        greyText = StColors.GreyText,
        border = StColors.Border,
        surfaceHigh = StColors.SurfaceHigh,
        isDark = true,
    )
}

private val DarkColors = darkColorScheme(
    primary = StColors.Emerald,
    onPrimary = Color(0xFF06120D),
    secondary = StColors.Lime,
    onSecondary = Color(0xFF131805),
    tertiary = StColors.Teal,
    onTertiary = Color(0xFF062226),
    background = StColors.Background,
    onBackground = StColors.WhiteHigh,
    surface = StColors.Surface,
    onSurface = StColors.WhiteHigh,
    surfaceVariant = StColors.SurfaceHigh,
    onSurfaceVariant = StColors.GreyText,
    outline = StColors.Border,
    outlineVariant = StColors.BorderStrong,
    error = StColors.TxRed,
)

private val LightColors = lightColorScheme(
    primary = StColors.LightEmerald,
    onPrimary = Color(0xFFFFFFFF),
    secondary = StColors.LightLime,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = StColors.Teal,
    background = StColors.LightBackground,
    onBackground = StColors.LightText,
    surface = StColors.LightSurface,
    onSurface = StColors.LightText,
    surfaceVariant = StColors.LightSurfaceHigh,
    onSurfaceVariant = StColors.LightGreyText,
    outline = StColors.LightBorder,
    error = StColors.TxRed,
)

private val StTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 1.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
    ),
)

/**
 * SecureTalk theme. Full dark and light schemes; extended tokens are provided
 * through [LocalStExtended] so custom components never hardcode palette colors.
 */
@Composable
fun SecureTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val isDark = darkTheme
    val scheme = if (isDark) DarkColors else LightColors
    val ext = if (isDark) {
        StExtended(
            emerald = StColors.Emerald, lime = StColors.Lime, txRed = StColors.TxRed,
            amber = StColors.Amber, teal = StColors.Teal, greyText = StColors.GreyText,
            border = StColors.Border, surfaceHigh = StColors.SurfaceHigh, isDark = true,
        )
    } else {
        StExtended(
            emerald = StColors.LightEmerald, lime = StColors.LightLime, txRed = StColors.TxRed,
            amber = StColors.Amber, teal = StColors.Teal, greyText = StColors.LightGreyText,
            border = StColors.LightBorder, surfaceHigh = StColors.LightSurfaceHigh, isDark = false,
        )
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStExtended provides ext) {
        MaterialTheme(
            colorScheme = scheme,
            typography = StTypography,
            shapes = StShapes,
            content = content,
        )
    }
}
