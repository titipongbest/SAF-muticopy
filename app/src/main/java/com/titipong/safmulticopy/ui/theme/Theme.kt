package com.titipong.safmulticopy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF2563EB)
val Success = Color(0xFF16A34A)
val Warn = Color(0xFFD97706)
val LogBackground = Color(0xFFF8FAFC)

// Always light, as requested — no dark scheme and no wallpaper colours.
private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBFF),
    onSecondaryContainer = Color(0xFF1E3A8A),
    tertiary = Color(0xFF0F766E),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F4F9),
    onSurfaceVariant = Color(0xFF5B6475),
    surfaceTint = Blue,
    outline = Color(0xFFCBD2DD),
    outlineVariant = Color(0xFFE5E9F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE9EDF3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFF3F5F9),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE6EAF1),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
