package com.veyq.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime-i18n string resolution (cr-0043) — per-locale lookup with an English fallback, an unknown key
 * resolving to itself, and the preference→locale resolver. The Android twin of Apple's `LocalizationTests`;
 * backs `uc.settings.appearance-language` on Android. Pure JVM (no Android SDK), runs in `testDebugUnitTest`.
 */
class LocalizationTest {

    @Test
    fun resolvesPerLocale() {
        assertEquals("Erinnerungen", Localization.string("de", "home.memories"))
        assertEquals("Memories", Localization.string("en", "home.memories"))
        assertEquals("Darstellung", Localization.string("de", "settings.appearance"))
    }

    @Test
    fun unknownLocaleFallsBackToEnglish() {
        assertEquals("Folders", Localization.string("fr", "home.folders"))
    }

    @Test
    fun unknownKeyResolvesToItself() {
        assertEquals("nope.missing", Localization.string("en", "nope.missing"))
    }

    @Test
    fun deAndEnCoverTheSameKeys() {
        val en = Localization.strings.getValue("en").keys
        val de = Localization.strings.getValue("de").keys
        assertEquals("every English key must have a German translation (and vice versa)", en, de)
    }

    @Test
    fun everyValueIsNonBlank() {
        Localization.strings.forEach { (locale, table) ->
            table.forEach { (key, value) ->
                assertTrue("$locale/$key is blank", value.isNotBlank())
            }
        }
    }

    @Test
    fun resolvesPreference() {
        // a concrete choice wins regardless of the system language
        assertEquals("en", Localization.resolve("en", "de"))
        assertEquals("de", Localization.resolve("de", "en"))
        // "system" follows the OS: German → de, anything else (incl. null) → en
        assertEquals("de", Localization.resolve("system", "de"))
        assertEquals("en", Localization.resolve("system", "fr"))
        assertEquals("en", Localization.resolve("system", null))
    }
}
