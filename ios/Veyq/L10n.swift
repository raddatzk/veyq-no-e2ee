import Foundation

enum L10n {
    static func string(_ key: String) -> String {
        string(key, languagePreference: currentLanguagePreference())
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        let preference = currentLanguagePreference()
        return String(format: string(key, languagePreference: preference), locale: locale(for: preference), arguments: arguments)
    }

    static func string(_ key: String, languagePreference: AppLanguagePreference) -> String {
        guard languagePreference != .system,
              let bundle = bundle(for: languagePreference.rawValue)
        else {
            return NSLocalizedString(key, comment: "")
        }

        return NSLocalizedString(key, bundle: bundle, comment: "")
    }

    private static func currentLanguagePreference() -> AppLanguagePreference {
        AppLanguagePreference.resolve(UserDefaults.standard.string(forKey: AppLanguagePreference.storageKey) ?? "")
    }

    private static func locale(for preference: AppLanguagePreference) -> Locale {
        switch preference {
        case .system: .current
        case .de: Locale(identifier: "de")
        case .en: Locale(identifier: "en")
        }
    }

    private static func bundle(for languageCode: String) -> Bundle? {
        bundleCandidates
            .compactMap { $0.path(forResource: languageCode, ofType: "lproj") }
            .compactMap(Bundle.init(path:))
            .first
    }
}

private final class L10nBundleMarker {}

private let bundleCandidates = [
    Bundle.main,
    Bundle(for: L10nBundleMarker.self)
]
