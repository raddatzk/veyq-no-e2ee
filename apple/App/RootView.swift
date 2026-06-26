import SwiftUI

/// Root of the universal app: switches between the connect → sign-in → unlock → home phases the `AppModel`
/// drives. One SwiftUI tree, adaptive across iPhone / iPad / Mac (the cards cap their width rather than
/// stretch).
struct RootView: View {
    @StateObject private var app = AppModel()

    var body: some View {
        Group {
            switch app.phase {
            case .connect: ConnectView()
            case .signIn: SignInView()
            case .unlock: UnlockView()
            case .home: HomeView()
            }
        }
        .environmentObject(app)
    }
}

#Preview {
    RootView()
}
