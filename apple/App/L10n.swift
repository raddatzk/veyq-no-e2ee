import Foundation

/// Lightweight runtime i18n (cr-0035, ported from `legacy/iOS/Veyq/L10n.swift`). `t(key)` resolves against the
/// active locale; as an `ObservableObject`, changing `locale` re-renders its observers (the shell + settings)
/// **without recreating any `@StateObject`** — so switching language never reloads the tree (the legacy
/// `.id(locale)` root-rebuild would). English is the fallback; the default follows the system language.
@MainActor
final class L10n: ObservableObject {
    private static let key = "veyq.locale"

    @Published var locale: String {
        didSet { UserDefaults.standard.set(locale, forKey: Self.key) }
    }

    init() {
        if let stored = UserDefaults.standard.string(forKey: Self.key), stored == "de" || stored == "en" {
            locale = stored
        } else {
            locale = Locale.current.language.languageCode?.identifier == "de" ? "de" : "en"
        }
    }

    func t(_ key: String) -> String { L10nStrings[locale]?[key] ?? L10nStrings["en"]?[key] ?? key }

    /// Format a string carrying a single `%@` placeholder.
    func t(_ key: String, _ arg: String) -> String { String(format: t(key), arg) }
}

let L10nStrings: [String: [String: String]] = [
    "en": [
        "home.spaces": "Spaces", "home.personal": "Personal",
        "home.memories": "Memories", "home.folders": "Folders",
        "home.noMemories": "No Memories here yet.", "home.noFolders": "No folders here yet.",
        "home.createMemory": "New Memory", "home.createFolder": "New folder",
        "home.signOut": "Sign out",
        "common.name": "Name", "common.create": "Create", "common.cancel": "Cancel",
        "common.rename": "Rename", "common.delete": "Delete", "common.done": "Done",
        "memory.deleteConfirm": "Delete “%@”? This can’t be undone.",
        "settings.title": "Settings", "settings.langAppearance": "Language & appearance",
        "settings.appearance": "Appearance", "settings.system": "System", "settings.light": "Light",
        "settings.dark": "Dark", "settings.language": "Language", "settings.account": "Account",
        "settings.instance": "Instance",
    ],
    "de": [
        "home.spaces": "Bereiche", "home.personal": "Persönlich",
        "home.memories": "Erinnerungen", "home.folders": "Ordner",
        "home.noMemories": "Hier gibt es noch keine Erinnerungen.", "home.noFolders": "Hier gibt es noch keine Ordner.",
        "home.createMemory": "Neue Erinnerung", "home.createFolder": "Neuer Ordner",
        "home.signOut": "Abmelden",
        "common.name": "Name", "common.create": "Erstellen", "common.cancel": "Abbrechen",
        "common.rename": "Umbenennen", "common.delete": "Löschen", "common.done": "Fertig",
        "memory.deleteConfirm": "„%@“ löschen? Das lässt sich nicht rückgängig machen.",
        "settings.title": "Einstellungen", "settings.langAppearance": "Sprache & Darstellung",
        "settings.appearance": "Darstellung", "settings.system": "System", "settings.light": "Hell",
        "settings.dark": "Dunkel", "settings.language": "Sprache", "settings.account": "Konto",
        "settings.instance": "Instanz",
    ],
]
