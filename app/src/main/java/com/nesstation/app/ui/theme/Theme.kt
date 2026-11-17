package com.nesstation.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// -----------------------------------------------------------------------------
// NesStation palette — derived from a soft pixel sky + grass + frosted glass.
// Inspired by Pico-8 / Analogue Pocket UI.
// -----------------------------------------------------------------------------

private val Sky = Color(0xFFB7D7F2)
private val SkyDeep = Color(0xFF8DB8E0)
private val Grass = Color(0xFF7BB36A)
private val GrassDeep = Color(0xFF4F8C4A)
private val Cloud = Color(0xFFF5F7FB)
private val Accent = Color(0xFFE74C3C)     // NES red
private val Accent2 = Color(0xFF8E44AD)    // NES purple
private val Surface = Color(0xFFF1F4FA)
private val OnSurface = Color(0xFF1E2A3A)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD8D3),
    onPrimaryContainer = Color(0xFF410006),
    secondary = Accent2,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEADDFF),
    onSecondaryContainer = Color(0xFF21005D),
    background = Sky,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = Color(0xFFE0E5EE),
    onSurfaceVariant = Color(0xFF404A5C),
    outline = Color(0xFF6E7585),
    error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A9),
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF930009),
    onPrimaryContainer = Color(0xFFFFDAD4),
    secondary = Color(0xFFCFBCFF),
    onSecondary = Color(0xFF371E73),
    background = Color(0xFF0F1622),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF182030),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF222B3D),
    onSurfaceVariant = Color(0xFFC2C7D3),
    outline = Color(0xFF8C93A3),
    error = Color(0xFFFFB4AB)
)

private val Pixel = FontFamily.Monospace

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = Pixel, fontWeight = FontWeight.Bold, fontSize = 40.sp),
    titleLarge = TextStyle(fontFamily = Pixel, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = Pixel, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = Pixel, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Pixel, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = Pixel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Pixel, fontSize = 12.sp)
)

@Composable
fun NesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content
    )
}
