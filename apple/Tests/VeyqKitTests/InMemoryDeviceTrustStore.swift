import Foundation
@testable import VeyqKit

/// Test double for `DeviceTrustStore` — an in-memory map, no Keychain. Used by the device-trust unit
/// tests (cr-0019 phase 1) and the orchestration tests (phase 2): the real `KeychainDeviceTrustStore` is
/// entitlement-sensitive from the SPM CLI and cross-test-polluting, so it is covered by the live
/// round-trip (phase 4), not the unit suite.
///
/// A `final class` with an `NSLock` (not an `actor`) so it satisfies the **synchronous** `DeviceTrustStore`
/// protocol — `@unchecked Sendable` because the lock guards all access to `records`.
final class InMemoryDeviceTrustStore: DeviceTrustStore, @unchecked Sendable {
    private let lock = NSLock()
    private var records: [String: TrustRecord] = [:]

    func isTrusted(email: String) -> Bool {
        lock.withLock { records[email] != nil }
    }

    func load(email: String) throws -> TrustRecord? {
        lock.withLock { records[email] }
    }

    func save(_ record: TrustRecord, email: String) throws {
        lock.withLock { records[email] = record }
    }

    func clear(email: String) throws {
        lock.withLock { _ = records.removeValue(forKey: email) }
    }
}
