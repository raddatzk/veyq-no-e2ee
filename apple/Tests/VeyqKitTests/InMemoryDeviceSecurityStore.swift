import Foundation
@testable import VeyqKit

/// Test double for the data-layer `DeviceSecurityStore` — an in-memory map, no Keychain / no biometric gate
/// (ADR 0011: the protocol is the **ungated** view; the gated L2 release lives in the app-layer concrete
/// store and is exercised by the live in-app UI round-trip, not this suite). `beginEnrol` mints a **stable**
/// random `deviceFactor` per account (a device-bound key never changes). The twin of Android's
/// `InMemoryDeviceSecurityStore`.
///
/// A `final class` with an `NSLock` (not an `actor`) so it satisfies the **synchronous** protocol —
/// `@unchecked Sendable` because the lock guards all access.
final class InMemoryDeviceSecurityStore: DeviceSecurityStore, @unchecked Sendable {
    private final class Rec {
        let factor: Data
        var level: DeviceLevel?
        var wrapId: String?
        var token: String?
        init(factor: Data) { self.factor = factor }
    }

    private let lock = NSLock()
    private var recs: [String: Rec] = [:]

    /// Get-or-create the record (a stable random factor on first touch). Caller holds `lock`.
    private func rec(_ email: String) -> Rec {
        if let r = recs[email] { return r }
        let r = Rec(factor: makeDeviceFactor()); recs[email] = r; return r
    }

    func currentLevel(email: String) -> DeviceLevel? { lock.withLock { recs[email]?.level } }
    func wrapId(email: String) -> String? { lock.withLock { recs[email]?.wrapId } }
    func refreshToken(email: String) -> String? { lock.withLock { recs[email]?.token } }
    func updateRefreshToken(_ token: String?, email: String) throws { lock.withLock { recs[email]?.token = token } }
    func beginEnrol(email: String) throws -> Data { lock.withLock { rec(email).factor } }
    func deviceFactor(email: String) -> Data? { lock.withLock { recs[email]?.factor } }

    func commitOnlyPassword(wrapId: String, email: String) throws {
        lock.withLock { let r = rec(email); r.level = .onlyPassword; r.wrapId = wrapId; r.token = nil }
    }

    func commitPasswordless(level: DeviceLevel, wrapId: String, refreshToken: String, email: String) throws {
        lock.withLock { let r = rec(email); r.level = level; r.wrapId = wrapId; r.token = refreshToken }
    }

    func clear(email: String) throws { lock.withLock { _ = recs.removeValue(forKey: email) } }
}
