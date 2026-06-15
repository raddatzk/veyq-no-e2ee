import XCTest
import Foundation
@testable import VeyqCrypto

/// Live (VEYQ_E2E=1): registration persists a RECOVERY wrap, login still works, and the login key bag must
/// EXCLUDE the RECOVERY wrap (it's released only via the email-token-gated reset — email is the 2nd factor).
final class ResetPasswordE2ETests: XCTestCase {
    func testRecoveryWrapNotInLoginKeyBag() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"
        let pass = "correct horse battery staple"

        let (_, kit) = try await SetupServer.registerServerOwner(apiBase: apiBase, email: "reset-owner@example.com", displayName: "Owner", password: pass)
        // login succeeds despite the extra RECOVERY wrap
        let session = try await Login.login(apiBase: apiBase, email: "reset-owner@example.com", password: pass, secretKeyB64: kit.secretKey)
        XCTAssertFalse(session.sessionToken.isEmpty)

        // the login key bag has PASSWORD but not RECOVERY
        var r = URLRequest(url: URL(string: "\(apiBase)/api/login")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["email": "reset-owner@example.com", "password": pass])
        let (data, _) = try await URLSession.shared.data(for: r)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let bag = try XCTUnwrap(json["keyBag"] as? [String: Any])
        let wraps = try XCTUnwrap(bag["masterKeyWraps"] as? [[String: Any]])
        let methods = wraps.compactMap { $0["unlockMethod"] as? String }
        XCTAssertTrue(methods.contains("PASSWORD"))
        XCTAssertFalse(methods.contains("RECOVERY"))
    }
}
