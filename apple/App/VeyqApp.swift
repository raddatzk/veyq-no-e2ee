import SwiftUI

/// veyq universal app entry (cr-0015). One SwiftUI target across macOS / iOS / iPadOS — the connect →
/// sign-in → home (login + unlock) flow, ported from `legacy/iOS/Veyq` onto the `VeyqKit` data layer.
@main
struct VeyqApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
        #if os(macOS)
        .defaultSize(width: 760, height: 680)
        #endif
    }
}
