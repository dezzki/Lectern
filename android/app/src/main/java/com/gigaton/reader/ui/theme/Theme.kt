package com.gigaton.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF10B981),
    secondary = Color(0xFF38BDF8),
    background = Color(0xFF0B1220),
    surface = Color(0xFF111827),
    onBackground = Color(0xFFE5E7EB),
    onSurface = Color(0xFFE5E7EB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF047857),
    secondary = Color(0xFF0369A1),
)

@Composable
fun GigatonTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}