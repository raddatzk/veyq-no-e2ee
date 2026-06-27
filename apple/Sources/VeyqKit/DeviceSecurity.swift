import Foundation
import Security

// Per-device unlock-level persistence (uc.account.set-device-security; ADR 0005 §8–13). A device sits at
// exactly one level; this stores the level + the material to open the Master Key on it. Generalised from
// cr-0019's L1-only `DeviceTrust` into the four-level model (cr-0020).
//
// **Two Keychain items per account** so the level is knowable *without* a biometric prompt:
//   - a **meta** item `{level, wrapId, refreshToken?}` — always ungated. The level is read freely (decide
//     whether to prompt / which UI to show), and the persisted **refresh token** (L2/L3) lives here so it
//     can be **updated on every rotation without a biometric prompt**. Security: an ungated session token
//     alone only fetches ciphertext (operator-blind) — the *vault* key is the gated secret below.
//   - a **secret** item `{secret}` — gated per level:
//       L0/L1/L3 → `WhenUnlockedThisDeviceOnly` (ungated);  L2 → `.biometryCurrentSet` (a live Face ID /
//       Touch ID releases it — the gate is Secure-Enclave-enforced even though the bytes then return).
//
// `secret` is the per-level KEK source: L1 = the random `deviceFactor` fed into Argon2id(+password);
// L2/L3 = the random key `K` that directly wraps the Master Key. The Master Key, password, and Secret Key
// are never stored here. cr-0020 reuses cr-0019's `symWrap`/`symUnwrap`; this file is only storage.

/// The unlock level a device is set to (the slider; ADR 0005 §8). Ordered most-private → most-convenient.
public enum DeviceLevel: String, Codable, Sendable, CaseIterable {
    case secretKeyEachTime   // L0 — no convenience wrap (the floor only)
    case onlyPassword        // L1 — DEVICE wrap, Argon2id(deviceFactor + password)   [cr-0019]
    case biometric           // L2 — random-K wrap, K biometric-gated; session persisted (gated) [cr-0020]
    case noLogin             // L3 — random-K wrap, K ungated; session persisted (ungated)        [cr-0020]
}

/// What a device keeps for its level. `secret` = `deviceFactor` (L1) or the random key `K` (L2/L3);
/// `wrapId` = the server wrap to open/remove; `refreshToken` = the persisted session for the passwordless
/// levels (L2/L3), nil otherwise.
public struct DeviceSecurityRecord: Equatable, Sendable, Codable {
    public let level: DeviceLevel
    public let wrapId: String
    public let secret: Data
    public let refreshToken: String?

    public init(level: DeviceLevel, wrapId: String, secret: Data, refreshToken: String? = nil) {
        self.level = level
        self.wrapId = wrapId
        self.secret = secret
        self.refreshToken = refreshToken
    }
}

/// Per-account persistence of the device level + its material. Behind a protocol so unit tests use an
/// in-memory double (the real Keychain — and the L2 biometric gate — is exercised by the live in-app UI
/// round-trip, not the SPM-CLI unit tests).
public protocol DeviceSecurityStore: Sendable {
    /// The device's level from the **ungated meta** — never triggers a biometric prompt; nil if L0/unset.
    func currentLevel(email: String) -> DeviceLevel?
    /// The full record incl. secrets. For an L2 device this read **prompts Face ID / Touch ID** (the
    /// secrets item is gated). Throws on an unexpected backing failure.
    func load(email: String) throws -> DeviceSecurityRecord?
    /// Persist (replacing any existing) the record, gating the secret per `record.level`.
    func save(_ record: DeviceSecurityRecord, email: String) throws
    /// Update only the persisted refresh token (in the ungated meta) — a cheap, **prompt-free** write used
    /// to track refresh-token rotation for L2/L3. No-op if no record exists.
    func updateRefreshToken(_ token: String?, email: String) throws
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

/// Keychain-backed store: a `kSecClassGenericPassword` **meta** item (ungated) + **secrets** item (gated
/// per level), one pair per account, JSON values, non-syncable + `ThisDeviceOnly`.
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

    public func currentLevel(email: String) -> DeviceLevel? {
        guard let data = (try? read(metaService, email)) ?? nil,
              let meta = try? JSONDecoder().decode(Meta.self, from: data)
        else { return nil }
        return meta.level
    }

    public func load(email: String) throws -> DeviceSecurityRecord? {
        guard let metaData = try read(metaService, email),
              let meta = try? JSONDecoder().decode(Meta.self, from: metaData)
        else { return nil }
        // The secret read prompts Face ID for an L2 device (the item is biometric-gated).
        guard let secretData = try read(secretService, email),
              let s = try? JSONDecoder().decode(Secrets.self, from: secretData)
        else { throw DeviceSecurityError.corruptRecord }
        return DeviceSecurityRecord(level: meta.level, wrapId: meta.wrapId,
                                    secret: s.secret, refreshToken: meta.refreshToken)
    }

    public func save(_ record: DeviceSecurityRecord, email: String) throws {
        try clear(email: email)
        let metaValue = try JSONEncoder().encode(
            Meta(level: record.level, wrapId: record.wrapId, refreshToken: record.refreshToken)
        )
        let secretValue = try JSONEncoder().encode(Secrets(secret: record.secret))
        try add(metaService, email, metaValue, gated: false)
        try add(secretService, email, secretValue, gated: record.level == .biometric)
    }

    public func updateRefreshToken(_ token: String?, email: String) throws {
        guard let metaData = try read(metaService, email),
              let meta = try? JSONDecoder().decode(Meta.self, from: metaData) else { return }
        let updated = try JSONEncoder().encode(Meta(level: meta.level, wrapId: meta.wrapId, refreshToken: token))
        try delete(metaService, email)
        try add(metaService, email, updated, gated: false)
    }

    public func clear(email: String) throws {
        try delete(metaService, email)
        try delete(secretService, email)
    }

    // MARK: - Keychain primitives

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
