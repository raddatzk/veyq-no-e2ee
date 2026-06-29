package com.veyq.app.i18n

import android.content.Context

/**
 * Device-local, non-secret app settings — the **theme** (system/light/dark) and **language** (system/en/de)
 * preference. Backed by a small `SharedPreferences` file, the established non-secret convention (the twin of
 * [com.veyq.app.ui.LaunchPrefs] / `"veyq.launch"`). No secret ever lands here — both values are plain UI
 * preferences, so the operator-blind guarantee is untouched.
 */
class SettingsPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "system" | "light" | "dark" — default "system". */
    var theme: String
        get() = prefs.getString(K_THEME, null)?.takeIf { it in THEMES } ?: "system"
        set(v) { prefs.edit().putString(K_THEME, v).apply() }

    /** "system" | "en" | "de" — default "system". */
    var language: String
        get() = prefs.getString(K_LANGUAGE, null)?.takeIf { it in LANGS } ?: "system"
        set(v) { prefs.edit().putString(K_LANGUAGE, v).apply() }

    private companion object {
        const val PREFS = "veyq.settings"
        const val K_THEME = "theme"
        const val K_LANGUAGE = "language"
        val THEMES = setOf("system", "light", "dark")
        val LANGS = setOf("system", "en", "de")
    }
}
