import XCTest

// End-to-end UI round-trip (cr-0015 phase 4/5): drives the macOS app through connect → sign-in → unlock
// against a REAL running instance and writes a screenshot of each screen to VEYQ_SHOT_DIR. Skipped unless
// the env is set (VEYQ_LIVE_BASE + VEYQ_EMAIL/PASSWORD/SECRET + VEYQ_SHOT_DIR), so the normal suite stays
// hermetic. This is both the phase-4 "round-trips login→unlock against a fresh instance" proof through the
// actual UI and the source of the phase-5 design-reference screenshots.
//
//   VEYQ_LIVE_BASE=http://localhost:8080 VEYQ_EMAIL=… VEYQ_PASSWORD=… VEYQ_SECRET=… \
//   VEYQ_SHOT_DIR=/abs/path/to/docs/design/screenshots/apple \
//   xcodebuild test -project Veyq.xcodeproj -scheme Veyq -destination 'platform=macOS' -skipPackagePluginValidation
final class RoundTripUITests: XCTestCase {

    @MainActor
    func testConnectSignInUnlock() throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"],
              let email = env["VEYQ_EMAIL"],
              let password = env["VEYQ_PASSWORD"],
              let secret = env["VEYQ_SECRET"],
              let shotDir = env["VEYQ_SHOT_DIR"]
        else { throw XCTSkip("set VEYQ_LIVE_BASE/EMAIL/PASSWORD/SECRET/SHOT_DIR to run the UI round-trip") }

        let app = XCUIApplication()
        // The sign-in fields are prefilled via this seam (SwiftUI SecureField drops chars under typeText);
        // the test still drives the real Connect typing + the Sign-in/Home button flow + orchestration.
        app.launchEnvironment["UITEST_EMAIL"] = email
        app.launchEnvironment["UITEST_PASSWORD"] = password
        app.launchEnvironment["UITEST_SECRET"] = secret
        app.launch()

        // 1 — Connect
        let url = app.textFields["Instance URL"]
        XCTAssertTrue(url.waitForExistence(timeout: 15), "Connect screen should show")
        shoot(app, shotDir, "01-connect")
        fill(url, base)
        shoot(app, shotDir, "02-connect-filled")
        app.buttons["Connect"].activate()

        // 2 — Sign in: email + password (prefilled via the seam; password stays masked)
        let emailField = app.textFields["Email"]
        XCTAssertTrue(emailField.waitForExistence(timeout: 20), "Sign-in screen should show after connect")
        XCTAssertEqual(emailField.value as? String, email, "email should be prefilled")
        shoot(app, shotDir, "03-signin")
        app.buttons["Sign in"].activate()

        // 3 — Unlock floor: Secret Key (prefilled via the seam)
        let unlockButton = app.buttons["Unlock"]
        XCTAssertTrue(unlockButton.waitForExistence(timeout: 25), "Unlock floor should show after login")
        shoot(app, shotDir, "04-unlock")
        unlockButton.activate()

        // 4 — Home (unlocked)
        XCTAssertTrue(app.staticTexts["Unlocked"].waitForExistence(timeout: 25), "Home should show after unlock")
        shoot(app, shotDir, "05-home")
    }

    /// Save a screenshot of the app window to `<dir>/<name>.png` and also attach it to the result.
    private func shoot(_ app: XCUIApplication, _ dir: String, _ name: String) {
        let shot = app.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        // Best-effort direct write — works when the test runs on the host (macOS); a no-op in the iOS
        // simulator sandbox, where the screenshots are pulled from the result bundle's attachments instead.
        let url = URL(fileURLWithPath: dir).appendingPathComponent("\(name).png")
        try? FileManager.default.createDirectory(at: URL(fileURLWithPath: dir), withIntermediateDirectories: true)
        try? shot.pngRepresentation.write(to: url)
    }
}

private extension RoundTripUITests {
    /// Focus a field and type it one character at a time. A single `typeText(whole)` into a SwiftUI
    /// `SecureField` drops all but one character (each keystroke triggers a view update that resets the
    /// field), so the password / Secret Key arrive truncated — typing per-character lets each keystroke
    /// settle. The plain `TextField`s don't need this but it's harmless there.
    func fill(_ field: XCUIElement, _ text: String) {
        field.activate()
        for ch in text { field.typeText(String(ch)) }
    }
}

private extension XCUIElement {
    /// Focus an element: `click()` on macOS, `tap()` on iOS / iPadOS.
    func activate() {
        #if os(macOS)
        click()
        #else
        tap()
        #endif
    }
}
