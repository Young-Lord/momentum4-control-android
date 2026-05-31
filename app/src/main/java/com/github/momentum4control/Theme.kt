package com.github.momentum4control

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF1A1A2E),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF0F3460),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    tertiary = androidx.compose.ui.graphics.Color(0xFF533483),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB0BEC5),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1A1A2E),
    secondary = androidx.compose.ui.graphics.Color(0xFF90CAF9),
    onSecondary = androidx.compose.ui.graphics.Color(0xFF0D1B2A),
    tertiary = androidx.compose.ui.graphics.Color(0xFFCE93D8),
)

@Composable
fun Momentum4Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
