import SwiftUI
import VeyqKit

/// Phase-1 placeholder root. Confirms the app links the shared libraries and adapts across
/// iPhone / iPad / Mac. The connect → login → unlock flow (ported from `legacy/iOS/Veyq`) lands
/// in phase 4.
struct RootView: View {
    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.shield")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("veyq")
                .font(.largeTitle.weight(.semibold))
            Text("Operator-blind. Login + unlock coming next.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    RootView()
}
