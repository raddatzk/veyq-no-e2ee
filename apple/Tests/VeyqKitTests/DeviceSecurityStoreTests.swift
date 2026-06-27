import XCTest
@testable import VeyqKit

/// The device-security store round-trip, on the in-memory double (the real Keychain + biometric gate are
/// covered by the live in-app UI round-trip). Covers L1 (cr-0019) and the L2/L3 record shape (cr-0020).
final class DeviceSecurityStoreTests: XCTestCase {
    private let email = "person@veyq.example"

    func testSaveLoadCurrentLevelClearRoundTrip() throws {
        let store = InMemoryDeviceSecurityStore()
        let record = DeviceSecurityRecord(level: .onlyPassword, wrapId: "wrap-123", secret: makeDeviceFactor())

        XCTAssertNil(store.currentLevel(email: email))
        XCTAssertNil(try store.load(email: email))

        try store.save(record, email: email)
        XCTAssertEqual(store.currentLevel(email: email), .onlyPassword)
        XCTAssertEqual(try store.load(email: email), record)

        try store.clear(email: email)
        XCTAssertNil(store.currentLevel(email: email))
        XCTAssertNil(try store.load(email: email))
    }

    func testSaveReplacesExistingRecord() throws {
        let store = InMemoryDeviceSecurityStore()
        let first = DeviceSecurityRecord(level: .onlyPassword, wrapId: "wrap-old", secret: makeDeviceFactor())
        let second = DeviceSecurityRecord(level: .biometric, wrapId: "wrap-new", secret: secureRandomBytes(32),
                                          refreshToken: "refresh-xyz")
        try store.save(first, email: email)
        try store.save(second, email: email)
        XCTAssertEqual(try store.load(email: email), second)
        XCTAssertEqual(store.currentLevel(email: email), .biometric)
    }

    func testPasswordlessRecordCarriesKeyAndToken() throws {
        let store = InMemoryDeviceSecurityStore()
        let k = secureRandomBytes(32)
        let record = DeviceSecurityRecord(level: .noLogin, wrapId: "w", secret: k, refreshToken: "rt")
        try store.save(record, email: email)
        let loaded = try XCTUnwrap(try store.load(email: email))
        XCTAssertEqual(loaded.secret, k)
        XCTAssertEqual(loaded.refreshToken, "rt")
        XCTAssertEqual(loaded.level, .noLogin)
    }

    func testRecordsAreScopedPerAccount() throws {
        let store = InMemoryDeviceSecurityStore()
        try store.save(DeviceSecurityRecord(level: .onlyPassword, wrapId: "a", secret: makeDeviceFactor()),
                       email: "a@veyq.example")
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
