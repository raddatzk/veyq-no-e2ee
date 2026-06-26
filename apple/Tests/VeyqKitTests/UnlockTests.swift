import XCTest
import Foundation
import HTTPTypes
import OpenAPIRuntime
import VeyqCrypto
@testable import VeyqKit

// uc.account.unlock (apple) data-layer tests. No live server: an `UnlockStub` serves key material that the
// test itself builds with the *same* V1 primitives (kekPasswordSecret → symWrap), then asserts unlock
// recovers exactly the original Master Key + identity private bytes. That round-trip proves the
// orchestration wiring (fetch → decodeKdfParams → kekPasswordSecret → symUnwrap master → symUnwrap
// identity) — operator-blind (only ciphertext fetched; password/Secret Key/KEK never sent), ADR 0003/0005.
final class UnlockTests: XCTestCase {

    private static let masterKey = Data((0..<32).map { UInt8($0) })
    private static let identityPriv = Data((0..<80).map { UInt8(0x10 &+ $0) })
    private static let secretKey = Data((0..<19).map { UInt8(0x40 &+ $0) })
    private static let password = "open sesame"
    private static let salt = Data((0..<16).map { UInt8(0xE0 &+ $0) })
    // Light Argon params (well above libsodium's argon2id minimums) so the test stays fast.
    private static let params = V1.ArgonParams(t: 3, m: 64, p: 1, dkLen: 32)
    private static let baseURL = URL(string: "https://veyq.example")!

    /// Build the wrapped key material the server would hand back: Master Key wrapped under the
    /// password+Secret-Key KEK, identity private wrapped under the Master Key.
    private static func keyMaterialJSON(method: String = "PASSWORD") -> [String: Any] {
        let kek = V1.kekPasswordSecret(secretKey: secretKey, password: password, salt: salt, params: params)
        let nonce1 = Data((0..<12).map { UInt8(0x01 &+ $0) })
        let nonce2 = Data((0..<12).map { UInt8(0x20 &+ $0) })
        let wrappedKey = V1.symWrap(kek: kek, targetKey: masterKey, context: "master-key", nonce: nonce1)
        let wrappedIdentity = V1.symWrap(kek: masterKey, targetKey: identityPriv, context: "identity-priv", nonce: nonce2)
        let kdfParams = V1.encodeKdfParams(params, salt: salt)
        return [
            "suiteId": "v1",
            "wraps": [[
                "id": "w1",
                "method": method,
                "wrappedKey": wrappedKey.base64EncodedString(),
                "kdfParams": kdfParams.base64EncodedString(),
            ]],
            "wrappedIdentityPrivateKey": wrappedIdentity.base64EncodedString(),
        ]
    }

    func testUnlockOpensVaultWithMasterAndIdentityKeys() async throws {
        let stub = UnlockStub(keyMaterial: Self.keyMaterialJSON())
        let client = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), transport: stub)

        try await client.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())

        let unlocked = await client.vault.isUnlocked
        let mk = await client.vault.masterKey()
        let id = await client.vault.identityPrivateKey()
        XCTAssertTrue(unlocked)
        XCTAssertEqual(mk, Self.masterKey, "the unwrapped Master Key matches the original")
        XCTAssertEqual(id, Self.identityPriv, "the Master-Key-unwrapped identity private matches the original")
    }

    func testWrongSecretKeyThrowsUnlockFailedAndLeavesVaultLocked() async throws {
        let stub = UnlockStub(keyMaterial: Self.keyMaterialJSON())
        let client = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), transport: stub)
        let wrong = Data((0..<19).map { UInt8(0x99 &+ $0) }).base64EncodedString()

        do {
            try await client.unlock(password: Self.password, secretKey: wrong)
            XCTFail("expected unlockFailed")
        } catch VeyqError.unlockFailed {
            // expected — AEAD tag mismatch
        }
        let unlocked = await client.vault.isUnlocked
        XCTAssertFalse(unlocked)
    }

    func testNoPasswordWrapThrowsUnlockUnavailable() async throws {
        let stub = UnlockStub(keyMaterial: Self.keyMaterialJSON(method: "DEVICE"))
        let client = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), transport: stub)
        do {
            try await client.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())
            XCTFail("expected unlockUnavailable")
        } catch VeyqError.unlockUnavailable {
            // expected — only a DEVICE wrap, no PASSWORD floor
        }
    }

    func testKeyMaterial401RefreshesThenUnlocks() async throws {
        let stub = UnlockStub(keyMaterial: Self.keyMaterialJSON(), failKeyMaterialOnce: true)
        let session = Session()
        await session.set(access: "access-1", refresh: "refresh-1", familyId: "fam")
        let client = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), session: session, transport: stub)

        try await client.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())

        let unlocked = await client.vault.isUnlocked
        let access = await client.session.accessToken
        XCTAssertTrue(unlocked, "the 401 was rotated and the retry unlocked")
        XCTAssertEqual(access, "access-2", "withRefresh rotated the session before the retry")
    }

    func testLogoutClearsVault() async throws {
        let stub = UnlockStub(keyMaterial: Self.keyMaterialJSON())
        let client = VeyqClient(instance: VeyqInstance(baseURL: Self.baseURL), transport: stub)
        try await client.unlock(password: Self.password, secretKey: Self.secretKey.base64EncodedString())

        try await client.logout()
        let unlocked = await client.vault.isUnlocked
        let mk = await client.vault.masterKey()
        XCTAssertFalse(unlocked)
        XCTAssertNil(mk)
    }
}

/// A `ClientTransport` serving the account key-material + token endpoints from canned data.
private final class UnlockStub: ClientTransport, @unchecked Sendable {
    let keyMaterial: [String: Any]
    let failKeyMaterialOnce: Bool
    private let lock = NSLock()
    private var keyMaterialCalls = 0

    init(keyMaterial: [String: Any], failKeyMaterialOnce: Bool = false) {
        self.keyMaterial = keyMaterial
        self.failKeyMaterialOnce = failKeyMaterialOnce
    }

    func send(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String
    ) async throws -> (HTTPResponse, HTTPBody?) {
        switch request.path ?? "" {
        case "/api/account/key-material":
            let n = lock.withLock { () -> Int in keyMaterialCalls += 1; return keyMaterialCalls }
            if failKeyMaterialOnce && n == 1 {
                return Self.jsonResponse(status: 401, object: ["code": "authentication_failed"])
            }
            return Self.jsonResponse(status: 200, object: keyMaterial)

        case "/api/token/refresh":
            return Self.jsonResponse(status: 200, object: ["accessToken": "access-2", "refreshToken": "refresh-2"])

        case "/api/logout":
            return (HTTPResponse(status: .noContent), nil)

        default:
            return (HTTPResponse(status: .notFound), nil)
        }
    }

    private static func jsonResponse(status: Int, object: Any) -> (HTTPResponse, HTTPBody?) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        var response = HTTPResponse(status: .init(code: status))
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(data))
    }
}
