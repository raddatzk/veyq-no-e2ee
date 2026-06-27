import XCTest
@testable import VeyqKit

/// cr-0019 phase 1 — the device-trust store round-trip, on the in-memory double (the real Keychain is
/// covered by the live round-trip, phase 4).
final class DeviceTrustStoreTests: XCTestCase {
    func testSaveLoadIsTrustedClearRoundTrip() throws {
        let store = InMemoryDeviceTrustStore()
        let email = "person@veyq.example"
        let record = TrustRecord(deviceFactor: makeDeviceFactor(), wrapId: "wrap-123")

        XCTAssertFalse(store.isTrusted(email: email))
        XCTAssertNil(try store.load(email: email))

        try store.save(record, email: email)
        XCTAssertTrue(store.isTrusted(email: email))
        XCTAssertEqual(try store.load(email: email), record)

        try store.clear(email: email)
        XCTAssertFalse(store.isTrusted(email: email))
        XCTAssertNil(try store.load(email: email))
    }

    func testSaveReplacesExistingRecord() throws {
        let store = InMemoryDeviceTrustStore()
        let email = "person@veyq.example"
        let first = TrustRecord(deviceFactor: makeDeviceFactor(), wrapId: "wrap-old")
        let second = TrustRecord(deviceFactor: makeDeviceFactor(), wrapId: "wrap-new")

        try store.save(first, email: email)
        try store.save(second, email: email)
        XCTAssertEqual(try store.load(email: email), second)
    }

    func testRecordsAreScopedPerAccount() throws {
        let store = InMemoryDeviceTrustStore()
        let a = TrustRecord(deviceFactor: makeDeviceFactor(), wrapId: "wrap-a")
        try store.save(a, email: "a@veyq.example")

        XCTAssertTrue(store.isTrusted(email: "a@veyq.example"))
        XCTAssertFalse(store.isTrusted(email: "b@veyq.example"))
    }

    func testClearIsIdempotentWhenAbsent() throws {
        let store = InMemoryDeviceTrustStore()
        XCTAssertNoThrow(try store.clear(email: "nobody@veyq.example"))
    }

    func testMakeDeviceFactorIs32RandomBytes() {
        let a = makeDeviceFactor()
        let b = makeDeviceFactor()
        XCTAssertEqual(a.count, 32)
        XCTAssertEqual(b.count, 32)
        XCTAssertNotEqual(a, b, "two device factors must not collide")
    }
}
