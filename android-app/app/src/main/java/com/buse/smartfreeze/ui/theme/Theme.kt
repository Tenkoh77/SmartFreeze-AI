package com.buse.smartfreeze.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Brand: deep teal "cool, calm device" palette. All text/background pairs below meet WCAG AA (>= 4.5:1).
private val Teal700 = Color(0xFF0E6E77)
private val Teal200 = Color(0xFF7FD6DC)
private val Slate900 = Color(0xFF0F1417)
private val Slate800 = Color(0xFF182025)
private val Slate100 = Color(0xFFEFF3F5)

private val LightColors = lightColorScheme(
    primary = Teal700, onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEF0), onPrimaryContainer = Color(0xFF002B2F),
    secondary = Color(0xFF4A6267), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7EA), onSecondaryContainer = Color(0xFF071E22),
    background = Color(0xFFF7F9FB), onBackground = Color(0xFF161D20),
    surface = Color(0xFFF7F9FB), onSurface = Color(0xFF161D20),
    surfaceVariant = Color(0xFFE3EAED), onSurfaceVariant = Color(0xFF3F494C),
    surfaceContainer = Slate100, surfaceContainerHigh = Color(0xFFE6ECEF),
    error = Color(0xFFB3261E), onError = Color.White,
    outline = Color(0xFF6F797C),
)

private val DarkColors = darkColorScheme(
    primary = Teal200, onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F56), onPrimaryContainer = Color(0xFFA6EEF3),
    secondary = Color(0xFFB1CBD0), onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF334B4F), onSecondaryContainer = Color(0xFFCDE7EC),
    background = Slate900, onBackground = Color(0xFFDEE3E5),
    surface = Slate900, onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F494C), onSurfaceVariant = Color(0xFFBFC8CB),
    surfaceContainer = Slate800, surfaceContainerHigh = Color(0xFF222B30),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    outline = Color(0xFF899396),
)

/** Semantic status colours used by cards, chips and charts (not part of Material's scheme). */
@Immutable
data class StatusColors(val ok: Color, val notice: Color, val warning: Color, val critical: Color, val chartGrid: Color)

private val LightStatus = StatusColors(Color(0xFF1B7F4B), Color(0xFF8A6100), Color(0xFFB4530A), Color(0xFFB3261E), Color(0x1F000000))
private val DarkStatus = StatusColors(Color(0xFF7DD8A4), Color(0xFFF2C14E), Color(0xFFFFB37A), Color(0xFFF2B8B5), Color(0x29FFFFFF))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.4.sp),
    )
}

val MetricValueStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun SmartFreezeTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}
