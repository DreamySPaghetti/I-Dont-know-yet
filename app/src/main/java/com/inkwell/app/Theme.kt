package com.inkwell.app

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

data class Palette(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val line: Color,
    val dark: Boolean
)

val LightP = Palette(Color(0xFFFBFAF7), Color(0xFFF1EEE7), Color(0xFF2B2A27), Color(0xFF8C887F), Color(0xFFD08A00), Color(0xFFE4E0D7), false)
val SepiaP = Palette(Color(0xFFF5EEDC), Color(0xFFECE1C8), Color(0xFF4B3E2C), Color(0xFF8F7E63), Color(0xFFB46F08), Color(0xFFE0D3B5), false)
val DarkP = Palette(Color(0xFF1B1B1D), Color(0xFF27272A), Color(0xFFE7E5E0), Color(0xFF8E8C88), Color(0xFFF0B13C), Color(0xFF333336), true)

val LocalPalette = staticCompositionLocalOf { LightP }

fun editorFont(f: String) = when (f) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.Serif
}

fun ringColor(p: Palette, state: Int) = when (state) {
    1 -> Color(0xFF3FA66B)
    2 -> Color(0xFFD9534F)
    else -> p.accent
}

@Composable
fun AppTheme(settings: Settings, content: @Composable () -> Unit) {
    val sys = isSystemInDarkTheme()
    val p = when (settings.theme) {
        "light" -> LightP
        "sepia" -> SepiaP
        "dark" -> DarkP
        else -> if (sys) DarkP else LightP
    }
    val act = LocalContext.current as? ComponentActivity
    LaunchedEffect(p.dark) {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (p.dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        act?.enableEdgeToEdge(style, style)
    }
    val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = p.accent,
        onPrimary = if (p.dark) Color.Black else Color.White,
        primaryContainer = p.surface,
        onPrimaryContainer = p.text,
        secondaryContainer = p.accent.copy(alpha = 0.22f),
        onSecondaryContainer = p.text,
        background = p.bg,
        onBackground = p.text,
        surface = p.bg,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.muted,
        outline = p.line,
        outlineVariant = p.line,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = p.bg,
        surfaceContainerLow = p.bg,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surface
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme) {
            Surface(Modifier.fillMaxSize(), color = p.bg, content = content)
        }
    }
}
