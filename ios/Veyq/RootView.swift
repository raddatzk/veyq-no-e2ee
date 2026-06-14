import SwiftUI

/// Slice 1: connect → login → landed. The real tab bar + MemoryHome arrive in slice 2.
struct RootView: View {
    @StateObject private var app = AppState()

    var body: some View {
        Group {
            switch app.phase {
            case .connect: ConnectView()
            case .login: LoginView()
            case .landed: LandedView()
            }
        }
        .environmentObject(app)
        .preferredColorScheme(.dark)
    }
}
