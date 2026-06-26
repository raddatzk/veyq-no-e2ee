import Foundation
import SwiftUI
import VeyqKit

/// Drives the connect → sign-in → home flow and owns the `VeyqClient` once an instance is chosen.
/// `ObservableObject` (not the `@Observable` macro) because the app deploys to macOS 13 / iOS 16.
///
/// State note (auth ≠ unlock): a successful sign-in does both a `login` (session) and an `unlock`
/// (vault) — the two distinct steps the data layer separates — behind one submit, mirroring the legacy
/// native UX. The home screen reflects the unlocked result; the password is held only transiently in the
/// sign-in view and never stored here.
@MainActor
final class AppModel: ObservableObject {
    enum Phase: Equatable { case connect, signIn, home }

    @Published var phase: Phase = .connect
    /// The chosen instance base URL (shown as the sign-in subtitle).
    @Published private(set) var apiBase: String = ""
    /// The signed-in account email (shown on the home screen).
    @Published private(set) var email: String = ""

    /// The data-layer client for the chosen instance. Set on connect, dropped on change-instance.
    private(set) var client: VeyqClient?

    /// Connected to an instance that is already set up — advance to sign-in.
    func connected(baseURL: URL, client: VeyqClient) {
        self.apiBase = baseURL.absoluteString
        self.client = client
        phase = .signIn
    }

    /// Signed in and unlocked — advance to the home screen.
    func unlocked(email: String) {
        self.email = email
        phase = .home
    }

    /// Sign out: revoke the session, re-lock the vault, and return to the sign-in screen (same instance).
    func signOut() async {
        try? await client?.logout()
        email = ""
        phase = .signIn
    }

    /// Pick a different instance — drop the client and return to connect.
    func changeInstance() {
        client = nil
        apiBase = ""
        email = ""
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
