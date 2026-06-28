import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// cr-0027 phase 5 — en.memory.tree (apple) data-layer tests. A stateful `NodesStub` serves
// /api/scopes/{id}/nodes: POST captures the ciphertext a create sends and serves it back in the list,
// so listTree round-trips the name (sealed under K_SC) and openMemory recovers the per-Memory K_mem
// with the real native v1 crypto. Operator-blind: only ciphertext (encryptedName / wrappedContentKey /
// slugToken) crosses the stub — the plaintext name and the content key never do.
final class ContentTreeTests: XCTestCase {

    private static let baseURL = URL(string: "https://fam.example.com")!
    private static let scopeId = "11111111-1111-1111-1111-111111111111"
    private let scopeKey = Data(repeating: 9, count: 32)

    private func client(_ stub: NodesStub) async -> VeyqClient {
        let session = Session()
        await session.set(access: "a", refresh: "r", familyId: "f", account: "p@veyq.example")
        return VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL),
                          session: session, deviceSecurity: InMemoryDeviceSecurityStore(), transport: stub)
    }

    func testCreateListDecryptRoundTrip() async throws {
        let stub = NodesStub()
        let c = await client(stub)

        try await c.createFolder(scopeId: Self.scopeId, scopeKey: scopeKey, name: "Trips")
        let (memId, memKey) = try await c.createMemory(scopeId: Self.scopeId, scopeKey: scopeKey, name: "Italien 2026")
        XCTAssertEqual(memKey.count, 32)

        let tree = try await c.listTree(scopeId: Self.scopeId, scopeKey: scopeKey)
        XCTAssertEqual(tree.count, 2)

        let folder = try XCTUnwrap(tree.first { $0.kind == .FOLDER })
        XCTAssertEqual(folder.name, "Trips")                  // name opened under K_SC
        XCTAssertNil(folder.wrappedContentKey)                // a folder carries no content key

        let memory = try XCTUnwrap(tree.first { $0.kind == .MEMORY })
        XCTAssertEqual(memory.id, memId)
        XCTAssertEqual(memory.name, "Italien 2026")
        // the wrapped K_mem opens back to exactly the key createMemory returned (end-to-end)
        XCTAssertEqual(try c.openMemory(scopeKey: scopeKey, node: memory), memKey)

        // operator-blind: the stub only ever saw ciphertext + a blinded slug token over the root parent slot
        let raw = try XCTUnwrap(stub.rawCreate(kind: "MEMORY"))
        XCTAssertNotEqual(Data(base64Encoded: raw["encryptedName"] as! String), Data("Italien 2026".utf8))
        XCTAssertEqual(raw["slugToken"] as? String,
                       V1.slugToken(contentKey: scopeKey, parentSlot: Self.scopeId, slug: "italien-2026").base64EncodedString())
    }

    func testRenameRecomputesSlugThenDelete() async throws {
        let stub = NodesStub()
        let c = await client(stub)
        try await c.createFolder(scopeId: Self.scopeId, scopeKey: scopeKey, name: "Trips")
        let created = try await c.listTree(scopeId: Self.scopeId, scopeKey: scopeKey)
        var node = try XCTUnwrap(created.first)

        try await c.renameNode(scopeId: Self.scopeId, scopeKey: scopeKey, node: node, newName: "Reisen")
        let renamed = try await c.listTree(scopeId: Self.scopeId, scopeKey: scopeKey)
        node = try XCTUnwrap(renamed.first)
        XCTAssertEqual(node.name, "Reisen")
        XCTAssertEqual(stub.lastSlug,
                       V1.slugToken(contentKey: scopeKey, parentSlot: Self.scopeId, slug: "reisen").base64EncodedString())

        try await c.deleteNode(scopeId: Self.scopeId, nodeId: node.id)
        let after = try await c.listTree(scopeId: Self.scopeId, scopeKey: scopeKey)
        XCTAssertTrue(after.isEmpty)
    }
}

/// A stateful `ClientTransport` for /api/scopes/{id}/nodes: POST appends a node (id assigned), GET lists,
/// PATCH mutates encryptedName/slugToken, DELETE removes. Stores only what the client sends — ciphertext.
private final class NodesStub: ClientTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var nodes: [[String: Any]] = []
    private var seq = 0
    private var _creates: [[String: Any]] = []
    private(set) var lastSlug: String?

    func rawCreate(kind: String) -> [String: Any]? { lock.withLock { _creates.first { $0["kind"] as? String == kind } } }

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String)
        async throws -> (HTTPResponse, HTTPBody?)
    {
        let path = request.path ?? ""
        let bodyData = try await Data(collecting: body ?? HTTPBody(), upTo: 1_000_000)
        let obj = (try? JSONSerialization.jsonObject(with: bodyData)) as? [String: Any] ?? [:]

        if path.hasSuffix("/nodes"), request.method == .get {
            return Self.json(200, lock.withLock { nodes })
        }
        if path.hasSuffix("/nodes"), request.method == .post {
            let id: String = lock.withLock {
                seq += 1
                let nid = "node-\(seq)"
                var row = obj
                row["id"] = nid
                row["orderIndex"] = obj["orderIndex"] ?? 0
                nodes.append(row)
                _creates.append(obj)
                return nid
            }
            return Self.json(201, ["nodeId": id])
        }
        // /nodes/{nodeId}
        let nodeId = String(path.split(separator: "/").last ?? "")
        if request.method == .patch {
            lock.withLock {
                lastSlug = obj["slugToken"] as? String
                if let i = nodes.firstIndex(where: { $0["id"] as? String == nodeId }) {
                    if let n = obj["encryptedName"] { nodes[i]["encryptedName"] = n }
                    if let s = obj["slugToken"] { nodes[i]["slugToken"] = s }
                }
            }
            return (HTTPResponse(status: .noContent), nil)
        }
        if request.method == .delete {
            lock.withLock { nodes.removeAll { $0["id"] as? String == nodeId } }
            return (HTTPResponse(status: .noContent), nil)
        }
        return (HTTPResponse(status: .notFound), nil)
    }

    private static func json(_ status: Int, _ object: Any) -> (HTTPResponse, HTTPBody?) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        var response = HTTPResponse(status: .init(code: status))
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(data))
    }
}
