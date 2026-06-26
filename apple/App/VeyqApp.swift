import SwiftUI

/// veyq universal app entry (cr-0015). One SwiftUI target across macOS / iOS / iPadOS.
///
/// Phase 1 is the bootable shell that proves the wiring (App → VeyqKit → generated clients +
/// VeyqCrypto). The real login + unlock screens are ported from `legacy/iOS/Veyq` in phase 4.
@main
struct VeyqApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}
