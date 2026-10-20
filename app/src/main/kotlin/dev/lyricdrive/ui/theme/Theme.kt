package dev.lyricdrive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun LyricDriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFF5E8A),
            background = Color(0xFF0E0820),
            surface = Color(0xFF0E0820),
        ),
        content = content,
    )
}
