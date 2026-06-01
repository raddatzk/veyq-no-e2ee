import SwiftUI

enum AppThemePreference: String, CaseIterable, Identifiable {
    static let storageKey = "veyq.appearance.theme"

    case system
    case light
    case dark

    var id: String { rawValue }

    var localizedTitle: String {
        switch self {
        case .system: L10n.string("System")
        case .light: L10n.string("Light")
        case .dark: L10n.string("Dark")
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }

    static func resolve(_ rawValue: String) -> AppThemePreference {
        AppThemePreference(rawValue: rawValue) ?? .system
    }
}

enum AppLanguagePreference: String, CaseIterable, Identifiable {
    static let storageKey = "veyq.appearance.language"

    case system
    case de
    case en

    var id: String { rawValue }

    var localizedTitle: String {
        switch self {
        case .system: L10n.string("System")
        case .de: L10n.string("German")
        case .en: L10n.string("English")
        }
    }

    var locale: Locale {
        switch self {
        case .system: .autoupdatingCurrent
        case .de: Locale(identifier: "de")
        case .en: Locale(identifier: "en")
        }
    }

    static func resolve(_ rawValue: String) -> AppLanguagePreference {
        AppLanguagePreference(rawValue: rawValue) ?? .system
    }
}
