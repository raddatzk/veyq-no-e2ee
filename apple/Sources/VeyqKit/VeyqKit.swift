import Foundation
import VeyqApi
import VeyqCrypto

// VeyqKit — session + vault + login/unlock orchestration.
//
// Phase 1 (cr-0015) lands only the scaffold: the layering compiles and the generated clients are
// reachable. Login (phase 2), unlock (phase 3) and the SwiftUI heads (phase 4) fill this in.

/// The base address of an already-bootstrapped veyq instance (native never bootstraps; setup is
/// browser-only). The login/unlock orchestration in later phases hangs off this.
public struct VeyqInstance: Sendable {
    public let baseURL: URL

    public init(baseURL: URL) {
        self.baseURL = baseURL
    }
}
