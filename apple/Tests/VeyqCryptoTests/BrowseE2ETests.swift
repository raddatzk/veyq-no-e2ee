import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class BrowseE2ETests: XCTestCase {
    func testResolvesACleanSlugPathClientSide() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let pass = "correct horse battery staple"
        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "admin@example.com", displayName: "Admin", password: pass)
        let a = try await Login.login(apiBase: apiBase, email: "admin@example.com", password: pass, secretKeyB64: kit.secretKey)
        let scope = try await ScopeContent.openPersonalScope(apiBase: apiBase, session: a)
        let ck = try await ScopeContent.getOrCreateContentKey(apiBase: apiBase, session: a, scope: scope)

        let folder = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "FOLDER", name: "Urlaube", slug: "urlaube", orderIndex: 0)
        let memory = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "MEMORY", name: "Italien 2026", slug: "italien-2026", orderIndex: 0, parentId: folder.id)
        _ = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "CHAPTER", name: "Tag 1", slug: "tag-1", orderIndex: 0, parentId: memory.id)
        _ = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "FOLDER", name: "Arbeit", slug: "arbeit", orderIndex: 1)

        // level listing: the root has two folders; the folder has one memory
        let roots = try await Browse.listChildren(apiBase: apiBase, session: a, scopeId: scope.scopeId)
        XCTAssertEqual(roots.count, 2)
        let folderChildren = try await Browse.listChildren(apiBase: apiBase, session: a, scopeId: scope.scopeId, parentId: folder.id)
        XCTAssertEqual(folderChildren.count, 1)

        // resolve the clean path — each resolved node knows its own type
        let chain = try await Browse.resolvePath(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, segments: ["urlaube", "italien-2026", "tag-1"])
        XCTAssertEqual(chain.map { $0.type }, ["FOLDER", "MEMORY", "CHAPTER"])
        XCTAssertEqual(try chain.map { try MemoryTree.decodeNode(contentKey: ck, node: $0).name }, ["Urlaube", "Italien 2026", "Tag 1"])

        // a non-existent segment fails
        var resolveFailed = false
        do { _ = try await Browse.resolvePath(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, segments: ["urlaube", "does-not-exist"]) }
        catch { resolveFailed = true }
        XCTAssertTrue(resolveFailed, "a non-existent path segment must fail to resolve")
    }
}
