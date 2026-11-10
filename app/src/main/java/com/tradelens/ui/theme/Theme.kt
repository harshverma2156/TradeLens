package com.tradelens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

data class MarketColors(val gain: Color, val loss: Color, val neutral: Color)

val LocalMarketColors = staticCompositionLocalOf {
    MarketColors(Color(0xFF138A55), Color(0xFFC93A3A), Color(0xFFB57A06))
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0F6C74),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBEE),
    onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFFB57A06),
    background = Color(0xFFF2F4F7),
    onBackground = Color(0xFF172033),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172033),
    surfaceVariant = Color(0xFFE6EAEF),
    onSurfaceVariant = Color(0xFF5A6474),
    surfaceContainer = Color(0xFFFFFFFF),
    outline = Color(0xFFC6CDD6),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5CC0C8),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF0F4E54),
    onPrimaryContainer = Color(0xFFCDEBEE),
    secondary = Color(0xFFE8B04A),
    background = Color(0xFF0F151D),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF18202A),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF232C37),
    onSurfaceVariant = Color(0xFF9AA5B4),
    surfaceContainer = Color(0xFF18202A),
    outline = Color(0xFF364150),
)

/** Tabular figures so prices line up in columns. */
val Num = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun TradeLensTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val market = if (dark) MarketColors(Color(0xFF3CCB8A), Color(0xFFFF6B6B), Color(0xFFE8B04A))
    else MarketColors(Color(0xFF138A55), Color(0xFFC93A3A), Color(0xFFB57A06))
    CompositionLocalProvider(LocalMarketColors provides market) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
