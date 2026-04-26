package com.x.rxsciapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanSignal,
    onPrimary = SteelNight,
    secondary = Mint,
    tertiary = AmberRelay,
    background = SteelNight,
    surface = Carbon,
    surfaceContainer = Graphite,
    surfaceContainerHigh = Alloy,
    surfaceContainerHighest = Graphite,
    onSurface = Mist,
    onSurfaceVariant = Silver,
    outline = Silver.copy(alpha = 0.55f),
    error = RedAlert,
    secondaryContainer = Graphite,
    tertiaryContainer = Color(0xFF3B2A10),
    onSecondaryContainer = Mist,
    onTertiaryContainer = Color(0xFFFFE1B2),
    primaryContainer = Color(0xFF0A4D63),
    onPrimaryContainer = Color(0xFFD8F6FF),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF005E7A),
    onPrimary = Color(0xFFF3FCFF),
    secondary = Color(0xFF006E5B),
    tertiary = Color(0xFF8B5A00),
    background = Color(0xFFF2F6F9),
    surface = Color(0xFFF8FBFD),
    surfaceContainer = Color(0xFFE8EEF3),
    surfaceContainerHigh = Color(0xFFDDE7EE),
    surfaceContainerHighest = Color(0xFFD0DCE5),
    onSurface = Color(0xFF0E141A),
    onSurfaceVariant = Color(0xFF4E5F6D),
    outline = Color(0xFF6A7E8E),
    error = Color(0xFFB3261E),
)

@Composable
fun RxsciappTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
