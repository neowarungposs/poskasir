package com.neowarungposs.poskasir.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF0E7A4F)
val GreenDark = Color(0xFF0E3B2A)
val GreenSoft = Color(0xFFE3F3EA)
val Amber = Color(0xFF7A4F00)
val AmberSoft = Color(0xFFFFF4DB)
val RedText = Color(0xFFB3261E)
val RedSoft = Color(0xFFFDE7E7)

private val scheme = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenSoft,
    onPrimaryContainer = GreenDark,
    secondary = GreenDark,
    background = Color(0xFFF5F6F2),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7EAE4),
    error = RedText,
)

@Composable
fun PosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
