package com.kickercam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val KickerOrange = Color(0xFFFF7A1F)
val KickerGreen = Color(0xFF3DDC84)
val KickerRed = Color(0xFFFF4D4D)
val KickerSurface = Color(0xFF12181F)
val KickerSurfaceHigh = Color(0xFF1C242E)

private val scheme = darkColorScheme(
    primary = KickerOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF4A2508),
    onPrimaryContainer = Color(0xFFFFD9BC),
    secondary = KickerGreen,
    onSecondary = Color.Black,
    tertiary = Color(0xFF6FB4FF),
    background = Color(0xFF0B1016),
    onBackground = Color(0xFFE6EBF0),
    surface = KickerSurface,
    onSurface = Color(0xFFE6EBF0),
    surfaceVariant = KickerSurfaceHigh,
    onSurfaceVariant = Color(0xFFB6C0CC),
    error = KickerRed,
    outline = Color(0xFF3A4552),
)

private val typography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KickerCamTheme(content: @Composable () -> Unit) {
    // The app is a viewfinder; it is always dark.
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
