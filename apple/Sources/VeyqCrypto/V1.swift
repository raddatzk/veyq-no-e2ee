import Crypto
import Foundation
import Sodium

/// The veyq `v1` crypto suite — Apple. Byte-identical to `web/src/shared/crypto/v1.ts`, pinned by
/// `contracts/crypto/vectors/v1.json` (ADR 0009). Auth/unlock subset (cr-0014) + the hybrid KEM
/// (X25519 + ML-KEM-768), asymWrap and identity bundle (cr-0025). Remaining content-side primitives
/// (journal/chunk/slug/dedup) are added with the content slice.
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

    // MARK: - Hybrid KEM (X25519 + ML-KEM-768) — the scope-grant primitive (cr-0025)
    //
    // Mirrors `web/src/shared/crypto/v1.ts` byte-for-byte, pinned by `kem_v1` / `wrap.asym_scope_grant`.
    // `HybridSecret.m` is the **64-byte ML-KEM seed** (FIPS 203 keygen reconstructs the private key — the
    // form CryptoKit/BouncyCastle want); `HybridPublic.m` is the 1184-byte ML-KEM public key.

    public struct HybridPublic: Sendable {
        public let x: Data   // X25519 public key (32)
        public let m: Data   // ML-KEM-768 public key (1184)
        public init(x: Data, m: Data) { self.x = x; self.m = m }
    }
    public struct HybridSecret: Sendable {
        public let x: Data   // X25519 secret (32)
        public let m: Data   // ML-KEM-768 seed (64) — the private key is reconstructed from it
        public init(x: Data, m: Data) { self.x = x; self.m = m }
    }

    private static let asymType: UInt8 = 0x01

    /// HKDF combiner binding both shared secrets + the transcript: ikm = lp(ssM)‖lp(ssX),
    /// salt = SHA256(lp(ctM)‖lp(epkX)‖lp(pkM)‖lp(pkX)), info = "veyq/v1/kem".
    private static func kemCombiner(ssM: Data, ssX: Data, ctM: Data, epkX: Data, pkM: Data, pkX: Data) -> Data {
        let ikm = lp(ssM) + lp(ssX)
        let salt = Data(SHA256.hash(data: lp(ctM) + lp(epkX) + lp(pkM) + lp(pkX)))
        return hkdfSha256(ikm: ikm, salt: salt, info: utf8("veyq/v1/kem"), length: 32)
    }

    private static func rawBytes(_ b: some ContiguousBytes) -> Data { b.withUnsafeBytes { Data($0) } }

    /// Encapsulate to a recipient's hybrid public key → (ct = epkX(32)‖mlkemCt(1088), shared secret(32)).
    public static func kemEncap(_ pk: HybridPublic) throws -> (ct: Data, ss: Data) {
        let esk = Curve25519.KeyAgreement.PrivateKey()
        let epkX = esk.publicKey.rawRepresentation
        let peerX = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: pk.x)
        let ssX = rawBytes(try esk.sharedSecretFromKeyAgreement(with: peerX))
        let mlPub = try MLKEM768.PublicKey(rawRepresentation: pk.m)
        let enc = try mlPub.encapsulate()
        let ssM = rawBytes(enc.sharedSecret)
        let ctM = enc.encapsulated
        let ss = kemCombiner(ssM: ssM, ssX: ssX, ctM: ctM, epkX: epkX, pkM: pk.m, pkX: pk.x)
        return (epkX + ctM, ss)
    }

    /// Decapsulate: recover the shared secret from `ct` with the recipient's hybrid secret (+ its public,
    /// which the combiner binds). The ML-KEM private key is reconstructed from the stored seed.
    public static func kemDecap(_ sk: HybridSecret, _ pk: HybridPublic, ct: Data) throws -> Data {
        let ctd = Data(ct)
        guard ctd.count >= 32 else { throw V1Error.malformed }
        let epkX = ctd.prefix(32)
        let ctM = ctd.suffix(from: ctd.startIndex + 32)
        let myX = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: sk.x)
        let peerEpk = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: Data(epkX))
        let ssX = rawBytes(try myX.sharedSecretFromKeyAgreement(with: peerEpk))
        let mlSk = try MLKEM768.PrivateKey(seedRepresentation: sk.m, publicKey: nil)
        let ssM = rawBytes(try mlSk.decapsulate(Data(ctM)))
        return kemCombiner(ssM: ssM, ssX: ssX, ctM: Data(ctM), epkX: Data(epkX), pkM: pk.m, pkX: pk.x)
    }

    /// §5a asymmetric wrap: 0x01 ‖ ct(1120) ‖ nonce(12) ‖ aead. KEK = HKDF(ss, "", "veyq/v1/wrap"+context).
    public static func asymWrap(pk: HybridPublic, targetKey: Data, context: String, nonce: Data) throws -> Data {
        let enc = try kemEncap(pk)
        let kek = hkdfSha256(ikm: enc.ss, salt: Data(), info: utf8("veyq/v1/wrap" + context), length: 32)
        return Data([asymType]) + enc.ct + nonce
            + aeadSeal(key: kek, nonce: nonce, plaintext: targetKey, aad: wrapAad(asymType, context))
    }

    public static func asymUnwrap(sk: HybridSecret, pk: HybridPublic, blob: Data, context: String) throws -> Data {
        let b = Data(blob)
        // 1 type + 1120 ct + 12 nonce + ≥16 tag; shorter is a malformed/hostile blob.
        guard b.count >= 1 + 1120 + 12 + 16 else { throw V1Error.malformed }
        let ct = b.subdata(in: 1 ..< 1 + 1120)
        let nonce = b.subdata(in: 1 + 1120 ..< 1 + 1120 + 12)
        let aead = b.suffix(from: 1 + 1120 + 12)
        let ss = try kemDecap(sk, pk, ct: ct)
        let kek = hkdfSha256(ikm: ss, salt: Data(), info: utf8("veyq/v1/wrap" + context), length: 32)
        return try aeadOpen(key: kek, nonce: nonce, ciphertextAndTag: aead, aad: wrapAad(asymType, context))
    }

    // MARK: - Identity (hybrid KEM keypair + Ed25519 signing) — cr-0025

    public struct IdentityPublic: Sendable {
        public let kemX: Data; public let kemM: Data; public let sign: Data
        public init(kemX: Data, kemM: Data, sign: Data) { self.kemX = kemX; self.kemM = kemM; self.sign = sign }
    }
    public struct IdentityPrivate: Sendable {
        public let kemX: Data; public let mlkemSeed: Data; public let sign: Data
        public init(kemX: Data, mlkemSeed: Data, sign: Data) { self.kemX = kemX; self.mlkemSeed = mlkemSeed; self.sign = sign }
    }
    public struct Identity: Sendable {
        public let pub: IdentityPublic; public let priv: IdentityPrivate
        public init(pub: IdentityPublic, priv: IdentityPrivate) { self.pub = pub; self.priv = priv }
    }

    /// HybridSecret for `kemDecap`/`asymUnwrap` from a stored private bundle (m = the 64-byte seed).
    public static func hybridSecret(_ priv: IdentityPrivate) -> HybridSecret {
        HybridSecret(x: priv.kemX, m: priv.mlkemSeed)
    }

    /// The hybrid PUBLIC bundle re-derived from a stored private bundle — for sealing to one's own identity.
    public static func hybridPublic(_ priv: IdentityPrivate) throws -> HybridPublic {
        let x = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv.kemX).publicKey.rawRepresentation
        let m = try MLKEM768.PrivateKey(seedRepresentation: priv.mlkemSeed, publicKey: nil).publicKey.rawRepresentation
        return HybridPublic(x: x, m: m)
    }

    /// Generate a fresh identity (hybrid KEM keypair + Ed25519 signing). Stored private form is the seeds.
    public static func generateIdentity() -> Identity {
        let kemXsk = Curve25519.KeyAgreement.PrivateKey()
        let mlSk = try! MLKEM768.PrivateKey()
        let signSk = Curve25519.Signing.PrivateKey()
        return Identity(
            pub: IdentityPublic(kemX: kemXsk.publicKey.rawRepresentation,
                                kemM: mlSk.publicKey.rawRepresentation,
                                sign: signSk.publicKey.rawRepresentation),
            priv: IdentityPrivate(kemX: kemXsk.rawRepresentation,
                                  mlkemSeed: mlSk.seedRepresentation,
                                  sign: signSk.rawRepresentation)
        )
    }

    /// Canonical identity PUBLIC serialization: lp(kemX) ‖ lp(kemM) ‖ lp(sign).
    public static func serializeIdentityPublic(_ pub: IdentityPublic) -> Data {
        lp(pub.kemX) + lp(pub.kemM) + lp(pub.sign)
    }

    /// Identity PRIVATE serialization (wrapped under the Master Key): lp(kemX) ‖ lp(mlkemSeed) ‖ lp(sign).
    public static func serializeIdentityPrivate(_ priv: IdentityPrivate) -> Data {
        lp(priv.kemX) + lp(priv.mlkemSeed) + lp(priv.sign)
    }

    /// Inverse of `serializeIdentityPrivate`: read three length-prefixed fields (guards untrusted bytes).
    public static func deserializeIdentityPrivate(_ bytes: Data) throws -> IdentityPrivate {
        let b = Data(bytes)
        var o = 0
        func next() throws -> Data {
            guard o + 4 <= b.count else { throw V1Error.malformed }
            let len = (Int(b[o]) << 24) | (Int(b[o + 1]) << 16) | (Int(b[o + 2]) << 8) | Int(b[o + 3])
            o += 4
            guard len >= 0, o + len <= b.count else { throw V1Error.malformed }
            let v = b.subdata(in: o ..< o + len)
            o += len
            return v
        }
        let kemX = try next(); let seed = try next(); let sign = try next()
        return IdentityPrivate(kemX: kemX, mlkemSeed: seed, sign: sign)
    }

    /// Identity fingerprint ("safety number"): SHA-256("veyq/v1/fingerprint" ‖ serializeIdentityPublic),
    /// rendered as 6 groups of 5 decimal digits (40-bit big-endian chunks mod 100000). Verification only.
    public static func fingerprint(_ pub: IdentityPublic) -> String {
        let h = Data(SHA256.hash(data: utf8("veyq/v1/fingerprint") + serializeIdentityPublic(pub)))
        var groups: [String] = []
        for i in 0 ..< 6 {
            var v = 0
            for j in 0 ..< 5 { v = v * 256 + Int(h[i * 5 + j]) }
            groups.append(String(format: "%05d", v % 100000))
        }
        return groups.joined(separator: " ")
    }

    // MARK: - content tokens (cr-0027)

    /// Blinded slug token (AD-26): `slugKey = HKDF(contentKey, "", "veyq/v1/slug-token:"+parentSlot, 32)`;
    /// `token = HMAC-SHA256(slugKey, NFC(slug))`. Per-parent keyed + one-way, so the blind server enforces
    /// slug uniqueness per parent without seeing the name. `parentSlot` = the parent node id (or the scope id
    /// at the root).
    public static func slugToken(contentKey: Data, parentSlot: String, slug: String) -> Data {
        let slugKey = hkdfSha256(ikm: contentKey, salt: Data(), info: utf8("veyq/v1/slug-token:" + parentSlot), length: 32)
        let mac = HMAC<SHA256>.authenticationCode(for: utf8(slug), using: SymmetricKey(data: slugKey))
        return Data(mac)
    }

    // MARK: - chunked-AEAD frame (cr-0028)

    /// Chunked-AEAD frame (resumable upload + segmented streaming): a large blob is split into independent,
    /// self-contained frames so chunks resume individually and segments decrypt without the whole file. Each
    /// frame = `nonce(12) || AEAD(contentKey, nonce, plaintextChunk, aad)` with `aad = u32be(index) || u32be(total)`,
    /// binding the chunk's position so a dropped, reordered or appended frame fails to open (anti-truncation).
    /// The media transport seals each chunk under a per-blob `K_blob`.
    public static func sealFrame(contentKey: Data, index: Int, total: Int, plaintext: Data, nonce: Data) -> Data {
        nonce + aeadSeal(key: contentKey, nonce: nonce, plaintext: plaintext, aad: u32be(index) + u32be(total))
    }

    public static func openFrame(contentKey: Data, index: Int, total: Int, frame: Data) throws -> Data {
        // 12 nonce + at least a 16-byte tag; shorter is a malformed/hostile frame, not ours.
        guard frame.count >= 12 + 16 else { throw V1Error.malformed }
        let nonce = frame.subdata(in: frame.startIndex ..< frame.startIndex + 12)
        let body = frame.suffix(from: frame.startIndex + 12)
        return try aeadOpen(key: contentKey, nonce: nonce, ciphertextAndTag: body, aad: u32be(index) + u32be(total))
    }
}
