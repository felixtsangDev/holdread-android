package app.holdread.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The prototype's palette: warm paper, ink, one orange accent. */
@Immutable
data class ReaderColors(
    val bg: Color,
    val paper: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val band: Color,
    val read: Color,
    val line: Color,
    val ok: Color,
    val bad: Color,
)

val LightReaderColors = ReaderColors(
    bg = Color(0xFFF6F1E7), paper = Color(0xFFFBF8F1), ink = Color(0xFF2A2620), muted = Color(0xFF8A8172),
    faint = Color(0xFFC9BFAE), accent = Color(0xFFD9622B), band = Color(0x14D9622B), read = Color(0xFFA79D8C),
    line = Color(0xFFE4DCCD), ok = Color(0xFF2F8F5B), bad = Color(0xFFC0392B),
)

val DarkReaderColors = ReaderColors(
    bg = Color(0xFF16140F), paper = Color(0xFF1D1A15), ink = Color(0xFFE9E2D4), muted = Color(0xFF8F8676),
    faint = Color(0xFF4A443A), accent = Color(0xFFF08A4B), band = Color(0x17F08A4B), read = Color(0xFF6F675A),
    line = Color(0xFF2C2821), ok = Color(0xFF4CB57E), bad = Color(0xFFE0604F),
)

val LocalReaderColors = staticCompositionLocalOf { LightReaderColors }

@Composable
fun HoldReadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkReaderColors else LightReaderColors
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, background = c.bg, surface = c.paper, onBackground = c.ink, onSurface = c.ink)
    } else {
        lightColorScheme(primary = c.accent, background = c.bg, surface = c.paper, onBackground = c.ink, onSurface = c.ink)
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalReaderColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
