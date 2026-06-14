import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class AddMediaE2ETests: XCTestCase {
    func testSealsMediaAndOrdersByTakenAt() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: pass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: a)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: scope)
        let memory = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "MEMORY", name: "Italien 2026", slug: "italien-2026", orderIndex: 0)

        let photoLater = Data([0xff, 0xd8, 0xff, 0x00, 0x10, 0x20])
        let photoEarlier = Data([0x89, 0x50, 0x4e, 0x47, 0x01, 0x02])
        let thumb = Data([1, 2, 3, 4])
        _ = try await AddMedia.addMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, nodeId: memory.id, bytes: photoLater, thumb: thumb, takenAt: "2026-03-15T12:00:00Z", mimeType: "image/jpeg")
        _ = try await AddMedia.addMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, nodeId: memory.id, bytes: photoEarlier, takenAt: "2026-01-01T08:00:00Z", mimeType: "image/png")

        // ordered by takenAt (earliest first); bytes + thumbnail round-trip byte-identical
        let items = try await AddMedia.listMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, nodeId: memory.id)
        XCTAssertEqual(items.map { $0.mimeType }, ["image/png", "image/jpeg"])
        XCTAssertEqual(try AddMedia.decodeBlob(contentKey: ck, item: items[0]), photoEarlier)
        XCTAssertEqual(try AddMedia.decodeBlob(contentKey: ck, item: items[1]), photoLater)
        XCTAssertEqual(try AddMedia.decodeThumb(contentKey: ck, item: items[1]), thumb)
        XCTAssertFalse(AddMedia.hasThumb(items[0]))

        // dedup (AD-09): the same photo is stored; adding it again references the one blob (no re-upload)
        let storedAgain = try await AddMedia.checkMediaBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, dedupToken: AddMedia.dedupTokenFor(contentKey: ck, content: photoEarlier))
        XCTAssertTrue(storedAgain)
        let dup = try await AddMedia.addMedia(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, nodeId: memory.id, bytes: photoEarlier, takenAt: "2026-02-01T00:00:00Z", mimeType: "image/png")
        XCTAssertEqual(try AddMedia.decodeBlob(contentKey: ck, item: dup), photoEarlier)
        let neverStored = try await AddMedia.checkMediaBlob(apiBase: apiBase, session: a, scopeId: scope.scopeId, dedupToken: AddMedia.dedupTokenFor(contentKey: ck, content: Data([9, 9, 9])))
        XCTAssertFalse(neverStored)
    }
}
