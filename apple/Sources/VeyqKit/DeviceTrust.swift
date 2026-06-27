import Foundation
import Security

// Device-trust persistence for the **DEVICE wrap** unlock (cr-0019, L1 — "only password"; ADR 0005 §4,
// §8). When a device is trusted, future unlocks need only the password: the KEK is
// `Argon2id(deviceFactor + password)`. `deviceFactor` is a random, device-bound secret that must survive
// app launches — so, unlike `Session`/`Vault` (in-memory only), it is persisted. On Apple the backing is
// the **Keychain** (`WhenUnlockedThisDeviceOnly`, non-syncable): the platform analog of the web's
// non-extractable IndexedDB key.
//
// Security note (Stufe A): a Keychain blob *is* readable by the app process — weaker than the web's
// non-extractable key. Accepted here because the password is still a required second factor (an
// exfiltrated `deviceFactor` alone opens nothing). Secure-Enclave hardening is the biometric slice
// (cr-0020 / uc.account.biometric-unlock), where the key never leaves the secure element.
//
// The KEK derivation itself is `VeyqCrypto.V1.kekDevicePassword` (already vector-conformant); this file
// is only the *storage* of the per-device factor + the server wrap id, never any crypto.

/// What a trusted device keeps locally: the random device-bound `deviceFactor` fed into the DEVICE-wrap
/// KEK, and the server `wrapId` of this device's DEVICE wrap (the authoritative match in the fetched key
/// material, ADR 0005 / cr-0005 #6). The Master Key, KEK, and Secret Key are **never** stored.
public struct TrustRecord: Equatable, Sendable, Codable {
    public let deviceFactor: Data   // JSON-encoded as base64 by JSONEncoder's default Data strategy.
    public let wrapId: String

    public init(deviceFactor: Data, wrapId: String) {
        self.deviceFactor = deviceFactor
        self.wrapId = wrapId
    }
}

/// Per-account persistence of the device-trust record. Behind a protocol so unit tests use an in-memory
/// double — the real Keychain is entitlement-sensitive from the SPM CLI and cross-test-polluting, so it is
/// exercised by the live round-trip (cr-0019 phase 4), not the unit tests.
public protocol DeviceTrustStore: Sendable {
    /// True iff a trust record is stored for this account on this device. Treats any read error as
    /// "not trusted" (the unlock flow then falls back to the floor — never a hard failure).
    func isTrusted(email: String) -> Bool
    /// The stored record for this account, or nil if none. Throws only on an unexpected backing failure.
    func load(email: String) throws -> TrustRecord?
    /// Persist (replacing any existing record) the trust record for this account.
    func save(_ record: TrustRecord, email: String) throws
    /// Remove this account's record (idempotent — absent is success).
    func clear(email: String) throws
}

/// `count` cryptographically-random bytes from the platform CSPRNG. Used for the `deviceFactor`, the
/// per-enrolment Argon2id salt, and the AEAD nonce.
public func secureRandomBytes(_ count: Int) -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
    return Data(bytes)
}

/// A fresh 32-byte random `deviceFactor`. Generated once per device-trust enrolment; fed (factor-first)
/// into `V1.kekDevicePassword`, matching the `device_password_argon2id` vector.
public func makeDeviceFactor() -> Data { secureRandomBytes(32) }

public enum DeviceTrustError: Error, Equatable {
    /// An unexpected Keychain `OSStatus` (not the benign not-found cases).
    case keychain(OSStatus)
    /// A stored record could not be decoded (corrupt / format change).
    case corruptRecord
}

/// Keychain-backed store: one `kSecClassGenericPassword` item per account, value = the JSON-encoded
/// `TrustRecord`, protected `WhenUnlockedThisDeviceOnly` and non-syncable (device-bound, never iCloud).
public struct KeychainDeviceTrustStore: DeviceTrustStore {
    private let service: String

    public init(service: String = "me.raddatz.veyq.device-trust") {
        self.service = service
    }

    private func baseQuery(email: String) -> [CFString: Any] {
        [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: email,
            kSecAttrSynchronizable: kCFBooleanFalse as Any,
        ]
    }

    public func isTrusted(email: String) -> Bool {
        (try? load(email: email)) != nil
    }

    public func load(email: String) throws -> TrustRecord? {
        var query = baseQuery(email: email)
        query[kSecMatchLimit] = kSecMatchLimitOne
        query[kSecReturnData] = kCFBooleanTrue as Any

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw DeviceTrustError.corruptRecord }
            guard let record = try? JSONDecoder().decode(TrustRecord.self, from: data) else {
                throw DeviceTrustError.corruptRecord
            }
            return record
        case errSecItemNotFound:
            return nil
        default:
            throw DeviceTrustError.keychain(status)
        }
    }

    public func save(_ record: TrustRecord, email: String) throws {
        let value = try JSONEncoder().encode(record)
        // Replace: delete any existing item, then add the fresh one (avoids errSecDuplicateItem and keeps
        // the access-control attributes authoritative).
        try clear(email: email)
        var attributes = baseQuery(email: email)
        attributes[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        attributes[kSecValueData] = value
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw DeviceTrustError.keychain(status) }
    }

    public func clear(email: String) throws {
        let status = SecItemDelete(baseQuery(email: email) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw DeviceTrustError.keychain(status)
        }
    }
}
