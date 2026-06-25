import SwiftUI

/// Slice 1 → 2: connect → login → MemoryHome.
struct RootView: View {
    @StateObject private var app = AppState()
    @AppStorage("veyq.theme") private var theme = "system"
    @AppStorage("veyq.locale") private var locale = "de"

    var body: some View {
        Group {
            switch app.phase {
            case .connect: ConnectView()
            case .login: LoginView()
            case .register:
                if let invite = app.pendingInvite {
                    RegisterView(parts: invite)
                }
            case .landed:
                if let session = app.session {
                    MemoryHomeView(apiBase: app.apiBase, session: session)
                }
            }
        }
        .environmentObject(app)
        .onOpenURL { app.handleDeepLink($0) }
        .preferredColorScheme(themeScheme(theme))
        .id(locale) // rebuild the tree so every T(...) re-resolves on language change
    }
}

/// Map the stored theme preference to a SwiftUI color scheme (nil = follow the system).
func themeScheme(_ theme: String) -> ColorScheme? {
    switch theme {
    case "dark": return .dark
    case "light": return .light
    default: return nil
    }
}
