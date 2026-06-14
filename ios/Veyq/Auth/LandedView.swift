import SwiftUI
import VeyqCrypto

/// Logged-in stub — slice 2 replaces this with the tab bar + MemoryHome.
struct LandedView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        AuthCard(title: "Eingeloggt", subtitle: app.session.map { "\($0.user.displayName) · \($0.user.email)" }) {
            VStack(spacing: 14) {
                Text("Slice 2 ersetzt das durch die Tab-Bar + MemoryHome.")
                    .font(.system(size: 12)).foregroundStyle(VeyqStyle.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button("Abmelden") { signOut() }
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(VeyqStyle.muted)
                    .frame(maxWidth: .infinity).padding(.vertical, 11)
                    .overlay(RoundedRectangle(cornerRadius: VeyqStyle.radiusSm).strokeBorder(VeyqStyle.line))
            }
        }
    }

    private func signOut() {
        let base = app.apiBase
        let token = app.session?.sessionToken
        app.signOut()
        if let token { Task { try? await Login.logout(apiBase: base, sessionToken: token) } }
    }
}
