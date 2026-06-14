import SwiftUI

/// Slice 1 → 2: connect → login → MemoryHome.
struct RootView: View {
    @StateObject private var app = AppState()

    var body: some View {
        Group {
            switch app.phase {
            case .connect: ConnectView()
            case .login: LoginView()
            case .landed:
                if let session = app.session {
                    MemoryHomeView(apiBase: app.apiBase, session: session)
                }
            }
        }
        .environmentObject(app)
        .preferredColorScheme(.dark)
    }
}
