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
        app.buttons["level.secretKeyEachTime"].activate()   // no default — pick a level to enable Unlock
        unlockButton.activate()

        // 4 — Home (unlocked)
        XCTAssertTrue(app.staticTexts["Unlocked"].waitForExistence(timeout: 25), "Home should show after unlock")
        shoot(app, shotDir, "05-home")
    }

    /// cr-0019 — the device-trust lifecycle through the **real UI** (real Keychain in-app): floor unlock at
    /// "Only my password" → Home shows trusted → sign out → re-login **folds straight to Home** (no floor)
    /// → untrust → sign out → re-login lands back on the floor. Writes the trust-* design references.
    @MainActor
    func testTrustDeviceLifecycle() throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let email = env["VEYQ_EMAIL"],
              let password = env["VEYQ_PASSWORD"], let secret = env["VEYQ_SECRET"],
              let shotDir = env["VEYQ_SHOT_DIR"]
        else { throw XCTSkip("set VEYQ_LIVE_BASE/EMAIL/PASSWORD/SECRET/SHOT_DIR to run the UI round-trip") }

        let app = XCUIApplication()
        app.launchEnvironment["UITEST_EMAIL"] = email
        app.launchEnvironment["UITEST_PASSWORD"] = password
        app.launchEnvironment["UITEST_SECRET"] = secret
        app.launch()

        // Connect → Sign in.
        let url = app.textFields["Instance URL"]
        XCTAssertTrue(url.waitForExistence(timeout: 15))
        fill(url, base)
        app.buttons["Connect"].activate()
        XCTAssertTrue(app.textFields["Email"].waitForExistence(timeout: 20))
        app.buttons["Sign in"].activate()

        // Robustness: a prior run may have left this device trusted (Keychain persists). If sign-in folds
        // straight to Home, untrust + sign out + sign in again to reach a known untrusted floor.
        if app.staticTexts["Unlocked"].waitForExistence(timeout: 20) {
            if app.buttons["Stop trusting this device"].exists {
                app.buttons["Stop trusting this device"].activate()
            }
            app.buttons["Sign out"].activate()
            XCTAssertTrue(app.textFields["Email"].waitForExistence(timeout: 20))
            app.buttons["Sign in"].activate()
        }

        // 1 — Floor with the level chooser; pick "Only my password" and unlock.
        let unlock = app.buttons["Unlock"]
        XCTAssertTrue(unlock.waitForExistence(timeout: 25), "untrusted device shows the floor")
        shoot(app, shotDir, "trust-01-floor-chooser")
        app.buttons["level.onlyPassword"].activate()
        shoot(app, shotDir, "trust-02-floor-only-password")
        unlock.activate()

        // 2 — Home, now trusted (the untrust control is present).
        XCTAssertTrue(app.staticTexts["Unlocked"].waitForExistence(timeout: 25))
        XCTAssertTrue(app.buttons["Stop trusting this device"].waitForExistence(timeout: 5),
                      "Home shows the trusted state + untrust control")
        shoot(app, shotDir, "trust-03-home-trusted")

        // 3 — Sign out → sign in → folds STRAIGHT to Home (no floor), unlocked with only the password.
        app.buttons["Sign out"].activate()
        XCTAssertTrue(app.textFields["Email"].waitForExistence(timeout: 20))
        app.buttons["Sign in"].activate()
        XCTAssertTrue(app.staticTexts["Unlocked"].waitForExistence(timeout: 25),
                      "a trusted device folds login straight to Home")
        XCTAssertFalse(app.buttons["Unlock"].exists, "the floor must not appear on a trusted re-login")
        shoot(app, shotDir, "trust-04-relogin-fold")

        // 4 — Untrust → sign out → sign in → back to the floor.
        app.buttons["Stop trusting this device"].activate()
        XCTAssertTrue(waitForGone(app.buttons["Stop trusting this device"], timeout: 10),
                      "untrust removes the control")
        app.buttons["Sign out"].activate()
        XCTAssertTrue(app.textFields["Email"].waitForExistence(timeout: 20))
        app.buttons["Sign in"].activate()
        XCTAssertTrue(app.buttons["Unlock"].waitForExistence(timeout: 25),
                      "after untrust, re-login lands back on the Secret-Key floor")
        shoot(app, shotDir, "trust-05-floor-after-untrust")
    }

    /// cr-0020 — the **L3 "no login" auto-restore** through the real UI + real Keychain: floor unlock at
    /// "Open straight in" → home → **relaunch opens straight to home** (no connect, no floor). The app must
    /// be uninstalled before this runs (clean LaunchPrefs + Keychain). Face-ID-gated L2 is a manual smoke.
    @MainActor
    func testNoLoginAutoRestoreLifecycle() throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let email = env["VEYQ_EMAIL"],
              let password = env["VEYQ_PASSWORD"], let secret = env["VEYQ_SECRET"],
              let shotDir = env["VEYQ_SHOT_DIR"]
        else { throw XCTSkip("set VEYQ_LIVE_BASE/EMAIL/PASSWORD/SECRET/SHOT_DIR to run the UI round-trip") }

        let app = XCUIApplication()
        app.launchEnvironment["UITEST_EMAIL"] = email
        app.launchEnvironment["UITEST_PASSWORD"] = password
        app.launchEnvironment["UITEST_SECRET"] = secret
        app.launch()

        // Fresh (uninstalled) → connect → sign in → floor.
        let url = app.textFields["Instance URL"]
        XCTAssertTrue(url.waitForExistence(timeout: 15))
        fill(url, base)
        app.buttons["Connect"].activate()
        XCTAssertTrue(app.textFields["Email"].waitForExistence(timeout: 20))
        app.buttons["Sign in"].activate()

        // Floor → pick "Open straight in" (L3) and unlock.
        let unlock = app.buttons["Unlock"]
        XCTAssertTrue(unlock.waitForExistence(timeout: 25), "untrusted device shows the floor")
        app.buttons["level.noLogin"].activate()
        shoot(app, shotDir, "trust-l3-01-floor-no-login")
        unlock.activate()
        XCTAssertTrue(app.staticTexts["Unlocked"].waitForExistence(timeout: 25))
        shoot(app, shotDir, "trust-l3-02-home")

        // Relaunch → auto-restore straight to home (no connect screen, no floor).
        app.terminate()
        app.launch()
        XCTAssertTrue(app.staticTexts["Unlocked"].waitForExistence(timeout: 25),
                      "an L3 device opens straight to home on relaunch")
        XCTAssertFalse(app.textFields["Instance URL"].exists, "no connect screen on auto-restore")
        XCTAssertFalse(app.buttons["Unlock"].exists, "no Secret-Key floor on auto-restore")
        shoot(app, shotDir, "trust-l3-03-autorestore")

        // Cleanup so the owner + this device are left clean.
        app.buttons["Stop trusting this device"].activate()
    }

    /// Wait until an element no longer exists.
    private func waitForGone(_ element: XCUIElement, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline { if !element.exists { return true }; usleep(200_000) }
        return !element.exists
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
