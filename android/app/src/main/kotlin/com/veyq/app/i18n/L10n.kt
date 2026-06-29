package com.veyq.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The reactive runtime-i18n + appearance holder (cr-0043), the Android twin of Apple's `L10n` (cr-0035/0037).
 * Holds the device-local **theme** and **language** preferences as Compose snapshot state, persisting each
 * change via [SettingsPrefs]. Because [t] reads the language [preference] (and [systemLanguage]) snapshot
 * state, every composable that calls it **re-renders the moment the preference changes — with no Activity
 * recreation**, so switching language never reloads the open Memory tree (the spec from Apple cr-0035). The
 * appearance toggle ([theme]) lives here too so the one composition root drives the whole palette.
 *
 * The string table + resolution are the pure [Localization] (unit-tested); this class only adds the reactive
 * active locale, exactly as Apple splits `Localization` (pure) from `L10n` (observable).
 */
class L10n(private val prefs: SettingsPrefs, systemLanguage: String?) {
    /** "system" | "light" | "dark". Persisted on change; drives the palette at the composition root. */
    var theme by mutableStateOf(prefs.theme)
        private set

    /** "system" | "en" | "de". Persisted on change; re-renders every string in place. */
    var preference by mutableStateOf(prefs.language)
        private set

    /** The OS language code ("de", "en", …), updated by the root when the configuration changes so a
     *  "system" language preference follows the OS live. */
    var systemLanguage by mutableStateOf(systemLanguage)

    fun applyTheme(value: String) { theme = value; prefs.theme = value }
    fun applyLanguage(value: String) { preference = value; prefs.language = value }

    /** The concrete locale the UI renders in — "system" resolves to the OS language. */
    val effective: String get() = Localization.resolve(preference, systemLanguage)

    /** Whether the palette should be dark, given the OS dark-mode flag and the theme preference. */
    fun darkTheme(systemDark: Boolean): Boolean = when (theme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }

    fun t(key: String): String = Localization.string(effective, key)

    /** Format a string carrying a single `%s` placeholder. */
    fun t(key: String, arg: String): String = String.format(t(key), arg)
}

/**
 * The active [L10n], provided once at the composition root. Static because the instance is stable; the
 * re-render on a preference change comes from the snapshot-state read inside [L10n.t], not from the local.
 */
val LocalL10n = staticCompositionLocalOf<L10n> { error("L10n not provided") }
