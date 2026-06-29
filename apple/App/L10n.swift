import Foundation
import VeyqKit

/// Lightweight runtime i18n (cr-0035/0037, ported from `legacy/iOS/Veyq/L10n.swift`). Holds the language
/// **preference** — "system" (follow the OS, the default), "en", or "de". As an `ObservableObject`, changing
/// it re-renders observers (the shell + settings) **without recreating any `@StateObject`** — so switching
/// language never reloads the tree. While on "system" it follows the OS language live (observes the locale
/// change). The string table + resolution live in `VeyqKit.Localization` (pure + unit-tested).
@MainActor
final class L10n: ObservableObject {
    private static let key = "veyq.locale"

    /// "system" | "en" | "de" — default "system".
    @Published var preference: String {
        didSet { UserDefaults.standard.set(preference, forKey: Self.key) }
    }

    init() {
        let stored = UserDefaults.standard.string(forKey: Self.key)
        preference = (stored == "en" || stored == "de" || stored == "system") ? stored! : "system"
        // Follow the OS language live while on "system".
        NotificationCenter.default.addObserver(
            forName: NSLocale.currentLocaleDidChangeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, self.preference == "system" else { return }
            self.objectWillChange.send()
        }
    }

    /// The concrete locale the UI uses — "system" resolves to the OS language.
    var effective: String {
        Localization.resolve(preference: preference, systemLanguageCode: Locale.current.language.languageCode?.identifier)
    }

    func t(_ key: String) -> String { Localization.string(effective, key) }

    /// Format a string carrying a single `%@` placeholder.
    func t(_ key: String, _ arg: String) -> String { String(format: t(key), arg) }
}
