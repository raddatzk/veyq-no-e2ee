import XCTest
import Foundation
@testable import VeyqCrypto

/// Real end-to-end against a running backend; opt-in via VEYQ_E2E=1 so plain `swift test` skips it.
final class SetupServerE2ETests: XCTestCase {
    func testCreatesFirstUser() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["VEYQ_E2E"] == "1", "set VEYQ_E2E=1 to run against a live backend")
        let apiBase = env["VEYQ_API_BASE"] ?? "http://localhost:8080"

        let (s0, _) = try await URLSession.shared.data(from: URL(string: "\(apiBase)/api/setup")!)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: s0) as! [String: Any])["setupRequired"] as? Bool, true)

        let (user, kit) = try await SetupServer.registerServerOwner(
            apiBase: apiBase, email: "owner@example.com", displayName: "Owner", password: "correct horse battery staple")
        XCTAssertEqual(user.email, "owner@example.com")
        XCTAssertEqual(user.serverRole, "ServerOwner")
        XCTAssertFalse(kit.secretKey.isEmpty)

        let (s1, _) = try await URLSession.shared.data(from: URL(string: "\(apiBase)/api/setup")!)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: s1) as! [String: Any])["setupRequired"] as? Bool, false)
    }
}
