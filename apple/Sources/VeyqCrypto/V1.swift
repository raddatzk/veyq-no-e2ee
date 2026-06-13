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

    // ---- Wraps ----
    public static func symWrap(kek: Data, targetKey: Data, context: String, nonce: Data) throws -> Data {
        Data([0x02]) + nonce + (try aeadSeal(key: kek, nonce: nonce, plaintext: targetKey, aad: Data(("v1" + context).utf8)))
    }
    public static func asymUnwrap(skX: Data, mlkemSeed: Data, pkX: Data, pkM: Data, blob: Data, context: String) throws -> Data {
        let body = Data(blob.dropFirst())
        let ct = Data(body.prefix(1120))
        let nonce = Data(body.dropFirst(1120).prefix(12))
        let aead = Data(body.dropFirst(1120 + 12))
        let ss = try kemDecap(skX: skX, mlkemSeed: mlkemSeed, pkX: pkX, pkM: pkM, ct: ct)
        let kek = hkdf(ikm: ss, salt: Data(), info: Data(("veyq/v1/wrap" + context).utf8), len: 32)
        return try aeadOpen(key: kek, nonce: nonce, aead: aead, aad: Data(("v1" + context).utf8))
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
}
