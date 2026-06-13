import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class ScopeContentE2ETests: XCTestCase {
    func testWriteAndReadEncryptedContentUnderPersonalScope() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "owner@example.com", displayName: "Owner", password: pass)
        let session = try await Login.login(apiBase: apiBase, email: "owner@example.com", password: pass, secretKeyB64: kit.secretKey)

        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: session)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session, scope: scope)

        let secret = "Italien 2024 — Tag 1"
        try await ScopeContent.writeItem(apiBase: apiBase, session: session, scope: scope, contentKey: ck, plaintext: secret)
        try await ScopeContent.writeItem(apiBase: apiBase, session: session, scope: scope, contentKey: ck, plaintext: "second note")

        let items = try await ScopeContent.readItems(apiBase: apiBase, session: session, scope: scope, contentKey: ck)
        XCTAssertTrue(items.contains(secret))
        XCTAssertTrue(items.contains("second note"))

        // a fresh login recovers the same content (stable, scope-wrapped content key)
        let session2 = try await Login.login(apiBase: apiBase, email: "owner@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope2 = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: session2)
        let ck2 = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: session2, scope: scope2)
        let items2 = try await ScopeContent.readItems(apiBase: apiBase, session: session2, scope: scope2, contentKey: ck2)
        XCTAssertTrue(items2.contains(secret))
    }
}
