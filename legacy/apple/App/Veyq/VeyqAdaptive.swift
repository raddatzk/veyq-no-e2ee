import SwiftUI

/// Adaptive helpers for the universal app (iPhone + iPad + macOS via Catalyst). The goal is to stop the
/// large-screen UI from looking like a stretched iPhone: cap content to a comfortable reading width and
/// centre it. These are intentionally inert on iPhone, where the screen is already narrower than the cap,
/// so the shipped compact layout is untouched.
extension View {
    /// Cap content to `maxWidth` and centre it. On iPhone the screen is narrower than the cap, so this is a
    /// no-op; on iPad/Mac it keeps cards and rows from sprawling edge-to-edge.
    func readableWidth(_ maxWidth: CGFloat = 1024) -> some View {
        frame(maxWidth: maxWidth).frame(maxWidth: .infinity, alignment: .center)
    }
}
