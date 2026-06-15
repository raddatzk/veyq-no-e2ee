package app.veyq.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lightweight runtime i18n. The locale is snapshot state, so every `L10n.t(key)` read in a composable
 * recomposes when the language changes. German is the source; English is provided.
 */
object L10n {
    var locale by mutableStateOf("de")

    fun t(key: String): String = strings[locale]?.get(key) ?: strings["de"]?.get(key) ?: key

    private val strings: Map<String, Map<String, String>> = mapOf(
        "de" to mapOf(
            "common.email" to "E-Mail", "common.password" to "Passwort", "common.secretKey" to "Secret Key",
            "connect.title" to "Mit einer Instanz verbinden", "connect.sub" to "Gib die Adresse deiner veyq-Instanz ein.",
            "connect.url" to "Instanz-URL", "connect.connect" to "Verbinden",
            "connect.setupNeeded" to "Diese Instanz ist noch nicht eingerichtet — bitte im Web einrichten.",
            "connect.error" to "Konnte die Instanz nicht erreichen. Prüfe die Adresse.",
            "login.title" to "Anmelden", "login.secretHint" to "Aus deinem Emergency Kit.", "login.submit" to "Anmelden",
            "login.otherInstance" to "Andere Instanz",
            "login.error" to "Anmeldung fehlgeschlagen. Prüfe E-Mail, Passwort und Secret Key.",
            "home.memories" to "Memories", "home.folders" to "Ordner", "home.noMemories" to "Noch keine Memories hier.",
            "home.noFolders" to "Keine Ordner.", "home.search" to "Suche einfach überall…",
            "search.nothing" to "Nichts gefunden.", "search.memory" to "Memory", "search.folder" to "Ordner",
            "detail.media" to "Medien", "detail.loadingGallery" to "Lädt Galerie…", "detail.noMedia" to "Noch keine Medien.",
            "download.title" to "Herunterladen", "download.text" to "Originale dieser Memory als ZIP teilen.",
            "download.onlyThis" to "Nur diese Memory", "download.withChapters" to "Mit Kapiteln",
            "settings.title" to "Einstellungen", "settings.langAppearance" to "Sprache und Darstellung", "settings.appearance" to "Darstellung",
            "settings.system" to "System", "settings.light" to "Hell", "settings.dark" to "Dunkel", "settings.language" to "Sprache",
            "settings.account" to "Account", "settings.instance" to "Eingeloggte Instanz", "settings.disconnect" to "Trennen",
        ),
        "en" to mapOf(
            "common.email" to "Email", "common.password" to "Password", "common.secretKey" to "Secret Key",
            "connect.title" to "Connect to an instance", "connect.sub" to "Enter your veyq instance address.",
            "connect.url" to "Instance URL", "connect.connect" to "Connect",
            "connect.setupNeeded" to "This instance is not set up yet — please set it up on the web.",
            "connect.error" to "Could not reach the instance. Check the address.",
            "login.title" to "Sign in", "login.secretHint" to "From your Emergency Kit.", "login.submit" to "Sign in",
            "login.otherInstance" to "Other instance",
            "login.error" to "Sign-in failed. Check email, password and Secret Key.",
            "home.memories" to "Memories", "home.folders" to "Folders", "home.noMemories" to "No memories here yet.",
            "home.noFolders" to "No folders.", "home.search" to "Search everything…",
            "search.nothing" to "Nothing found.", "search.memory" to "Memory", "search.folder" to "Folder",
            "detail.media" to "media", "detail.loadingGallery" to "Loading gallery…", "detail.noMedia" to "No media yet.",
            "download.title" to "Download", "download.text" to "Share this memory's originals as a ZIP.",
            "download.onlyThis" to "Only this memory", "download.withChapters" to "With chapters",
            "settings.title" to "Settings", "settings.langAppearance" to "Language & Appearance", "settings.appearance" to "Appearance",
            "settings.system" to "System", "settings.light" to "Light", "settings.dark" to "Dark", "settings.language" to "Language",
            "settings.account" to "Account", "settings.instance" to "Connected instance", "settings.disconnect" to "Disconnect",
        ),
    )
}
