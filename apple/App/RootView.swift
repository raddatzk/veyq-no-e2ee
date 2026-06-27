import SwiftUI

/// Root of the universal app: switches between the connect → sign-in → unlock → home phases the `AppModel`
/// drives. One SwiftUI tree, adaptive across iPhone / iPad / Mac (the cards cap their width rather than
/// stretch).
struct RootView: View {
    @StateObject private var app = AppModel()

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
        .task { await app.bootstrap() }
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
