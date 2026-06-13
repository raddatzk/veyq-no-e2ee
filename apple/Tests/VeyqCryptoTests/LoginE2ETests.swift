import XCTest
import Foundation
import CryptoKit
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class LoginE2ETests: XCTestCase {
    func testLoginAndUnlock() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"
        let email = "owner@example.com"
        let password = "correct horse battery staple"

        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: email, displayName: "Owner", password: password)

        let session = try await Login.login(apiBase: apiBase, email: email, password: password, secretKeyB64: kit.secretKey)
        XCTAssertFalse(session.sessionToken.isEmpty)
        XCTAssertEqual(session.identity.kemX.count, 32)
        XCTAssertEqual(session.identity.mlkemSeed.count, 64)
        XCTAssertEqual(session.identity.sign.count, 32)

        let me = try await Login.fetchCurrentUser(apiBase: apiBase, sessionToken: session.sessionToken)
        XCTAssertEqual(me.email, email)

        // the recovered private key matches the server's stored public key (X25519 half)
        let recoveredXPub = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: session.identity.kemX).publicKey.rawRepresentation
        XCTAssertEqual(recoveredXPub, Data(session.kemPublicKey.prefix(32)))
    }
}
