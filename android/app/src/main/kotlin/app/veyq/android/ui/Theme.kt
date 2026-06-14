package app.veyq.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** veyq design tokens (dark) as a Material 3 color scheme — mirrors the web/iOS tokens. */
object Veyq {
    val Bg = Color(0xFF101418)
    val Surface = Color(0xFF171C22)
    val SurfaceMuted = Color(0xFF20262E)
    val Text = Color(0xFFEEF3F8)
    val Muted = Color(0xFF9AA7B5)
    val Line = Color(0xFF333D49)
    val Accent = Color(0xFF9B8CFF)
    val Danger = Color(0xFFF87171)
    val Radius = 12.dp
}

private val VeyqColorScheme = darkColorScheme(
    primary = Veyq.Accent,
    onPrimary = Color.White,
    background = Veyq.Bg,
    onBackground = Veyq.Text,
    surface = Veyq.Surface,
    onSurface = Veyq.Text,
    surfaceVariant = Veyq.SurfaceMuted,
    onSurfaceVariant = Veyq.Muted,
    outline = Veyq.Line,
    error = Veyq.Danger,
)

@Composable
fun VeyqTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VeyqColorScheme, content = content)
}
