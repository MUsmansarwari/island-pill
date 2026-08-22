package com.nahope.island.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF32D74B),
    onPrimary = Color(0xFF04150A),
    background = Color(0xFF0B0B0F),
    surface = Color(0xFF16161C),
    surfaceVariant = Color(0xFF1F1F27),
)

private val Light = lightColorScheme(
    primary = Color(0xFF15803D),
    background = Color(0xFFF7F7FA),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
