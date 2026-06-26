import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// uc.account.login (apple) data-layer tests. No live server: a `StubTransport` answers the two login
// endpoints with canned JSON and records what the client sent. The headline assertion is that the
// signature the client produced actually **verifies** against the keypair derived from the same
// password + instance salt — i.e. the challenge → deriveAuthSalt → deriveAuthKeypair → authSign chain
// is wired correctly end-to-end, the operator-blind guarantee (ADR 0003) holding without a backend.
final class LoginTests: XCTestCase {

    // Fixed server-side material the stub hands out. Real 16-byte salt + 32-byte nonce.
    private static let instanceSalt = Data((0..<16).map { UInt8($0) })
    private static let nonce = Data((0..<32).map { UInt8(0xA0 &+ $0) })
    private static let email = "kevin@example.com"
    private static let password = "correct horse battery staple"
    private static let familyId = "7c3f0e2a-9b1d-4a6e-8b2c-1f2d3e4a5b6c"

    func testLoginDerivesSignsAndHoldsSession() async throws {
        let stub = StubTransport(
            instanceSalt: Self.instanceSalt,
            nonce: Self.nonce,
            accessToken: "access-1",
            refreshToken: "refresh-1",
            familyId: Self.familyId
        )
        let client = VeyqClient(
            instance: VeyqInstance(baseURL: URL(string: "https://veyq.example")!),
            transport: stub
        )

        try await client.login(email: Self.email, password: Self.password)

        // The session is held with exactly the tokens the server issued.
        let session = client.session
        let isAuth = await session.isAuthenticated
        let access = await session.accessToken
        let refresh = await session.refreshToken
        let family = await session.familyId
        XCTAssertTrue(isAuth)
        XCTAssertEqual(access, "access-1")
        XCTAssertEqual(refresh, "refresh-1")
        XCTAssertEqual(family, Self.familyId)

        // The headline check: the signature the client sent verifies against the keypair derived from
        // the same password + instance salt over the issued nonce.
        let sent = try XCTUnwrap(stub.recordedSignature)
        let authSalt = V1.deriveAuthSalt(instanceSalt: Self.instanceSalt, email: Self.email)
        let keypair = V1.deriveAuthKeypair(password: Self.password, authSalt: authSalt)
        XCTAssertTrue(V1.authVerify(publicKey: keypair.publicKey, message: Self.nonce, signature: sent))

        // The verify request echoed the same email.
        XCTAssertEqual(stub.recordedEmail, Self.email)
    }

    func testRefreshRotatesTokens() async throws {
        let stub = StubTransport(
            instanceSalt: Self.instanceSalt,
            nonce: Self.nonce,
            accessToken: "access-1",
            refreshToken: "refresh-1",
            familyId: Self.familyId
        )
        let client = VeyqClient(
            instance: VeyqInstance(baseURL: URL(string: "https://veyq.example")!),
            transport: stub
        )
        try await client.login(email: Self.email, password: Self.password)

        // The stub rotates to access-2/refresh-2 on the refresh endpoint.
        try await client.refresh()
        let access = await client.session.accessToken
        let refresh = await client.session.refreshToken
        let family = await client.session.familyId
        XCTAssertEqual(access, "access-2")
        XCTAssertEqual(refresh, "refresh-2")
        XCTAssertEqual(family, Self.familyId, "familyId is set at login and kept across refreshes")
    }

    func testRefreshWithoutSessionThrows() async throws {
        let stub = StubTransport(instanceSalt: Self.instanceSalt, nonce: Self.nonce,
                                 accessToken: "x", refreshToken: "y", familyId: Self.familyId)
        let client = VeyqClient(
            instance: VeyqInstance(baseURL: URL(string: "https://veyq.example")!),
            transport: stub
        )
        do {
            try await client.refresh()
            XCTFail("expected noSession")
        } catch VeyqError.noSession {
            // expected
        }
    }

    func testWithRefreshRotatesOnSessionExpiredThenRetries() async throws {
        let stub = StubTransport(instanceSalt: Self.instanceSalt, nonce: Self.nonce,
                                 accessToken: "access-1", refreshToken: "refresh-1", familyId: Self.familyId)
        let client = VeyqClient(
            instance: VeyqInstance(baseURL: URL(string: "https://veyq.example")!),
            transport: stub
        )
        try await client.login(email: Self.email, password: Self.password)

        // A resource op that 401s the first time, then succeeds — withRefresh must rotate and retry.
        let attempts = Counter()
        let result = try await client.withRefresh { () async throws -> String in
            let n = await attempts.next()
            if n == 1 { throw VeyqError.sessionExpired }
            return "ok-on-attempt-\(n)"
        }
        XCTAssertEqual(result, "ok-on-attempt-2")
        let access = await client.session.accessToken
        XCTAssertEqual(access, "access-2", "the 401 triggered a refresh before the retry")
    }
}

/// Serial attempt counter for the withRefresh test.
private actor Counter {
    private var n = 0
    func next() -> Int { n += 1; return n }
}

/// A `ClientTransport` that answers the login + token endpoints with canned JSON and records the
/// signature/email the client sent. It does not verify anything — the *test* verifies the recorded
/// signature against the independently-derived keypair, which is what makes this a real proof.
private final class StubTransport: ClientTransport, @unchecked Sendable {
    let instanceSalt: Data
    let nonce: Data
    let accessToken: String
    let refreshToken: String
    let familyId: String

    private let lock = NSLock()
    private var _recordedSignature: Data?
    private var _recordedEmail: String?
    private var refreshCount = 0

    var recordedSignature: Data? { lock.withLock { _recordedSignature } }
    var recordedEmail: String? { lock.withLock { _recordedEmail } }

    init(instanceSalt: Data, nonce: Data, accessToken: String, refreshToken: String, familyId: String) {
        self.instanceSalt = instanceSalt
        self.nonce = nonce
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.familyId = familyId
    }

    func send(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String
    ) async throws -> (HTTPResponse, HTTPBody?) {
        let path = request.path ?? ""
        let payload = try await Self.collect(body)

        switch path {
        case "/api/login/challenge":
            let json = ["nonce": nonce.base64EncodedString(),
                        "instanceSalt": instanceSalt.base64EncodedString()]
            return Self.jsonResponse(status: 200, object: json)

        case "/api/login/verify":
            if let obj = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] {
                lock.withLock {
                    _recordedEmail = obj["email"] as? String
                    if let sig = obj["signature"] as? String { _recordedSignature = Data(base64Encoded: sig) }
                }
            }
            let json: [String: Any] = [
                "accessToken": accessToken,
                "refreshToken": refreshToken,
                "family": ["id": familyId],
            ]
            return Self.jsonResponse(status: 200, object: json)

        case "/api/token/refresh":
            let n = lock.withLock { () -> Int in refreshCount += 1; return refreshCount }
            let json = ["accessToken": "access-\(n + 1)", "refreshToken": "refresh-\(n + 1)"]
            return Self.jsonResponse(status: 200, object: json)

        case "/api/logout":
            return (HTTPResponse(status: .noContent), nil)

        default:
            return (HTTPResponse(status: .notFound), nil)
        }
    }

    private static func collect(_ body: HTTPBody?) async throws -> Data {
        guard let body else { return Data() }
        var data = Data()
        for try await chunk in body { data.append(contentsOf: chunk) }
        return data
    }

    private static func jsonResponse(status: Int, object: Any) -> (HTTPResponse, HTTPBody?) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        var response = HTTPResponse(status: .init(code: status))
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(data))
    }
}
