import Crypto
import Foundation
import Sodium

/// The veyq `v1` crypto suite — Apple, auth/unlock subset (cr-0014). Byte-identical to
/// `web/src/shared/crypto/v1.ts`, pinned by `contracts/crypto/vectors/v1.json` (ADR 0009). The hybrid KEM
/// (ML-KEM-768) and content-side primitives are deferred to the first scope/content slice.
public enum V1 {

    /// A wrapped blob / kdfParams from the (untrusted, operator-blind) server was too short to parse. Thrown
    /// instead of trapping on an out-of-range slice, so a malicious/buggy operator can't crash the client.
    public enum V1Error: Error { case malformed }

    public struct ArgonParams: Sendable {
        public let t: Int
        public let m: Int
        public let p: Int
        public let dkLen: Int
        public init(t: Int, m: Int, p: Int, dkLen: Int) { self.t = t; self.m = m; self.p = p; self.dkLen = dkLen }
    }

    /// Auth-path default: m in KiB = 32 MiB (mirrors v1.ts ARGON).
    public static let argon = ArgonParams(t: 3, m: 32768, p: 1, dkLen: 32)

    // MARK: - byte helpers

    /// NFC-normalized UTF-8 bytes (matches TS `utf8`).
    public static func utf8(_ s: String) -> Data {
        Data(s.precomposedStringWithCanonicalMapping.utf8)
    }

    static func u32be(_ n: Int) -> Data {
        let v = UInt32(truncatingIfNeeded: n).bigEndian
        return withUnsafeBytes(of: v) { Data($0) }
    }

    /// length-prefixed: u32be(len) || bytes.
    static func lp(_ x: Data) -> Data { u32be(x.count) + x }

    // MARK: - primitives

    public static func hkdfSha256(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm),
            salt: salt,
            info: info,
            outputByteCount: length,
        )
        return key.withUnsafeBytes { Data($0) }
    }

    /// AES-256-GCM seal → ciphertext ‖ tag (matches TS `aeadSeal`, whose output concatenates ct+tag).
    public static func aeadSeal(key: Data, nonce: Data, plaintext: Data, aad: Data) -> Data {
        let box = try! AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: key),
            nonce: try! AES.GCM.Nonce(data: nonce),
            authenticating: aad,
        )
        return box.ciphertext + box.tag
    }

    /// AES-256-GCM open of a ciphertext ‖ tag blob (tag = last 16 bytes).
    public static func aeadOpen(key: Data, nonce: Data, ciphertextAndTag: Data, aad: Data) throws -> Data {
        guard ciphertextAndTag.count >= 16 else { throw V1Error.malformed }
        let ct = ciphertextAndTag.prefix(ciphertextAndTag.count - 16)
        let tag = ciphertextAndTag.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: nonce), ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // MARK: - Argon2id (libsodium)

    static func argon2id(_ password: Data, salt: Data, _ params: ArgonParams) -> Data {
        // libsodium crypto_pwhash: opsLimit = passes (t), memLimit in BYTES (m KiB * 1024), Argon2id v1.3.
        let out = Sodium().pwHash.hash(
            outputLength: params.dkLen,
            passwd: [UInt8](password),
            salt: [UInt8](salt),
            opsLimit: params.t,
            memLimit: params.m * 1024,
            alg: .Argon2ID13,
        )!
        return Data(out)
    }

    // MARK: - KDF params wire format (u32be t‖m‖p‖dkLen‖salt)

    public static func encodeKdfParams(_ p: ArgonParams, salt: Data) -> Data {
        u32be(p.t) + u32be(p.m) + u32be(p.p) + u32be(p.dkLen) + salt
    }

    public static func decodeKdfParams(_ b: Data) throws -> (params: ArgonParams, salt: Data) {
        guard b.count >= 16 else { throw V1Error.malformed }
        func be(_ off: Int) -> Int {
            let s = b.startIndex + off
            return (Int(b[s]) << 24) | (Int(b[s + 1]) << 16) | (Int(b[s + 2]) << 8) | Int(b[s + 3])
        }
        let params = ArgonParams(t: be(0), m: be(4), p: be(8), dkLen: be(12))
        return (params, b.suffix(from: b.startIndex + 16))
    }

    // MARK: - unlock KEKs

    /// PASSWORD-floor unlock KEK: Argon2id(lp(secretKey) ‖ lp(utf8(password)), salt).
    public static func kekPasswordSecret(secretKey: Data, password: String, salt: Data, params: ArgonParams = argon) -> Data {
        argon2id(lp(secretKey) + lp(utf8(password)), salt: salt, params)
    }

    /// RECOVERY KEK: Argon2id(recoveryCode, salt) — the code alone.
    public static func kekRecovery(recoveryCode: Data, salt: Data, params: ArgonParams = argon) -> Data {
        argon2id(recoveryCode, salt: salt, params)
    }

    /// DEVICE-trust KEK: Argon2id(lp(deviceFactor) ‖ lp(utf8(password)), salt) — factor replaces the Secret Key.
    public static func kekDevicePassword(deviceFactor: Data, password: String, salt: Data, params: ArgonParams = argon) -> Data {
        argon2id(lp(deviceFactor) + lp(utf8(password)), salt: salt, params)
    }

    // MARK: - symmetric wrap (0x02 ‖ nonce(12) ‖ aead)

    private static let symType: UInt8 = 0x02
    private static func wrapAad(_ type: UInt8, _ context: String) -> Data { Data([type]) + utf8("v1" + context) }

    public static func symWrap(kek: Data, targetKey: Data, context: String, nonce: Data) -> Data {
        Data([symType]) + nonce + aeadSeal(key: kek, nonce: nonce, plaintext: targetKey, aad: wrapAad(symType, context))
    }

    public static func symUnwrap(kek: Data, blob: Data, context: String) throws -> Data {
        // 1 type byte + 12 nonce + at least a 16-byte tag; shorter is a malformed/hostile blob, not ours.
        guard blob.count >= 1 + 12 + 16 else { throw V1Error.malformed }
        let nonce = blob.subdata(in: blob.startIndex + 1 ..< blob.startIndex + 13)
        let body = blob.suffix(from: blob.startIndex + 13)
        return try aeadOpen(key: kek, nonce: nonce, ciphertextAndTag: body, aad: wrapAad(symType, context))
    }

    // MARK: - operator-blind password auth (ADR 0003 / Model B)

    /// Deterministic per-account auth salt: HKDF(ikm = normalized email, salt = instanceSalt, "veyq/v1/auth-salt", 16).
    public static func deriveAuthSalt(instanceSalt: Data, email: String) -> Data {
        let normalized = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return hkdfSha256(ikm: utf8(normalized), salt: instanceSalt, info: utf8("veyq/v1/auth-salt"), length: 16)
    }

    public struct AuthKeypair: Sendable { public let publicKey: Data; public let privateKey: Data }

    /// Ed25519 auth keypair from password + per-account salt: seed = Argon2id(utf8(password), authSalt).
    public static func deriveAuthKeypair(password: String, authSalt: Data, params: ArgonParams = argon) -> AuthKeypair {
        let seed = argon2id(utf8(password), salt: authSalt, ArgonParams(t: params.t, m: params.m, p: params.p, dkLen: 32))
        let sk = try! Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        return AuthKeypair(publicKey: Data(sk.publicKey.rawRepresentation), privateKey: seed)
    }

    public static func authSign(privateKey: Data, message: Data) -> Data {
        let sk = try! Curve25519.Signing.PrivateKey(rawRepresentation: privateKey)
        return try! Data(sk.signature(for: message))
    }

    public static func authVerify(publicKey: Data, message: Data, signature: Data) -> Bool {
        guard let pk = try? Curve25519.Signing.PublicKey(rawRepresentation: publicKey) else { return false }
        return pk.isValidSignature(signature, for: message)
    }

    // MARK: - recovery commitment

    /// Password-reset recovery commitment: SHA-256("veyq/v1/recovery-auth" ‖ masterKey).
    public static func recoveryAuthTag(masterKey: Data) -> Data {
        Data(SHA256.hash(data: utf8("veyq/v1/recovery-auth") + masterKey))
    }
}
