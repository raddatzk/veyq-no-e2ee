import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// cr-0026 phase 6 — uc.spaces.personal-scope (apple) data-layer tests. A stateful `ScopesStub` serves
// /api/scopes: it captures the ciphertext grant a `ensurePersonalScope` POSTs and serves it back in
// `listMyScopes`, so `openPersonalScope` recovers the scope key end-to-end with the real hybrid KEM
// (asymWrap → asymUnwrap, cr-0025). Operator-blind: only the ciphertext grant crosses the stub.
final class ScopesTests: XCTestCase {

    private static let baseURL = URL(string: "https://fam.example.com")!
    private static let email = "person@veyq.example"
    private static let masterKey = Data(repeating: 7, count: 32)

    /// A client whose vault already holds a REAL identity bundle (we bypass the floor — the identity is what
    /// seals/opens a grant). Returns the client, the stub, and the identity for independent verification.
    private func unlockedClient(stub: ScopesStub) async -> (VeyqClient, V1.Identity) {
        let id = V1.generateIdentity()
        let session = Session()
        await session.set(access: "access-1", refresh: "refresh-1", familyId: "fam", account: Self.email)
        let c = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL),
                           session: session, deviceSecurity: InMemoryDeviceSecurityStore(), transport: stub)
        await c.vault.open(masterKey: Self.masterKey, identityPrivate: V1.serializeIdentityPrivate(id.priv))
        return (c, id)
    }

    func testEnsureBirthsThenOpenRecoversTheScopeKey() async throws {
        let stub = ScopesStub(initial: [Self.tenantScope()])
        let (c, id) = await unlockedClient(stub: stub)

        let created = try await c.ensurePersonalScope()
        XCTAssertTrue(created, "a personal scope was born")
        XCTAssertTrue(stub.hasKind("PERSONAL"), "the server stored a PERSONAL scope")

        // openPersonalScope asymUnwraps our own grant back to the 32-byte scope key…
        let opened = try await c.openPersonalScope()
        XCTAssertEqual(opened?.count, 32)
        // …and it is exactly the key sealed inside the captured grant (end-to-end with the real KEM).
        let manual = try V1.asymUnwrap(sk: V1.hybridSecret(id.priv), pk: V1.hybridPublic(id.priv),
                                       blob: try XCTUnwrap(stub.lastGrant), context: "scope-grant")
        XCTAssertEqual(opened, manual)
    }

    func testEnsureIsIdempotentWhenPersonalExists() async throws {
        let stub = ScopesStub(initial: [Self.tenantScope(), Self.personalScope()])
        let (c, _) = await unlockedClient(stub: stub)
        let created = try await c.ensurePersonalScope()
        XCTAssertFalse(created, "no second personal scope is born")
        XCTAssertFalse(stub.sawPost, "no POST when a personal scope already exists")
    }

    func testOpenReturnsNilWhenNoPersonal() async throws {
        let stub = ScopesStub(initial: [Self.tenantScope()])
        let (c, _) = await unlockedClient(stub: stub)
        let opened = try await c.openPersonalScope()
        XCTAssertNil(opened)
    }

    // --- fixtures ---

    private static func tenantScope() -> [String: Any] {
        ["id": "tenant-1", "kind": "TENANT", "tenantId": "tenant-1", "wrappedGrant": "AQ==", "role": "OWNER", "nameEpoch": 0, "currentEpoch": 0, "grantedEpoch": 0]
    }
    private static func personalScope() -> [String: Any] {
        ["id": "personal-1", "kind": "PERSONAL", "tenantId": "tenant-1", "wrappedGrant": "AQ==", "role": "OWNER", "nameEpoch": 0, "currentEpoch": 0, "grantedEpoch": 0]
    }
}

/// A stateful `ClientTransport` for /api/scopes: GET returns the scope list; POST captures the ciphertext
/// grant, appends a PERSONAL scope serving it back, and returns 201.
private final class ScopesStub: ClientTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var scopes: [[String: Any]]
    private(set) var sawPost = false
    private var _lastGrant: Data?

    init(initial: [[String: Any]]) { self.scopes = initial }

    var lastGrant: Data? { lock.withLock { _lastGrant } }
    func hasKind(_ kind: String) -> Bool { lock.withLock { scopes.contains { $0["kind"] as? String == kind } } }

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String)
        async throws -> (HTTPResponse, HTTPBody?)
    {
        let path = request.path ?? ""
        if path == "/api/scopes", request.method == .get {
            let list = lock.withLock { scopes }
            return Self.json(200, list)
        }
        if path == "/api/scopes", request.method == .post {
            let data = try await Data(collecting: body ?? HTTPBody(), upTo: 1_000_000)
            let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
            let grantB64 = obj["wrappedGrant"] as? String ?? ""
            lock.withLock {
                sawPost = true
                _lastGrant = Data(base64Encoded: grantB64)
                scopes.append(["id": "personal-new", "kind": "PERSONAL", "tenantId": "tenant-1", "wrappedGrant": grantB64, "role": "OWNER", "nameEpoch": 0, "currentEpoch": 0, "grantedEpoch": 0])
            }
            return Self.json(201, ["scopeId": "personal-new", "kind": "PERSONAL"])
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
