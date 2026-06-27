package com.veyq.app.ui

import android.content.Context

/**
 * The last instance + account this device used at a **passwordless** level (L2/L3) — so the app can
 * auto-restore on launch (it must know *which* account's level to resume; the device-security store is
 * keyed by email). Neither value is a secret (a URL + an email); the actual secrets (the key K, the refresh
 * token) live in the Keystore / encrypted prefs. Set when a passwordless level is chosen, cleared on
 * untrust. The Android twin of Apple's `LaunchPrefs`.
 */
class LaunchPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var instance: String?
        get() = prefs.getString(K_INSTANCE, null)
        private set(v) { prefs.edit().putString(K_INSTANCE, v).apply() }

    var account: String?
        get() = prefs.getString(K_ACCOUNT, null)
        private set(v) { prefs.edit().putString(K_ACCOUNT, v).apply() }

    fun remember(instance: String, account: String) {
        prefs.edit().putString(K_INSTANCE, instance).putString(K_ACCOUNT, account).apply()
    }

    fun clear() {
        prefs.edit().remove(K_INSTANCE).remove(K_ACCOUNT).apply()
    }

    private companion object {
        const val PREFS = "veyq.launch"
        const val K_INSTANCE = "lastInstance"
        const val K_ACCOUNT = "lastAccount"
    }
}
