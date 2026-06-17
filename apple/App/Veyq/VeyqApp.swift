import SwiftUI

/// Greenfield app entry. The real UI (space switcher, MemoryHome, detail, upload) is built as slices
/// on top of the `apple/` VeyqCrypto package; this is the bootable shell that proves the wiring.
@main
struct VeyqApp: App {
    init() { Self.cleanupKitTemp() }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }

    /// Wipe any leftover Emergency Kit PDFs from the temp dir on launch. The kit embeds the plaintext Secret
    /// Key and is shared out via the activity sheet; none can be mid-share at a cold start, so deleting it here
    /// bounds its lifetime to one session instead of letting it linger. Targets only the kit files by name.
    private static func cleanupKitTemp() {
        let tmp = FileManager.default.temporaryDirectory
        let files = (try? FileManager.default.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.lastPathComponent.hasPrefix("veyq-emergency-kit") { try? FileManager.default.removeItem(at: f) }
    }
}
