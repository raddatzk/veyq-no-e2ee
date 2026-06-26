package com.veyq.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The veyq design tokens on the Android surface — the exact light/dark role values from the shared parity
// doc (ui-design-parity.md), identical to Apple's `VeyqStyle` and the web CSS variables, so every surface
// shares one look. Material 3 is themed to these tokens (not stock purple); components read the precise
// role colors from [LocalVeyqColors] rather than Material slots so the rendered values equal the table.

/** The canonical veyq role colors for one appearance (light or dark). */
@Immutable
data class VeyqColors(
    val bg: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val accent: Color,
    val success: Color,
    val danger: Color,
)

val LightVeyqColors = VeyqColors(
    bg = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF4F6FB),
    text = Color(0xFF111827),
    muted = Color(0xFF667085),
    line = Color(0xFFDCE2EC),
    accent = Color(0xFF5B4DF5),
    success = Color(0xFF0F766E),
    danger = Color(0xFFB42318),
)

val DarkVeyqColors = VeyqColors(
    bg = Color(0xFF101418),
    surface = Color(0xFF171C22),
    surfaceMuted = Color(0xFF20262E),
    text = Color(0xFFEEF3F8),
    muted = Color(0xFF9AA7B5),
    line = Color(0xFF333D49),
    accent = Color(0xFF9B8CFF),
    success = Color(0xFF2DD4BF),
    danger = Color(0xFFF87171),
)

/** Non-Material design tokens (corner radii). */
object VeyqTokens {
    val radius = 12.dp
    val radiusSm = 10.dp
}

val LocalVeyqColors = staticCompositionLocalOf { LightVeyqColors }

/** Shorthand for the active veyq role colors inside composables. */
val veyqColors: VeyqColors
    @Composable @ReadOnlyComposable
    get() = LocalVeyqColors.current

/**
 * Applies the veyq look: provides [LocalVeyqColors] and themes Material 3 to the role tokens (accent →
 * primary, surface → surface, bg → background, danger → error) so any Material component drawn inside still
 * reads as veyq, not stock Material. Follows the system light/dark via [isSystemInDarkTheme].
 */
@Composable
fun VeyqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkVeyqColors else LightVeyqColors
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalVeyqColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
