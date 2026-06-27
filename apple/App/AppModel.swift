import Foundation
import LocalAuthentication
import SwiftUI
import VeyqKit

/// Drives the connect → sign-in → unlock → home flow and owns the `VeyqClient` once an instance is chosen.
/// `ObservableObject` (not the `@Observable` macro) because the app deploys to macOS 13 / iOS 16.
///
/// State note (auth ≠ unlock): sign-in is **two steps**, mirroring the web — `login()` (email + password →
/// session) on the sign-in screen, then `unlock()` (password + Secret Key → vault) on the Secret-Key
/// floor. The password is carried transiently here between the two steps (never persisted, cleared on
/// unlock / sign-out). `pendingSecretKey` parks a Secret Key handed over by a **login-screen kit import**
/// until the floor consumes it (the analog of web's `pendingSecretKey`).
@MainActor
final class AppModel: ObservableObject {
    enum Phase: Equatable { case launching, connect, signIn, unlock, home }

    @Published var phase: Phase = .launching
    /// The chosen instance base URL (shown as the sign-in / floor subtitle).
    @Published private(set) var apiBase: String = ""
    /// The signed-in account email (shown on the floor + home).
    @Published private(set) var email: String = ""

    /// A Secret Key parked by a login-screen kit import, consumed (and cleared) by the unlock floor.
    @Published var pendingSecretKey: String = ""

    /// Whether the current device is at the "only password" level (a DEVICE wrap is enrolled). Drives the
    /// untrust control on the home screen (cr-0019 phase 3).
    @Published private(set) var deviceTrusted: Bool = false
    /// Set when enrolling device trust failed after a floor unlock — the unlock still succeeded, but the
    /// UI surfaces "couldn't trust this device" (cr-0005 #3). Cleared on the next attempt / sign-out.
    @Published var deviceTrustFailed: Bool = false

    /// The data-layer client for the chosen instance. Set on connect, dropped on change-instance.
    private(set) var client: VeyqClient?

    /// The password from the login step, carried to the unlock step. Transient — never persisted, cleared
    /// the moment the vault opens or the session ends.
    private var carriedPassword: String = ""

    /// **App-launch auto-restore** for a passwordless device (cr-0020 L2/L3). If this device last used an
    /// L2/L3 level, resume the persisted session and open the vault — silently for L3 ("no login"), after a
    /// Face ID / Touch ID for L2 (reading the gated key prompts it). Anything missing/failed falls back to
    /// the normal connect / sign-in flow. Called once at launch.
    func bootstrap() async {
        guard phase == .launching else { return }
        guard let inst = LaunchPrefs.instance, let acct = LaunchPrefs.account,
              let baseURL = normalizeBaseURL(inst)
        else { phase = .connect; return }

        let client = VeyqClient(instance: VeyqInstance(baseURL: baseURL))
        guard let level = client.currentLevel(email: acct), level == .biometric || level == .noLogin else {
            phase = .connect; return
        }
        self.client = client
        self.apiBase = baseURL.absoluteString
        self.email = acct
        self.deviceTrusted = true
        do {
            // autoRestore does it in a SINGLE gated read (cr-0023): resume the session + open the vault from
            // one device-key load — biometric-gated for L2 (one Face ID prompt), free for L3. The persisted
            // refresh token is sealed under that key, so an unlocked-device attacker can't reuse it.
            if try await client.autoRestore(email: acct) {
                phase = .home
            } else {
                // No usable session/wrap. If the device key itself is gone the record is dead → connect;
                // otherwise the floor can re-auth (but it needs a password we don't hold) → sign-in.
                phase = client.currentLevel(email: acct) == nil ? .connect : .signIn
            }
        } catch {
            phase = .signIn
        }
    }

    /// Connected to an instance that is already set up — advance to sign-in.
    func connected(baseURL: URL, client: VeyqClient) {
        self.apiBase = baseURL.absoluteString
        self.client = client
        phase = .signIn
    }

    /// Step 1 — log in (email + password → session). On a **trusted device** the DEVICE unlock **folds
    /// into login** (cr-0008 parity): the busy sign-in card stays up across the device unlock so it
    /// transitions straight to home and never flashes the floor. If the device key/wrap is unusable (E1)
    /// the flow falls through to the Secret-Key floor. Throws on a login failure (the view maps it to a
    /// generic message).
    func login(email: String, password: String) async throws {
        guard let client else { return }
        try await client.login(email: email, password: password)
        self.email = email
        deviceTrustFailed = false

        if client.isTrusted(email: email) {
            // The login just proved this password, so a DEVICE unlock with it can only fail on a corrupt /
            // foreign / missing wrap — in which case fall through to the floor (never a hard error here).
            if let unlocked = try? await client.unlockWithDevice(email: email, password: password), unlocked {
                carriedPassword = ""
                pendingSecretKey = ""
                deviceTrusted = true
                phase = .home
                return
            }
        }
        self.carriedPassword = password
        phase = .unlock
    }

    /// Step 2 — unlock the floor (carried password + Secret Key → vault), then apply the chosen device
    /// **unlock level** right after a successful unlock (reusing the known-correct password / the live
    /// session before they are cleared): L0 stores nothing; L1 enrols the DEVICE wrap (password); L2/L3
    /// enrol the random-K wrap + persist the session (biometric-gated / ungated) and remember this device
    /// for app-launch auto-restore. An enrol failure does not fail the unlock — it surfaces
    /// `deviceTrustFailed`. Throws on an unlock failure (the floor view maps it generically).
    func unlock(secretKey: String, level: DeviceUnlockLevel) async throws {
        guard let client else { return }
        try await client.unlock(password: carriedPassword, secretKey: secretKey)

        do {
            switch level {
            case .secretKeyEachTime:
                break
            case .onlyPassword:
                try await client.trustThisDevice(email: email, password: carriedPassword)
                deviceTrusted = true
            case .biometric, .noLogin:
                // L3 stores K with no auth gate, so a set passcode is its only at-rest barrier. Enforce it
                // here too (not just in the chooser UI) so the ungated wrap is never written on a passcode-
                // less device — where `WhenUnlockedThisDeviceOnly` would degrade to always-readable.
                if level == .noLogin, !LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) {
                    throw VeyqError.trustFailed
                }
                try await client.setPasswordlessLevel(level == .biometric ? .biometric : .noLogin, email: email)
                LaunchPrefs.remember(instance: apiBase, account: email)
                deviceTrusted = true
            }
        } catch {
            deviceTrustFailed = true   // unlock stands; the level did not take
        }

        carriedPassword = ""
        pendingSecretKey = ""
        phase = .home
    }

    /// Drop this device's convenience level back to "Secret Key each time": remove the wrap + local key +
    /// any persisted session, and forget it for auto-restore. Safe to call from the unlocked home screen.
    func untrust() async {
        guard let client else { return }
        try? await client.untrustThisDevice(email: email)
        LaunchPrefs.clear()
        deviceTrusted = false
    }

    /// Sign out: revoke the session, re-lock the vault, drop all transient secrets, and return to sign-in.
    /// Device trust (the server wrap + Keychain key) is **not** touched — that is what makes the next login
    /// fold straight through; untrust is the explicit way to remove it.
    func signOut() async {
        try? await client?.logout()
        email = ""
        carriedPassword = ""
        pendingSecretKey = ""
        deviceTrusted = false
        deviceTrustFailed = false
        phase = .signIn
    }

    /// Pick a different instance — drop the client + secrets and return to connect.
    func changeInstance() {
        client = nil
        apiBase = ""
        email = ""
        carriedPassword = ""
        pendingSecretKey = ""
        deviceTrusted = false
        deviceTrustFailed = false
        phase = .connect
    }
}

/// Normalize a user-typed instance address into an absolute base URL: default to https, trim a trailing
/// slash, reject anything that isn't a usable http(s) URL.
func normalizeBaseURL(_ raw: String) -> URL? {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !s.isEmpty else { return nil }
    if !s.contains("://") { s = "https://" + s }
    while s.hasSuffix("/") { s.removeLast() }
    guard let url = URL(string: s), let scheme = url.scheme?.lowercased(),
          scheme == "https" || scheme == "http", url.host != nil else { return nil }
    return url
}
