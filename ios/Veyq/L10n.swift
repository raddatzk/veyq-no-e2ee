import Foundation

/// Lightweight runtime i18n. `T(key)` reads the stored locale; the whole view tree is rebuilt on change via
/// `.id(locale)` at the root, so every `T(...)` re-resolves. German is the source; English is provided.
func T(_ key: String) -> String {
    let locale = UserDefaults.standard.string(forKey: "veyq.locale") ?? "de"
    return L10nStrings[locale]?[key] ?? L10nStrings["de"]?[key] ?? key
}

let L10nStrings: [String: [String: String]] = [
    "de": [
        "common.email": "E-Mail", "common.password": "Passwort", "common.secretKey": "Secret Key",
        "common.cancel": "Abbrechen", "common.done": "Fertig", "common.name": "Name", "common.create": "Erstellen",
        "common.rename": "Umbenennen", "common.close": "Schließen", "common.move": "Verschieben", "cover.setAsCover": "Als Cover",
        "move.title": "Verschieben nach", "move.toRoot": "Hauptebene",
        "home.createMemory": "Memory erstellen", "home.createFolder": "Ordner erstellen",
        "connect.title": "Mit einer Instanz verbinden", "connect.sub": "Gib die Adresse deiner veyq-Instanz ein.",
        "connect.url": "Instanz-URL", "connect.connect": "Verbinden",
        "connect.setupNeeded": "Diese Instanz ist noch nicht eingerichtet — bitte im Web einrichten.",
        "connect.error": "Konnte die Instanz nicht erreichen. Prüfe die Adresse.",
        "login.title": "Anmelden", "login.secretHint": "Aus deinem Emergency Kit.", "login.submit": "Anmelden",
        "login.otherInstance": "Andere Instanz",
        "login.error": "Anmeldung fehlgeschlagen. Prüfe E-Mail, Passwort und Secret Key.",
        "home.memories": "Memories", "home.folders": "Ordner", "home.noMemories": "Noch keine Memories hier.",
        "home.noFolders": "Keine Ordner.", "home.search": "Suchen",
        "search.nothing": "Nichts gefunden.", "search.memory": "Memory", "search.folder": "Ordner",
        "detail.media": "Medien", "detail.loadingGallery": "Lädt Galerie…", "detail.noMedia": "Noch keine Medien.",
        "detail.upload": "Hochladen", "detail.uploading": "Lädt…",
        "download.title": "Herunterladen", "download.onlyThis": "Nur diese Memory", "download.withChapters": "Mit Kapiteln",
        "settings.title": "Einstellungen", "settings.langAppearance": "Sprache und Darstellung", "settings.appearance": "Darstellung",
        "settings.system": "System", "settings.light": "Hell", "settings.dark": "Dunkel", "settings.language": "Sprache",
        "settings.account": "Account", "settings.instance": "Eingeloggte Instanz", "settings.disconnect": "Trennen",
    ],
    "en": [
        "common.email": "Email", "common.password": "Password", "common.secretKey": "Secret Key",
        "common.cancel": "Cancel", "common.done": "Done", "common.name": "Name", "common.create": "Create",
        "common.rename": "Rename", "common.close": "Close", "common.move": "Move", "cover.setAsCover": "Set as cover",
        "move.title": "Move to", "move.toRoot": "Top level",
        "home.createMemory": "Create memory", "home.createFolder": "Create folder",
        "connect.title": "Connect to an instance", "connect.sub": "Enter your veyq instance address.",
        "connect.url": "Instance URL", "connect.connect": "Connect",
        "connect.setupNeeded": "This instance is not set up yet — please set it up on the web.",
        "connect.error": "Could not reach the instance. Check the address.",
        "login.title": "Sign in", "login.secretHint": "From your Emergency Kit.", "login.submit": "Sign in",
        "login.otherInstance": "Other instance",
        "login.error": "Sign-in failed. Check email, password and Secret Key.",
        "home.memories": "Memories", "home.folders": "Folders", "home.noMemories": "No memories here yet.",
        "home.noFolders": "No folders.", "home.search": "Search",
        "search.nothing": "Nothing found.", "search.memory": "Memory", "search.folder": "Folder",
        "detail.media": "media", "detail.loadingGallery": "Loading gallery…", "detail.noMedia": "No media yet.",
        "detail.upload": "Upload", "detail.uploading": "…",
        "download.title": "Download", "download.onlyThis": "Only this memory", "download.withChapters": "With chapters",
        "settings.title": "Settings", "settings.langAppearance": "Language & Appearance", "settings.appearance": "Appearance",
        "settings.system": "System", "settings.light": "Light", "settings.dark": "Dark", "settings.language": "Language",
        "settings.account": "Account", "settings.instance": "Connected instance", "settings.disconnect": "Disconnect",
    ],
]
