import Foundation
import VeyqKit

/// Lightweight runtime i18n (cr-0035, ported from `legacy/iOS/Veyq/L10n.swift`). As an `ObservableObject`,
/// changing `locale` re-renders its observers (the shell + settings) **without recreating any `@StateObject`**
/// — so switching language never reloads the tree (the legacy `.id(locale)` root-rebuild would). The string
/// table + resolution live in `VeyqKit.Localization` (pure + unit-tested); this just holds the active locale.
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

    func t(_ key: String) -> String { Localization.string(locale, key) }

    /// Format a string carrying a single `%@` placeholder.
    func t(_ key: String, _ arg: String) -> String { String(format: t(key), arg) }
}
