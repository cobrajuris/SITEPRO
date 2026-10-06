package com.nero.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta tirada da logo: fundo petróleo, gato preto, olhos brancos.
val NeroNight = Color(0xFF0E1B1E)
val NeroTeal = Color(0xFF1C353A)
val NeroTealLight = Color(0xFF2A4A51)
val NeroSurface = Color(0xFF152A2E)
val NeroEye = Color(0xFFF4F1EA)
val NeroGold = Color(0xFFE6C77A)
val NeroMuted = Color(0xFF8FA6AA)
val NeroBlack = Color(0xFF050808)

private val NeroColors = darkColorScheme(
    primary = NeroEye,
    onPrimary = NeroBlack,
    secondary = NeroGold,
    onSecondary = NeroBlack,
    background = NeroNight,
    onBackground = NeroEye,
    surface = NeroSurface,
    onSurface = NeroEye,
    surfaceVariant = NeroTeal,
    onSurfaceVariant = NeroMuted,
    outline = NeroTealLight,
    error = Color(0xFFFF8A80),
)

@Composable
fun NeroTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeroColors, content = content)
}
