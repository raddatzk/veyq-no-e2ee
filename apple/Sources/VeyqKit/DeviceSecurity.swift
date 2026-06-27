import Foundation
import Security

// Per-device unlock-level persistence (uc.account.set-device-security; ADR 0005 §8–13). A device sits at
// exactly one level; this stores the level + the material to open the Master Key on it. Generalised from
// cr-0019's L1-only `DeviceTrust` into the four-level model (cr-0020); split per ADR 0011 (cr-0024) so the
// **data layer never prompts** (the Android layering, now uniform).
//
// **Two Keychain items per account** so the level is knowable *without* a biometric prompt:
//   - a **meta** item `{level, wrapId, refreshToken?}` — always ungated. The level is read freely (decide
//     whether to prompt / which UI to show), and the persisted **refresh token** (L2/L3) lives here so it
//     can be **updated on every rotation without a biometric prompt**. The token is sealed under K, so an
//     ungated read yields only ciphertext.
//   - a **secret** item — the per-level KEK source: L1 = the random `deviceFactor` (ungated); L2/L3 = the
//     random key `K` that directly wraps the Master Key. L2's item is biometric-gated
//     (`.biometryCurrentSet`); L1/L3's is ungated (`WhenUnlockedThisDeviceOnly`).
//
// **Layer split (ADR 0011).** The `DeviceSecurityStore` *protocol* — the part the data layer
// (`VeyqClient`) sees — exposes **ungated meta + the L1 factor only**; it never reads the gated L2 secret,
// so no data-layer call can trigger a prompt. The **app layer** drives the gated L2/L3 key through the
// concrete `KeychainDeviceSecurityStore`'s `storePasswordlessKey` / `releasePasswordlessKey` (the latter is
// the single Face ID prompt at launch) and hands the released `K` to the data layer as plain bytes.

/// The unlock level a device is set to (the slider; ADR 0005 §8). Ordered most-private → most-convenient.
public enum DeviceLevel: String, Codable, Sendable, CaseIterable {
    case secretKeyEachTime   // L0 — no convenience wrap (the floor only)
    case onlyPassword        // L1 — DEVICE wrap, Argon2id(deviceFactor + password)   [cr-0019]
    case biometric           // L2 — random-K wrap, K biometric-gated; session persisted (gated) [cr-0020]
    case noLogin             // L3 — random-K wrap, K ungated; session persisted (ungated)        [cr-0020]
}

/// Per-account persistence of the device level + its material — the **data-layer view** (ADR 0011): ungated
/// meta (level / wrapId / sealed refresh token) plus the L1 `deviceFactor` (also ungated). The L2/L3 random
/// key `K` is **not** here — it is released by the app layer (`KeychainDeviceSecurityStore`) and passed into
/// the data layer, so no method on this protocol can prompt. Behind a protocol so unit tests use an
/// in-memory double (the real Keychain — and the L2 biometric gate — is exercised by the live in-app UI
/// round-trip, not the SPM-CLI unit tests).
public protocol DeviceSecurityStore: Sendable {
    /// The device's level from the **ungated meta** — never triggers a biometric prompt; nil if L0/unset.
    func currentLevel(email: String) -> DeviceLevel?
    /// The server wrap id to open/remove, or nil if none (ungated).
    func wrapId(email: String) -> String?
    /// The persisted (sealed) refresh token for a passwordless device (L2/L3), or nil (ungated).
    func refreshToken(email: String) -> String?
    /// Update only the persisted refresh token (in the ungated meta) — a cheap, **prompt-free** write used
    /// to track refresh-token rotation for L2/L3. No-op if no record exists.
    func updateRefreshToken(_ token: String?, email: String) throws
    /// Ensure this device's L1 factor exists and return it (a fresh random `deviceFactor`, stored ungated).
    func beginEnrol(email: String) throws -> Data
    /// The existing L1 `deviceFactor` (for unlock), or nil if none. Ungated — never prompts.
    func deviceFactor(email: String) -> Data?
    /// Commit the L1 ("only password") level with its server `wrapId`.
    func commitOnlyPassword(wrapId: String, email: String) throws
    /// Commit a passwordless level (L2/L3) with its server `wrapId` + persisted (sealed) refresh token; the
    /// random key `K` is stored separately by the app-layer concrete store.
    func commitPasswordless(level: DeviceLevel, wrapId: String, refreshToken: String, email: String) throws
    /// Remove the account's meta + secret (idempotent).
    func clear(email: String) throws
}

/// `count` cryptographically-random bytes from the platform CSPRNG (the `deviceFactor`, the random key K,
/// per-enrolment salts, AEAD nonces).
public func secureRandomBytes(_ count: Int) -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
    return Data(bytes)
}

/// A fresh 32-byte random `deviceFactor` (L1) — fed factor-first into `V1.kekDevicePassword`, matching the
/// `device_password_argon2id` vector.
public func makeDeviceFactor() -> Data { secureRandomBytes(32) }

public enum DeviceSecurityError: Error, Equatable {
    case keychain(OSStatus)
    case corruptRecord
    case accessControl
}

/// Keychain-backed store: a `kSecClassGenericPassword` **meta** item (ungated) + **secret** item (gated per
/// level), one pair per account, JSON values, non-syncable + `ThisDeviceOnly`. Implements the data-layer
/// protocol (ungated) and adds the **app-layer-only** passwordless-key methods (ADR 0011) — the only place
/// the gated L2 read (the Face ID prompt) happens.
public struct KeychainDeviceSecurityStore: DeviceSecurityStore {
    private let metaService: String
    private let secretService: String

    public init(service: String = "me.raddatz.veyq.device-security") {
        self.metaService = service + ".meta"
        self.secretService = service + ".secret"
    }

    private struct Meta: Codable { let level: DeviceLevel; let wrapId: String; let refreshToken: String? }
    private struct Secrets: Codable { let secret: Data }

    private func base(_ service: String, _ email: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: email,
            kSecAttrSynchronizable: kCFBooleanFalse as Any,
        ]
    }

    // MARK: - Data-layer view (ungated meta + L1 factor)

    public func currentLevel(email: String) -> DeviceLevel? { readMeta(email)?.level }
    public func wrapId(email: String) -> String? { readMeta(email)?.wrapId }
    public func refreshToken(email: String) -> String? { readMeta(email)?.refreshToken }

    public func updateRefreshToken(_ token: String?, email: String) throws {
        guard let meta = readMeta(email) else { return }
        try writeMeta(Meta(level: meta.level, wrapId: meta.wrapId, refreshToken: token), email: email)
    }

    public func beginEnrol(email: String) throws -> Data {
        let factor = makeDeviceFactor()
        try writeSecret(factor, gated: false, email: email)
        return factor
    }

    public func deviceFactor(email: String) -> Data? { (try? readSecret(email)) ?? nil }

    public func commitOnlyPassword(wrapId: String, email: String) throws {
        try writeMeta(Meta(level: .onlyPassword, wrapId: wrapId, refreshToken: nil), email: email)
    }

    public func commitPasswordless(level: DeviceLevel, wrapId: String, refreshToken: String, email: String) throws {
        try writeMeta(Meta(level: level, wrapId: wrapId, refreshToken: refreshToken), email: email)
    }

    public func clear(email: String) throws {
        try delete(metaService, email)
        try delete(secretService, email)
    }

    // MARK: - App-layer view (the gated L2/L3 key — the only prompting path; ADR 0011)

    /// Store the passwordless random key `K` in the secret item: biometric-gated for L2 (`gated == true`),
    /// ungated for L3. Writing a gated item does **not** prompt — only reading it does.
    public func storePasswordlessKey(_ k: Data, gated: Bool, email: String) throws {
        try writeSecret(k, gated: gated, email: email)
    }

    /// Release `K` for a passwordless device. For an **L2** device this read **prompts Face ID / Touch ID**
    /// (the secret item is biometric-gated); for L3 it is free. Returns nil if absent. This is the single
    /// gated read of the launch flow — the data layer never calls it.
    public func releasePasswordlessKey(email: String) throws -> Data? { try readSecret(email) }

    // MARK: - Keychain primitives

    private func readMeta(_ email: String) -> Meta? {
        guard let data = (try? read(metaService, email)) ?? nil else { return nil }
        return try? JSONDecoder().decode(Meta.self, from: data)
    }

    private func readSecret(_ email: String) throws -> Data? {
        guard let data = try read(secretService, email),
              let s = try? JSONDecoder().decode(Secrets.self, from: data) else { return nil }
        return s.secret
    }

    private func writeMeta(_ meta: Meta, email: String) throws {
        let value = try JSONEncoder().encode(meta)
        try delete(metaService, email)
        try add(metaService, email, value, gated: false)
    }

    private func writeSecret(_ secret: Data, gated: Bool, email: String) throws {
        let value = try JSONEncoder().encode(Secrets(secret: secret))
        try delete(secretService, email)
        try add(secretService, email, value, gated: gated)
    }

    private func read(_ service: String, _ email: String) throws -> Data? {
        var query = base(service, email)
        query[kSecMatchLimit] = kSecMatchLimitOne
        query[kSecReturnData] = kCFBooleanTrue as Any
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw DeviceSecurityError.corruptRecord }
            return data
        case errSecItemNotFound:
            return nil
        default:
            throw DeviceSecurityError.keychain(status)
        }
    }

    private func add(_ service: String, _ email: String, _ value: Data, gated: Bool) throws {
        var attributes = base(service, email)
        attributes[kSecValueData] = value
        if gated {
            guard let access = SecAccessControlCreateWithFlags(
                nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .biometryCurrentSet, nil
            ) else { throw DeviceSecurityError.accessControl }
            attributes[kSecAttrAccessControl] = access
        } else {
            attributes[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        }
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw DeviceSecurityError.keychain(status) }
    }

    private func delete(_ service: String, _ email: String) throws {
        let status = SecItemDelete(base(service, email) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DeviceSecurityError.keychain(status)
        }
    }
}
