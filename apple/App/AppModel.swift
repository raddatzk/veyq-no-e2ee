import Foundation
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
    enum Phase: Equatable { case connect, signIn, unlock, home }

    @Published var phase: Phase = .connect
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

    /// Step 2 — unlock the floor (carried password + Secret Key → vault). When `trustThisDevice` is set
    /// (the floor's "only password" choice), enrol the DEVICE wrap **right after** a successful unlock,
    /// reusing the known-correct password before it is cleared. An enrol failure does not fail the unlock —
    /// it surfaces `deviceTrustFailed`. Throws on an unlock failure (the floor view maps it generically).
    func unlock(secretKey: String, trustThisDevice: Bool = false) async throws {
        guard let client else { return }
        try await client.unlock(password: carriedPassword, secretKey: secretKey)

        if trustThisDevice {
            do {
                try await client.trustThisDevice(email: email, password: carriedPassword)
                deviceTrusted = true
            } catch {
                deviceTrustFailed = true   // unlock stands; trust did not take
            }
        }

        carriedPassword = ""
        pendingSecretKey = ""
        phase = .home
    }

    /// Untrust this device (back to "Secret Key each time"): remove the DEVICE wrap + local key. Safe to
    /// call from the unlocked home screen.
    func untrust() async {
        guard let client else { return }
        try? await client.untrustThisDevice(email: email)
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
