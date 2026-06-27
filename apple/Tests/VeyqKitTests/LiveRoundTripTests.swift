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

    /// cr-0019 — the DEVICE-wrap trust round-trip against the **live** wraps endpoints. Uses an in-memory
    /// `DeviceTrustStore` (the real Keychain is entitlement-gated from the SPM CLI; the in-app UI round-trip
    /// exercises it). Proves: floor unlock → `trustThisDevice` (POST /api/account/wraps) → a fresh client
    /// opens with only the password via the served DEVICE wrap → `untrustThisDevice` (DELETE) cleans up.
    func testTrustDeviceRoundTripAgainstLiveInstance() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let base = env["VEYQ_LIVE_BASE"], let baseURL = URL(string: base) else {
            throw XCTSkip("set VEYQ_LIVE_BASE (+ VEYQ_EMAIL/PASSWORD/SECRET) to run the live round-trip")
        }
        let email = try XCTUnwrap(env["VEYQ_EMAIL"])
        let password = try XCTUnwrap(env["VEYQ_PASSWORD"])
        let secret = try XCTUnwrap(env["VEYQ_SECRET"])
        let store = InMemoryDeviceTrustStore()

        // 1 — floor unlock, then trust this device (enrol a DEVICE wrap server-side).
        let c1 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceTrust: store)
        try await c1.login(email: email, password: password)
        try await c1.unlock(password: password, secretKey: secret)
        try await c1.trustThisDevice(email: email, password: password)
        XCTAssertTrue(store.isTrusted(email: email), "a DEVICE wrap + local record now exist")

        // 2 — a fresh client (new vault), same store: unlock with ONLY the password (no Secret Key).
        let c2 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceTrust: store)
        try await c2.login(email: email, password: password)
        let opened = try await c2.unlockWithDevice(email: email, password: password)
        XCTAssertTrue(opened, "the trusted device opens with only the password")
        let mk = await c2.vault.masterKey()
        XCTAssertEqual(mk?.count, 32, "the Master Key opened from the DEVICE wrap")

        // 3 — wrong password on the trusted device fails generically (AEAD tag), vault stays locked.
        let c3 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceTrust: store)
        try await c3.login(email: email, password: password)
        do {
            _ = try await c3.unlockWithDevice(email: email, password: "definitely wrong")
            XCTFail("expected unlockFailed")
        } catch VeyqError.unlockFailed { /* expected */ }

        // 4 — untrust: delete the wrap server-side + drop the local record (cleanup, leaves the owner clean).
        try await c2.untrustThisDevice(email: email)
        XCTAssertFalse(store.isTrusted(email: email))
        let c4 = VeyqClient(instance: VeyqInstance(baseURL: baseURL), deviceTrust: store)
        try await c4.login(email: email, password: password)
        let openedAfterUntrust = try await c4.unlockWithDevice(email: email, password: password)
        XCTAssertFalse(openedAfterUntrust, "after untrust the device path is unavailable (no record)")
    }
}
