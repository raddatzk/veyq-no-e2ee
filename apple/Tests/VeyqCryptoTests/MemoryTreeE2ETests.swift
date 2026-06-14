import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class MemoryTreeE2ETests: XCTestCase {
    func testBuildsEncryptedFolderMemoryChapterTree() async throws {
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
        let chapter = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "CHAPTER", name: "Tag 1", slug: "tag-1", orderIndex: 0, parentId: memory.id)

        XCTAssertNil(folder.parentId)
        XCTAssertEqual(memory.parentId, folder.id)
        XCTAssertEqual(chapter.parentId, memory.id)
        XCTAssertEqual(folder.slugToken, MemoryTree.slugTokenFor(contentKey: ck, slug: "urlaube"))

        // fetch the flat graph and reconstruct names + structure locally
        let nodes = try await MemoryTree.listNodes(apiBase: apiBase, session: a, scopeId: scope.scopeId)
        XCTAssertEqual(nodes.count, 3)
        var byId: [String: MemoryTree.MemoryNode] = [:]
        for n in nodes { byId[n.id] = n }
        XCTAssertEqual(try MemoryTree.decodeNodeName(contentKey: ck, node: byId[folder.id]!), "Urlaube")
        XCTAssertEqual(try MemoryTree.decodeNodeName(contentKey: ck, node: byId[memory.id]!), "Italien 2026")
        XCTAssertEqual(try MemoryTree.decodeNodeName(contentKey: ck, node: byId[chapter.id]!), "Tag 1")

        // invariants enforced server-side: a duplicate sibling slug and a folder-in-a-memory are rejected
        var dupRejected = false
        do { _ = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "FOLDER", name: "Urlaube 2", slug: "urlaube", orderIndex: 1) }
        catch { dupRejected = true }
        XCTAssertTrue(dupRejected, "duplicate sibling slug must be rejected")

        var containmentRejected = false
        do { _ = try await MemoryTree.createNode(apiBase: apiBase, session: a, scopeId: scope.scopeId, contentKey: ck, type: "FOLDER", name: "Nope", slug: "nope", orderIndex: 1, parentId: memory.id) }
        catch { containmentRejected = true }
        XCTAssertTrue(containmentRejected, "a folder inside a memory must be rejected")
    }
}
