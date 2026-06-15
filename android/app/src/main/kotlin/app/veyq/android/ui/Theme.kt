package app.veyq.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** veyq design tokens (light + dark) — mirrors the web/iOS tokens. */
data class VeyqColors(
    val bg: Color, val surface: Color, val surfaceMuted: Color, val text: Color,
    val muted: Color, val line: Color, val accent: Color, val danger: Color,
)

private val DarkColors = VeyqColors(
    bg = Color(0xFF101418), surface = Color(0xFF171C22), surfaceMuted = Color(0xFF20262E), text = Color(0xFFEEF3F8),
    muted = Color(0xFF9AA7B5), line = Color(0xFF333D49), accent = Color(0xFF9B8CFF), danger = Color(0xFFF87171),
)
private val LightColors = VeyqColors(
    bg = Color(0xFFF7F8FB), surface = Color(0xFFFFFFFF), surfaceMuted = Color(0xFFF4F6FB), text = Color(0xFF111827),
    muted = Color(0xFF667085), line = Color(0xFFDCE2EC), accent = Color(0xFF5B4DF5), danger = Color(0xFFB42318),
)

/**
 * The active palette is snapshot state, so every `Veyq.Bg`/`Veyq.Text`/… read in a composable recomposes
 * when the theme changes — no need to thread colors through the views. VeyqTheme sets it from the preference.
 */
object Veyq {
    var palette by mutableStateOf(DarkColors)
    val Bg get() = palette.bg
    val Surface get() = palette.surface
    val SurfaceMuted get() = palette.surfaceMuted
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Line get() = palette.line
    val Accent get() = palette.accent
    val Danger get() = palette.danger
    val Radius = 12.dp
}

@Composable
fun VeyqTheme(dark: Boolean, content: @Composable () -> Unit) {
    SideEffect { Veyq.palette = if (dark) DarkColors else LightColors }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) {
        darkColorScheme(primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceMuted, onSurfaceVariant = c.muted, outline = c.line, error = c.danger)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text, surfaceVariant = c.surfaceMuted, onSurfaceVariant = c.muted, outline = c.line, error = c.danger)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
