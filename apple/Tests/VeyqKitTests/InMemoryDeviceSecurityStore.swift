import Foundation
@testable import VeyqKit

/// Test double for `DeviceSecurityStore` — an in-memory map, no Keychain / no biometric gate. Used by the
/// device-level unit tests and the orchestration tests: the real `KeychainDeviceSecurityStore` (and the L2
/// biometric prompt) is entitlement-sensitive from the SPM CLI, so it is covered by the live in-app UI
/// round-trip, not the unit suite.
///
/// A `final class` with an `NSLock` (not an `actor`) so it satisfies the **synchronous** protocol —
/// `@unchecked Sendable` because the lock guards all access to `records`.
final class InMemoryDeviceSecurityStore: DeviceSecurityStore, @unchecked Sendable {
    private let lock = NSLock()
    private var records: [String: DeviceSecurityRecord] = [:]

    func currentLevel(email: String) -> DeviceLevel? {
        lock.withLock { records[email]?.level }
    }

    func load(email: String) throws -> DeviceSecurityRecord? {
        lock.withLock { records[email] }
    }

    func save(_ record: DeviceSecurityRecord, email: String) throws {
        lock.withLock { records[email] = record }
    }

    func updateRefreshToken(_ token: String?, email: String) throws {
        lock.withLock {
            guard let r = records[email] else { return }
            records[email] = DeviceSecurityRecord(level: r.level, wrapId: r.wrapId, secret: r.secret, refreshToken: token)
        }
    }

    func clear(email: String) throws {
        lock.withLock { _ = records.removeValue(forKey: email) }
    }
}
