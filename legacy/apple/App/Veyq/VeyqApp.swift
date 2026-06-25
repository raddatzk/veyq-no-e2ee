import SwiftUI

/// Greenfield app entry. The real UI (space switcher, MemoryHome, detail, upload) is built as slices
/// on top of the `apple/` VeyqCrypto package; this is the bootable shell that proves the wiring.
@main
struct VeyqApp: App {
    init() { Self.cleanupSharedTemp() }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }

    /// Wipe leftover plaintext-at-rest artifacts from the temp dir on launch: the Emergency Kit PDF (plaintext
    /// Secret Key) and the media-export dir (decrypted, GPS-bearing originals + zips). All are shared out via the
    /// activity sheet and none can be mid-share at a cold start, so deleting them here bounds their lifetime to one
    /// session instead of letting them linger in the container.
    private static func cleanupSharedTemp() {
        let tmp = FileManager.default.temporaryDirectory
        let files = (try? FileManager.default.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.lastPathComponent.hasPrefix("veyq-emergency-kit") { try? FileManager.default.removeItem(at: f) }
        DownloadService.cleanupExports()
    }
}
