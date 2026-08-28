package com.ecs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF16181D)
private val Accent = Color(0xFF2E6BE6)
private val AccentDark = Color(0xFF8AB0FF)
private val Warn = Color(0xFFD03A34)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Color(0xFF4A5568),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onSurface = Ink,
    error = Warn,
)

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF0A1633),
    secondary = Color(0xFFB6BECD),
    background = Color(0xFF101216),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFE6E8EC),
    error = Color(0xFFFF8A80),
)

@Composable
fun EcsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
