import XCTest
@testable import VeyqKit

/// The runtime-i18n string resolution (cr-0036) — per-locale lookup with an English fallback; an unknown key
/// resolves to itself. Backs `uc.settings.appearance-language` on Apple.
final class LocalizationTests: XCTestCase {
    func testResolvesPerLocale() {
        XCTAssertEqual(Localization.string("de", "home.memories"), "Erinnerungen")
        XCTAssertEqual(Localization.string("en", "home.memories"), "Memories")
        XCTAssertEqual(Localization.string("de", "settings.appearance"), "Darstellung")
    }

    func testUnknownLocaleFallsBackToEnglish() {
        XCTAssertEqual(Localization.string("fr", "home.folders"), "Folders")
    }

    func testUnknownKeyResolvesToItself() {
        XCTAssertEqual(Localization.string("en", "nope.missing"), "nope.missing")
    }

    func testDeAndEnCoverTheSameKeys() {
        let en = Set(Localization.strings["en"]!.keys)
        let de = Set(Localization.strings["de"]!.keys)
        XCTAssertEqual(en, de, "every English key must have a German translation (and vice versa)")
    }

    func testResolvePreference() {
        // a concrete choice wins regardless of the system
        XCTAssertEqual(Localization.resolve(preference: "en", systemLanguageCode: "de"), "en")
        XCTAssertEqual(Localization.resolve(preference: "de", systemLanguageCode: "en"), "de")
        // "system" follows the OS: German → de, anything else (incl. nil) → en
        XCTAssertEqual(Localization.resolve(preference: "system", systemLanguageCode: "de"), "de")
        XCTAssertEqual(Localization.resolve(preference: "system", systemLanguageCode: "fr"), "en")
        XCTAssertEqual(Localization.resolve(preference: "system", systemLanguageCode: nil), "en")
    }
}
