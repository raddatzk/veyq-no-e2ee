import XCTest
import Foundation
@testable import VeyqCrypto

/// Live (VEYQ_E2E=1): two iOS JournalLiveProviders join the same journal room through the operator-blind
/// relay and converge — proving the native Yrs CRDT + the y-websocket wire framing + AEAD + the relay all
/// work end-to-end. The framing is byte-identical to the web provider, so this also backs web↔iOS interop.
final class JournalLiveE2ETests: XCTestCase {
    func testTwoProvidersConverge() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"
        let pass = "correct horse battery staple"

        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "jlive@example.com", displayName: "JLive", password: pass)
        let session = try await Login.login(apiBase: apiBase, email: "jlive@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: session)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session, scope: scope)

        let wsBase = apiBase.replacingOccurrences(of: "http", with: "ws")
        let url = URL(string: "\(wsBase)/ws/journal/\(scope.scopeId)/n1?token=\(session.sessionToken)")!
        let a = JournalLiveProvider(url: url, contentKey: ck)
        let b = JournalLiveProvider(url: url, contentKey: ck)
        let bPeers = PeerBox()
        b.onPeers = { bPeers.set($0) }

        try await waitFor { a.currentText == "" && b.currentText == "" } // both connected
        try await Task.sleep(nanoseconds: 300_000_000) // let the sync settle

        // awareness (presence + cursor): A announces itself; B should see it
        a.setUser(name: "Alice", color: "#10b981")
        a.setCursor(3)
        try await waitFor { bPeers.get().contains { $0.name == "Alice" && $0.cursor == 3 } }
        XCTAssertTrue(bPeers.get().contains { $0.name == "Alice" && $0.cursor == 3 })

        a.applyLocalText("hallo welt")
        try await waitFor { b.currentText == "hallo welt" }
        XCTAssertEqual(b.currentText, "hallo welt")

        // concurrent edits merge (CRDT): B appends, A prepends — both converge to the same string
        b.applyLocalText("hallo welt !")
        a.applyLocalText("> hallo welt")
        try await waitFor { a.currentText == b.currentText && a.currentText.contains("hallo welt") }
        XCTAssertEqual(a.currentText, b.currentText)

        a.destroy(); b.destroy()
    }

    /// Thread-safe holder for the peers list (onPeers fires off the websocket thread).
    final class PeerBox: @unchecked Sendable {
        private let lock = NSLock(); private var value: [JournalLiveProvider.Peer] = []
        func set(_ v: [JournalLiveProvider.Peer]) { lock.lock(); value = v; lock.unlock() }
        func get() -> [JournalLiveProvider.Peer] { lock.lock(); defer { lock.unlock() }; return value }
    }

    private func waitFor(_ cond: @escaping () -> Bool, timeout: TimeInterval = 6) async throws {
        let start = Date()
        while !cond() {
            if Date().timeIntervalSince(start) > timeout { XCTFail("timeout"); return }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
    }
}
