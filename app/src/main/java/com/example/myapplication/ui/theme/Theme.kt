package com.example.bleaudioviz.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = VizCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = VizCyanContainer,
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = VizTeal,
    tertiary = VizAmber,
    background = VizSurface,
    onBackground = VizOnSurface,
    surface = VizSurface,
    onSurface = VizOnSurface,
    surfaceVariant = VizSurfaceVariant,
    onSurfaceVariant = Color(0xFFBAC6D4)
)

@Composable
fun BleVisualizerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
