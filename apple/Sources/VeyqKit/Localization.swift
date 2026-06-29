import Foundation

/// Pure string resolution for the runtime i18n (cr-0035/0036). The table + lookup live here (testable,
/// no UI); the app's `L10n` ObservableObject wraps `string(_:_:)` with the reactive active locale. German
/// source + English fallback; an unknown key resolves to itself.
public enum Localization {
    public static func string(_ locale: String, _ key: String) -> String {
        strings[locale]?[key] ?? strings["en"]?[key] ?? key
    }

    public static let strings: [String: [String: String]] = [
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
}
