import SwiftUI

/// Root of the universal app: switches between the connect → sign-in → unlock → home phases the `AppModel`
/// drives. One SwiftUI tree, adaptive across iPhone / iPad / Mac (the cards cap their width rather than
/// stretch).
struct RootView: View {
    @StateObject private var app = AppModel()
    @StateObject private var l10n = L10n()
    /// Appearance override (cr-0035): "system" follows the OS; "light"/"dark" force the scheme. `VeyqStyle`
    /// colours are adaptive, so flipping the scheme flips the whole palette.
    @AppStorage("veyq.theme") private var theme = "system"

    var body: some View {
        Group {
            switch app.phase {
            case .launching: LaunchView()
            case .connect: ConnectView()
            case .signIn: SignInView()
            case .unlock: UnlockView()
            case .home: HomeView()
            }
        }
        .environmentObject(app)
        .environmentObject(l10n)
        .preferredColorScheme(colorScheme(theme))
        .task { await app.bootstrap() }
    }

    private func colorScheme(_ theme: String) -> ColorScheme? {
        switch theme {
        case "light": return .light
        case "dark": return .dark
        default: return nil // system
        }
    }
}

/// Brief launch placeholder while `bootstrap()` decides whether to auto-restore a passwordless device
/// (cr-0020) or fall through to connect. Kept minimal — most launches pass through it in a blink.
private struct LaunchView: View {
    var body: some View {
        ZStack {
            VeyqStyle.bg.ignoresSafeArea()
            ProgressView().controlSize(.large)
        }
    }
}

#Preview {
    RootView()
}
