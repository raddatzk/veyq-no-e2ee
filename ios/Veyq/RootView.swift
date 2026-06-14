import SwiftUI
import VeyqCrypto

/// Placeholder root — replaced by the real space switcher / MemoryHome in slice 1. References the
/// VeyqCrypto package so the app↔core link is exercised at build time.
struct RootView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("veyq")
                .font(.largeTitle.bold())
            Text("core linked · frame size \(Media.defaultChunkSize)")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
