import XCTest
import Foundation
@testable import VeyqKit

// Live round-trip against a REAL running instance (cr-0015 phase 4/5 verification). Skipped unless the
// VEYQ_LIVE_BASE env var points at a bootstrapped instance — so the normal `swift test` stays hermetic.
// This is the cross-platform behavioural contract (ADR 0010) exercised for real: the same login → unlock
// the web does, against the same backend, over the real URLSession transport.
//
//   VEYQ_LIVE_BASE=http://localhost:8080 \
//   VEYQ_EMAIL=kevin@veyq.test VEYQ_PASSWORD='…' VEYQ_SECRET='…' \
//   swift test --filter LiveRoundTripTests
final class LiveRoundTripTests: XCTestCase {

    func testLoginThenUnlockAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])

        let client = VeyqClient(instance: VeyqInstance(baseURL: baseURL))

        // The instance is bootstrapped, so setup is not required.
        let setupReq = try await client.setupRequired()
        XCTAssertFalse(setupReq, "instance should already be set up")

        // login → holds a session
        try await client.login(email: email, password: password)
        let isAuth = await client.session.isAuthenticated
        XCTAssertTrue(isAuth, "login should hold a session")

        // unlock → opens the vault (Master Key + identity private on-device)
        try await client.unlock(password: password, secretKey: secret)
        let unlocked = await client.vault.isUnlocked
        let mk = await client.vault.masterKey()
        let id = await client.vault.identityPrivateKey()
        XCTAssertTrue(unlocked, "vault should be unlocked")
        XCTAssertEqual(mk?.count, 32, "Master Key is 32 bytes")
        XCTAssertNotNil(id, "identity private key is held")

        // logout → re-locks
        try await client.logout()
        let stillUnlocked = await client.vault.isUnlocked
        let stillAuth = await client.session.isAuthenticated
        XCTAssertFalse(stillUnlocked)
        XCTAssertFalse(stillAuth)
    }
}
