import XCTest
@testable import VeyqKit

/// The device-security store round-trip, on the in-memory double (the real Keychain + biometric gate are
/// covered by the live in-app UI round-trip). Covers the **data-layer view** (ADR 0011): ungated meta +
/// the L1 factor; the L2/L3 random key `K` is app-layer and not on this protocol.
final class DeviceSecurityStoreTests: XCTestCase {
    private let email = "person@veyq.example"

    func testCommitL1CurrentLevelClearRoundTrip() throws {
        let store = InMemoryDeviceSecurityStore()
        XCTAssertNil(store.currentLevel(email: email))
        XCTAssertNil(store.wrapId(email: email))

        _ = try store.beginEnrol(email: email)
        try store.commitOnlyPassword(wrapId: "wrap-123", email: email)
        XCTAssertEqual(store.currentLevel(email: email), .onlyPassword)
        XCTAssertEqual(store.wrapId(email: email), "wrap-123")
        XCTAssertNotNil(store.deviceFactor(email: email))

        try store.clear(email: email)
        XCTAssertNil(store.currentLevel(email: email))
        XCTAssertNil(store.wrapId(email: email))
    }

    func testCommitReplacesExistingLevel() throws {
        let store = InMemoryDeviceSecurityStore()
        _ = try store.beginEnrol(email: email)
        try store.commitOnlyPassword(wrapId: "wrap-old", email: email)
        try store.commitPasswordless(level: .biometric, wrapId: "wrap-new", refreshToken: "sealed-xyz", email: email)
        XCTAssertEqual(store.currentLevel(email: email), .biometric)
        XCTAssertEqual(store.wrapId(email: email), "wrap-new")
        XCTAssertEqual(store.refreshToken(email: email), "sealed-xyz")
    }

    func testPasswordlessCommitCarriesWrapAndToken() throws {
        let store = InMemoryDeviceSecurityStore()
        try store.commitPasswordless(level: .noLogin, wrapId: "w", refreshToken: "rt", email: email)
        XCTAssertEqual(store.wrapId(email: email), "w")
        XCTAssertEqual(store.refreshToken(email: email), "rt")
        XCTAssertEqual(store.currentLevel(email: email), .noLogin)
    }

    func testUpdateRefreshTokenTracksRotation() throws {
        let store = InMemoryDeviceSecurityStore()
        try store.commitPasswordless(level: .noLogin, wrapId: "w", refreshToken: "rt-1", email: email)
        try store.updateRefreshToken("rt-2", email: email)
        XCTAssertEqual(store.refreshToken(email: email), "rt-2")
    }

    func testBeginEnrolReturnsAStableFactor() throws {
        let store = InMemoryDeviceSecurityStore()
        let first = try store.beginEnrol(email: email)
        let second = try store.beginEnrol(email: email)
        XCTAssertEqual(first, second, "a device-bound factor never changes")
        XCTAssertEqual(store.deviceFactor(email: email), first)
    }

    func testRecordsAreScopedPerAccount() throws {
        let store = InMemoryDeviceSecurityStore()
        try store.commitOnlyPassword(wrapId: "a", email: "a@veyq.example")
        XCTAssertEqual(store.currentLevel(email: "a@veyq.example"), .onlyPassword)
        XCTAssertNil(store.currentLevel(email: "b@veyq.example"))
    }

    func testClearIsIdempotentWhenAbsent() throws {
        let store = InMemoryDeviceSecurityStore()
        XCTAssertNoThrow(try store.clear(email: "nobody@veyq.example"))
    }

    func testMakeDeviceFactorIs32RandomBytes() {
        let a = makeDeviceFactor(), b = makeDeviceFactor()
        XCTAssertEqual(a.count, 32)
        XCTAssertEqual(b.count, 32)
        XCTAssertNotEqual(a, b)
    }
}
