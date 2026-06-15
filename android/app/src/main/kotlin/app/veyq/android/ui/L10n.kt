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
            "common.name" to "Name", "common.create" to "Erstellen", "common.cancel" to "Abbrechen",
            "common.rename" to "Umbenennen", "common.close" to "Schließen", "common.move" to "Verschieben", "cover.setAsCover" to "Als Cover",
            "move.title" to "Verschieben nach", "move.toRoot" to "Hauptebene",
            "home.createMemory" to "Memory erstellen", "home.createFolder" to "Ordner erstellen",
            "connect.title" to "Mit einer Instanz verbinden", "connect.sub" to "Gib die Adresse deiner veyq-Instanz ein.",
            "connect.url" to "Instanz-URL", "connect.connect" to "Verbinden",
            "connect.setupNeeded" to "Diese Instanz ist noch nicht eingerichtet — bitte im Web einrichten.",
            "connect.error" to "Konnte die Instanz nicht erreichen. Prüfe die Adresse.",
            "login.title" to "Anmelden", "login.secretHint" to "Aus deinem Emergency Kit.", "login.submit" to "Anmelden",
            "login.otherInstance" to "Andere Instanz",
            "login.error" to "Anmeldung fehlgeschlagen. Prüfe E-Mail, Passwort und Secret Key.",
            "login.remember" to "Auf diesem Gerät merken", "login.unlock" to "Mit Biometrie entsperren", "login.unlockFailed" to "Entsperren fehlgeschlagen.",
            "kit.import" to "Emergency Kit importieren", "kit.scan" to "QR-Code scannen", "kit.importFailed" to "Datei nicht erkannt. Bitte ein gültiges Emergency Kit wählen.",
            "settings.deviceUnlock" to "Geräte-Entsperrung", "settings.forgetDevice" to "Auf diesem Gerät vergessen",
            "home.memories" to "Memories", "home.folders" to "Ordner", "home.noMemories" to "Noch keine Memories hier.",
            "home.noFolders" to "Keine Ordner.", "home.search" to "Suche einfach überall…",
            "search.nothing" to "Nichts gefunden.", "search.memory" to "Memory", "search.folder" to "Ordner",
            "detail.media" to "Medien", "detail.loadingGallery" to "Lädt Galerie…", "detail.noMedia" to "Noch keine Medien.",
            "detail.gallery" to "Galerie", "detail.journal" to "Journal", "detail.map" to "Karte",
            "map.empty" to "Keine Medien mit Standort. Fotos mit GPS erscheinen hier auf der Karte.",
            "track.import" to "GPX importieren", "track.importing" to "Importiert…", "track.delete" to "Entfernen",
            "share.share" to "Teilen", "share.roleViewer" to "Nur Ansicht", "share.roleContributor" to "Mitwirken", "share.roleUploader" to "Nur Upload",
            "share.hint" to "Jeder mit dem Link kann diese Memory sehen. Mitwirkende können auch Fotos und Journal hinzufügen.",
            "groups.join" to "Gruppe beitreten", "accept.paste" to "Einladungslink einfügen", "accept.join" to "Beitreten",
            "accept.joining" to "Tritt bei…", "accept.failed" to "Beitritt fehlgeschlagen. Der Link ist evtl. abgelaufen.",
            "accept.as" to "als", "invite.member" to "Mitglied", "invite.owner" to "Eigentümer",
            "journal.empty" to "Noch kein Journal. Schreib die Geschichte zu dieser Memory.",
            "journal.edit" to "Bearbeiten", "journal.save" to "Speichern", "journal.saving" to "Speichert…",
            "journal.placeholder" to "Schreibe in Markdown… # Überschrift, **fett**, - Liste",
            "download.title" to "Herunterladen", "download.text" to "Originale dieser Memory als ZIP teilen.",
            "download.onlyThis" to "Nur diese Memory", "download.withChapters" to "Mit Kapiteln",
            "settings.title" to "Einstellungen", "settings.langAppearance" to "Sprache und Darstellung", "settings.appearance" to "Darstellung",
            "settings.system" to "System", "settings.light" to "Hell", "settings.dark" to "Dunkel", "settings.language" to "Sprache",
            "settings.account" to "Account", "settings.instance" to "Eingeloggte Instanz", "settings.disconnect" to "Trennen",
        ),
        "en" to mapOf(
            "common.email" to "Email", "common.password" to "Password", "common.secretKey" to "Secret Key",
            "common.name" to "Name", "common.create" to "Create", "common.cancel" to "Cancel",
            "common.rename" to "Rename", "common.close" to "Close", "common.move" to "Move", "cover.setAsCover" to "Set as cover",
            "move.title" to "Move to", "move.toRoot" to "Top level",
            "home.createMemory" to "Create memory", "home.createFolder" to "Create folder",
            "connect.title" to "Connect to an instance", "connect.sub" to "Enter your veyq instance address.",
            "connect.url" to "Instance URL", "connect.connect" to "Connect",
            "connect.setupNeeded" to "This instance is not set up yet — please set it up on the web.",
            "connect.error" to "Could not reach the instance. Check the address.",
            "login.title" to "Sign in", "login.secretHint" to "From your Emergency Kit.", "login.submit" to "Sign in",
            "login.otherInstance" to "Other instance",
            "login.error" to "Sign-in failed. Check email, password and Secret Key.",
            "login.remember" to "Remember on this device", "login.unlock" to "Unlock with biometrics", "login.unlockFailed" to "Unlock failed.",
            "kit.import" to "Import Emergency Kit", "kit.scan" to "Scan QR code", "kit.importFailed" to "File not recognised. Please choose a valid Emergency Kit.",
            "settings.deviceUnlock" to "Device unlock", "settings.forgetDevice" to "Forget on this device",
            "home.memories" to "Memories", "home.folders" to "Folders", "home.noMemories" to "No memories here yet.",
            "home.noFolders" to "No folders.", "home.search" to "Search everything…",
            "search.nothing" to "Nothing found.", "search.memory" to "Memory", "search.folder" to "Folder",
            "detail.media" to "media", "detail.loadingGallery" to "Loading gallery…", "detail.noMedia" to "No media yet.",
            "detail.gallery" to "Gallery", "detail.journal" to "Journal", "detail.map" to "Map",
            "map.empty" to "No media with a location. Photos with GPS show up here on the map.",
            "track.import" to "Import GPX", "track.importing" to "Importing…", "track.delete" to "Remove",
            "share.share" to "Share", "share.roleViewer" to "View only", "share.roleContributor" to "Contribute", "share.roleUploader" to "Upload only",
            "share.hint" to "Anyone with the link can view this memory. Contributors can also add photos and the journal.",
            "groups.join" to "Join group", "accept.paste" to "Paste invite link", "accept.join" to "Join",
            "accept.joining" to "Joining…", "accept.failed" to "Could not join. The link may have expired.",
            "accept.as" to "as", "invite.member" to "Member", "invite.owner" to "Owner",
            "journal.empty" to "No journal yet. Write the story behind this memory.",
            "journal.edit" to "Edit", "journal.save" to "Save", "journal.saving" to "Saving…",
            "journal.placeholder" to "Write in Markdown… # heading, **bold**, - list",
            "download.title" to "Download", "download.text" to "Share this memory's originals as a ZIP.",
            "download.onlyThis" to "Only this memory", "download.withChapters" to "With chapters",
            "settings.title" to "Settings", "settings.langAppearance" to "Language & Appearance", "settings.appearance" to "Appearance",
            "settings.system" to "System", "settings.light" to "Light", "settings.dark" to "Dark", "settings.language" to "Language",
            "settings.account" to "Account", "settings.instance" to "Connected instance", "settings.disconnect" to "Disconnect",
        ),
    )
}
