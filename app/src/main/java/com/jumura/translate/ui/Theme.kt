package com.jumura.translate.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette « mosquée moderne » : émeraude + or sur vert-nuit profond.
val JumuraBg = Color(0xFF06110D)
val JumuraSurface = Color(0xFF0C1A14)
val JumuraSurfaceHigh = Color(0xFF122820)
val JumuraEmerald = Color(0xFF34D399)
val JumuraEmeraldDeep = Color(0xFF10B981)
val JumuraGold = Color(0xFFE6C36B)
val JumuraGoldSoft = Color(0xFFF3E4B0)
val JumuraText = Color(0xFFEAF3EE)
val JumuraMuted = Color(0xFF8FB0A0)
val JumuraArabic = Color(0xFFF0E4C8)
val JumuraRed = Color(0xFFF87171)

private val JumuraColors = darkColorScheme(
    primary = JumuraEmerald,
    onPrimary = Color(0xFF04140D),
    secondary = JumuraGold,
    onSecondary = Color(0xFF1A1405),
    tertiary = JumuraGold,
    background = JumuraBg,
    onBackground = JumuraText,
    surface = JumuraSurface,
    onSurface = JumuraText,
    surfaceVariant = JumuraSurfaceHigh,
    onSurfaceVariant = JumuraMuted,
    error = JumuraRed
)

@Composable
fun JumuraTheme(content: @Composable () -> Unit) {
    // Identité visuelle sombre et sobre, toujours.
    MaterialTheme(
        colorScheme = JumuraColors,
        typography = Typography(),
        content = content
    )
}
