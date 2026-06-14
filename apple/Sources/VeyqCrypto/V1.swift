import Foundation
import CryptoKit
import Sodium

/// veyq v1 crypto suite — Apple (Swift/CryptoKit). Conformance: docs/api/crypto-vectors/v1.json.
public enum V1 {
    public struct ArgonParams: Sendable {
        public let t: Int
        public let m: Int
        public let p: Int
        public let dkLen: Int
        public init(t: Int, m: Int, p: Int, dkLen: Int) { self.t = t; self.m = m; self.p = p; self.dkLen = dkLen }
    }

    private static func bytes(_ b: some ContiguousBytes) -> Data {
        b.withUnsafeBytes { Data(bytes: $0.baseAddress!, count: $0.count) }
    }

    static func u32be(_ n: Int) -> Data {
        Data([UInt8((n >> 24) & 0xff), UInt8((n >> 16) & 0xff), UInt8((n >> 8) & 0xff), UInt8(n & 0xff)])
    }
    /// length-prefixed: u32(len) || bytes
    static func lp(_ x: Data) -> Data { u32be(x.count) + x }

    /// kdfParams wire format (crypto agility, AD-25): the unlock cost params travel WITH the wrap so a future
    /// cost bump never strands an account minted under lighter params. Layout: u32be t || m || p || dkLen || salt.
    public static func encodeKdfParams(_ p: ArgonParams, salt: Data) -> Data {
        u32be(p.t) + u32be(p.m) + u32be(p.p) + u32be(p.dkLen) + salt
    }
    public static func decodeKdfParams(_ b: Data) -> (params: ArgonParams, salt: Data) {
        func r(_ o: Int) -> Int { (Int(b[b.startIndex + o]) << 24) | (Int(b[b.startIndex + o + 1]) << 16) | (Int(b[b.startIndex + o + 2]) << 8) | Int(b[b.startIndex + o + 3]) }
        return (ArgonParams(t: r(0), m: r(4), p: r(8), dkLen: r(12)), b.subdata(in: (b.startIndex + 16)..<b.endIndex))
    }

    // ---- AEAD: AES-256-GCM (12-byte nonce, tag appended) ----
    public static func aeadSeal(key: Data, nonce: Data, plaintext: Data, aad: Data) throws -> Data {
        let box = try AES.GCM.seal(
            plaintext, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: aad
        )
        return box.ciphertext + box.tag
    }
    public static func aeadOpen(key: Data, nonce: Data, aead: Data, aad: Data) throws -> Data {
        let ct = aead.prefix(aead.count - 16)
        let tag = aead.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: aad)
    }

    // ---- HKDF-SHA256 ----
    static func hkdf(ikm: Data, salt: Data, info: Data, len: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: len
        )
        return bytes(key)
    }

    // ---- Hybrid KEM (KEM-v1) ----
    static func combiner(ssM: Data, ssX: Data, ctM: Data, epkX: Data, pkM: Data, pkX: Data) -> Data {
        let ikm = lp(ssM) + lp(ssX)
        let salt = Data(SHA256.hash(data: lp(ctM) + lp(epkX) + lp(pkM) + lp(pkX)))
        return hkdf(ikm: ikm, salt: salt, info: Data("veyq/v1/kem".utf8), len: 32)
    }

    /// ML-KEM private key is reconstructed from the FIPS-203 seed (CryptoKit takes a seed, not the
    /// expanded secret key). X25519 secret is raw bytes.
    public static func kemDecap(skX: Data, mlkemSeed: Data, pkX: Data, pkM: Data, ct: Data) throws -> Data {
        let epkX = Data(ct.prefix(32))
        let ctM = Data(ct.dropFirst(32))
        let xPriv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: skX)
        let xPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: epkX)
        let ssX = bytes(try xPriv.sharedSecretFromKeyAgreement(with: xPub))
        let mPriv = try MLKEM768.PrivateKey(seedRepresentation: mlkemSeed, publicKey: nil)
        let ssM = bytes(try mPriv.decapsulate(ctM))
        return combiner(ssM: ssM, ssX: ssX, ctM: ctM, epkX: epkX, pkM: pkM, pkX: pkX)
    }

    /// Encapsulate to a recipient's hybrid KEM public key. Randomized (ephemeral X25519 + ML-KEM).
    public static func kemEncap(pkX: Data, pkM: Data) throws -> (ct: Data, ss: Data) {
        let esk = Curve25519.KeyAgreement.PrivateKey()
        let epkX = esk.publicKey.rawRepresentation
        let xPub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: pkX)
        let ssX = bytes(try esk.sharedSecretFromKeyAgreement(with: xPub))
        let mPub = try MLKEM768.PublicKey(rawRepresentation: pkM)
        let enc = try mPub.encapsulate()
        let ssM = bytes(enc.sharedSecret)
        let ctM = enc.encapsulated
        let ss = combiner(ssM: ssM, ssX: ssX, ctM: ctM, epkX: epkX, pkM: pkM, pkX: pkX)
        return (epkX + ctM, ss)
    }

    // ---- Wraps ----
    // AAD binds the envelope TYPE byte (0x02 sym / 0x01 asym) alongside the suite+context, so a ciphertext
    // authenticated for one wrap type can never be reinterpreted as the other (defense in depth vs confusion).
    private static func wrapAad(_ type: UInt8, _ context: String) -> Data { Data([type]) + Data(("v1" + context).utf8) }

    public static func symWrap(kek: Data, targetKey: Data, context: String, nonce: Data) throws -> Data {
        Data([0x02]) + nonce + (try aeadSeal(key: kek, nonce: nonce, plaintext: targetKey, aad: wrapAad(0x02, context)))
    }
    public static func symUnwrap(kek: Data, blob: Data, context: String) throws -> Data {
        let nonce = Data(blob.dropFirst().prefix(12))
        let aead = Data(blob.dropFirst(1 + 12))
        return try aeadOpen(key: kek, nonce: nonce, aead: aead, aad: wrapAad(0x02, context))
    }
    /// §5a asymmetric wrap: 0x01 || ct(1120) || nonce(12) || aead
    public static func asymWrap(pkX: Data, pkM: Data, targetKey: Data, context: String, nonce: Data) throws -> Data {
        let (ct, ss) = try kemEncap(pkX: pkX, pkM: pkM)
        let kek = hkdf(ikm: ss, salt: Data(), info: Data(("veyq/v1/wrap" + context).utf8), len: 32)
        return Data([0x01]) + ct + nonce + (try aeadSeal(key: kek, nonce: nonce, plaintext: targetKey, aad: wrapAad(0x01, context)))
    }
    public static func asymUnwrap(skX: Data, mlkemSeed: Data, pkX: Data, pkM: Data, blob: Data, context: String) throws -> Data {
        let body = Data(blob.dropFirst())
        let ct = Data(body.prefix(1120))
        let nonce = Data(body.dropFirst(1120).prefix(12))
        let aead = Data(body.dropFirst(1120 + 12))
        let ss = try kemDecap(skX: skX, mlkemSeed: mlkemSeed, pkX: pkX, pkM: pkM, ct: ct)
        let kek = hkdf(ikm: ss, salt: Data(), info: Data(("veyq/v1/wrap" + context).utf8), len: 32)
        return try aeadOpen(key: kek, nonce: nonce, aead: aead, aad: wrapAad(0x01, context))
    }

    /// Blinded slug token (AD-26): slugKey = HKDF(contentKey, "veyq/v1/slug-token:" + parentSlot);
    /// token = HMAC-SHA256(slugKey, NFC(slug)). The key is derived PER PARENT so the same slug under
    /// different parents yields different tokens (no cross-parent name-equality leak). parentSlot is the
    /// parent node id, or the scope id for a root-level node (matching the server's parent_id sentinel).
    public static func slugToken(contentKey: Data, parentSlot: String, slug: String) -> Data {
        let slugKey = hkdf(ikm: contentKey, salt: Data(), info: Data(("veyq/v1/slug-token:" + parentSlot).utf8), len: 32)
        let nfc = Data(slug.precomposedStringWithCanonicalMapping.utf8)
        let mac = HMAC<SHA256>.authenticationCode(for: nfc, using: SymmetricKey(data: slugKey))
        return Data(mac)
    }

    // ---- Per-method KEKs ----
    static func argon2id(input: Data, salt: Data, params: ArgonParams) throws -> Data {
        // libsodium crypto_pwhash: opsLimit = t, memLimit = m KiB * 1024 (bytes), p is fixed at 1.
        let sodium = Sodium()
        guard let out = sodium.pwHash.hash(
            outputLength: params.dkLen,
            passwd: [UInt8](input),
            salt: [UInt8](salt),
            opsLimit: params.t,
            memLimit: params.m * 1024,
            alg: .Argon2ID13
        ) else {
            throw NSError(domain: "veyq.crypto", code: 1, userInfo: [NSLocalizedDescriptionKey: "argon2id failed"])
        }
        return Data(out)
    }
    public static func kekPasswordSecret(secretKey: Data, password: String, salt: Data, params: ArgonParams) throws -> Data {
        let input = lp(secretKey) + lp(Data(password.precomposedStringWithCanonicalMapping.utf8))
        return try argon2id(input: input, salt: salt, params: params)
    }
    public static func kekRecovery(recoveryCode: Data, salt: Data, params: ArgonParams) throws -> Data {
        try argon2id(input: recoveryCode, salt: salt, params: params)
    }
    public static func kekPasskey(prfOutput: Data) -> Data {
        hkdf(ikm: prfOutput, salt: Data(), info: Data("veyq/v1/passkey-kek".utf8), len: 32)
    }

    // ---- Identity generation ----
    public static func randomBytes(_ n: Int) -> Data {
        Data((0..<n).map { _ in UInt8.random(in: UInt8.min...UInt8.max) })
    }

    public struct Identity {
        public let pubKemX: Data
        public let pubKemM: Data
        public let pubSign: Data
        public let privKemX: Data
        public let mlkemSeed: Data
        public let privSign: Data
    }

    public static func generateIdentity() throws -> Identity {
        let xPriv = Curve25519.KeyAgreement.PrivateKey()
        let mlkemSeed = randomBytes(64)
        let mPriv = try MLKEM768.PrivateKey(seedRepresentation: mlkemSeed, publicKey: nil)
        let sPriv = Curve25519.Signing.PrivateKey()
        return Identity(
            pubKemX: xPriv.publicKey.rawRepresentation,
            pubKemM: mPriv.publicKey.rawRepresentation,
            pubSign: sPriv.publicKey.rawRepresentation,
            privKemX: xPriv.rawRepresentation,
            mlkemSeed: mlkemSeed,
            privSign: sPriv.rawRepresentation
        )
    }

    public static func serializeIdentityPrivate(_ id: Identity) -> Data {
        lp(id.privKemX) + lp(id.mlkemSeed) + lp(id.privSign)
    }

    public struct IdentityPrivate { public let kemX: Data; public let mlkemSeed: Data; public let sign: Data }

    /// Inverse of serializeIdentityPrivate: read three length-prefixed fields.
    public static func deserializeIdentityPrivate(_ bytes: Data) -> IdentityPrivate {
        var o = bytes.startIndex
        func next() -> Data {
            let len = Int(bytes[o]) << 24 | Int(bytes[o + 1]) << 16 | Int(bytes[o + 2]) << 8 | Int(bytes[o + 3])
            o += 4
            let v = bytes.subdata(in: o ..< o + len)
            o += len
            return v
        }
        return IdentityPrivate(kemX: next(), mlkemSeed: next(), sign: next())
    }
}
