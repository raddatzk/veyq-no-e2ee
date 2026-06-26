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

    /// Step 1 — log in (email + password → session). On success the email + password are held and the flow
    /// advances to the Secret-Key floor. Throws on failure (the view maps it to a generic message).
    func login(email: String, password: String) async throws {
        guard let client else { return }
        try await client.login(email: email, password: password)
        self.email = email
        self.carriedPassword = password
        phase = .unlock
    }

    /// Step 2 — unlock (carried password + Secret Key → vault). On success the secrets are dropped and the
    /// flow advances to home. Throws on failure (the floor view maps it to a generic message).
    func unlock(secretKey: String) async throws {
        guard let client else { return }
        try await client.unlock(password: carriedPassword, secretKey: secretKey)
        carriedPassword = ""
        pendingSecretKey = ""
        phase = .home
    }

    /// Sign out: revoke the session, re-lock the vault, drop all transient secrets, and return to sign-in.
    func signOut() async {
        try? await client?.logout()
        email = ""
        carriedPassword = ""
        pendingSecretKey = ""
        phase = .signIn
    }

    /// Pick a different instance — drop the client + secrets and return to connect.
    func changeInstance() {
        client = nil
        apiBase = ""
        email = ""
        carriedPassword = ""
        pendingSecretKey = ""
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
