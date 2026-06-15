import SwiftUI
import VeyqCrypto

/// Slice-1 navigation + session state. Mobile picks an instance (unlike web, which is same-origin), so
/// the flow is: connect → login → landed. First-run server setup is web-only.
@MainActor
final class AppState: ObservableObject {
    enum Phase { case connect, login, register, landed }

    private static let instanceKey = "veyq.instance.url"

    @Published var phase: Phase = .connect
    @Published var apiBase: String = UserDefaults.standard.string(forKey: AppState.instanceKey) ?? ""
    @Published var session: Login.UnlockedSession?
    @Published var pendingInvite: InviteLink.Parts?
    /// The instance's public app origin (for assembling invite links); falls back to apiBase if unknown.
    @Published var appBaseUrl: String = ""

    func connected(apiBase: String, appBaseUrl: String? = nil) {
        self.apiBase = apiBase
        self.appBaseUrl = appBaseUrl ?? apiBase
        UserDefaults.standard.set(apiBase, forKey: Self.instanceKey)
        phase = .login
    }

    /// A veyq://invite deep link (from the mobile browser banner): point at the named instance and jump a
    /// not-signed-in user straight into registration. Ignored when already signed in.
    func handleDeepLink(_ url: URL) {
        guard session == nil, let dl = InviteLink.parseDeepLink(url.absoluteString) else { return }
        apiBase = dl.apiBase
        UserDefaults.standard.set(dl.apiBase, forKey: Self.instanceKey)
        pendingInvite = dl.parts
        phase = .register
    }

    func cancelRegister() {
        pendingInvite = nil
        phase = .login
    }

    func loggedIn(_ session: Login.UnlockedSession) {
        self.session = session
        phase = .landed
    }

    func signOut() {
        session = nil
        phase = .login
    }

    func changeInstance() {
        session = nil
        phase = .connect
    }
}

/// Normalize a user-typed instance URL (accept bare host, default https, strip trailing slash).
func normalizeApiBase(_ input: String) -> String {
    var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
    while s.hasSuffix("/") { s.removeLast() }
    if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "https://\(s)" }
    return s
}
