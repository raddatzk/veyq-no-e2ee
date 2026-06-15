import SwiftUI

/// Greenfield app entry. The real UI (space switcher, MemoryHome, detail, upload) is built as slices
/// on top of the `apple/` VeyqCrypto package; this is the bootable shell that proves the wiring.
@main
struct VeyqApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
