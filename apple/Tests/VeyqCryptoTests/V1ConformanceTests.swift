import Foundation
import XCTest

@testable import VeyqCrypto

/// Conformance of the Apple `v1` auth/unlock subset against the SHARED vectors
/// (`contracts/crypto/vectors/v1.json`, ADR 0009) — the same file the web TS suite is pinned to, so a green
/// run here proves cross-implementation byte-parity. Includes the hybrid KEM + asymWrap + identity/fingerprint
/// (cr-0025), the slug token (cr-0027) and the chunked-AEAD frame (cr-0028); journal/dedup land with their slices.
final class V1ConformanceTests: XCTestCase {

    private var vectors: [String: Any]!

    override func setUpWithError() throws {
        // apple/Tests/VeyqCryptoTests/<this file> → up 4 → repo root → contracts/crypto/vectors/v1.json
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent("contracts/crypto/vectors/v1.json")
        let data = try Data(contentsOf: url)
        vectors = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    func testHkdfSha256() throws {
        let v = sub(["primitives", "hkdf_sha256_rfc5869_case1"])
        let okm = V1.hkdfSha256(ikm: hex(v["ikm"]), salt: hex(v["salt"]), info: hex(v["info"]), length: v["length"] as! Int)
        XCTAssertEqual(okm, hex(v["okm"]))
    }

    func testAes256Gcm() throws {
        let v = sub(["primitives", "aes256gcm"])
        let sealed = V1.aeadSeal(key: hex(v["key"]), nonce: hex(v["nonce"]), plaintext: hex(v["plaintext"]), aad: hex(v["aad"]))
        XCTAssertEqual(sealed, hex(v["ciphertext_and_tag"]))
        let opened = try V1.aeadOpen(key: hex(v["key"]), nonce: hex(v["nonce"]), ciphertextAndTag: sealed, aad: hex(v["aad"]))
        XCTAssertEqual(opened, hex(v["plaintext"]))
    }

    func testKekPasswordSecret() throws {
        let v = sub(["kek", "password_secret_argon2id"])
        let kek = V1.kekPasswordSecret(secretKey: hex(v["secret_key"]), password: v["password_utf8"] as! String, salt: hex(v["salt"]), params: params(v))
        XCTAssertEqual(kek, hex(v["kek"]))
    }

    func testKekDevicePassword() throws {
        let v = sub(["kek", "device_password_argon2id"])
        let kek = V1.kekDevicePassword(deviceFactor: hex(v["device_factor"]), password: v["password_utf8"] as! String, salt: hex(v["salt"]), params: params(v))
        XCTAssertEqual(kek, hex(v["kek"]))
    }

    func testKekRecovery() throws {
        for key in ["recovery_argon2id", "recovery_argon2id_m32768"] {
            let v = sub(["kek", key])
            let kek = V1.kekRecovery(recoveryCode: hex(v["recovery_code"]), salt: hex(v["salt"]), params: params(v))
            XCTAssertEqual(kek, hex(v["kek"]), "kek vector \(key)")
        }
    }

    func testKdfParamsRoundTrip() throws {
        let v = sub(["kdf_params"])
        let p = params(v)
        let encoded = V1.encodeKdfParams(p, salt: hex(v["salt"]))
        XCTAssertEqual(encoded, hex(v["encoded"]))
        let (dp, salt) = try V1.decodeKdfParams(encoded)
        XCTAssertEqual([dp.t, dp.m, dp.p, dp.dkLen], [p.t, p.m, p.p, p.dkLen])
        XCTAssertEqual(salt, hex(v["salt"]))
    }

    func testSymWrap() throws {
        for key in ["sym_master_key", "session_token", "sym_identity_pins", "sym_invite_temp_key"] {
            let v = sub(["wrap", key])
            let blob = V1.symWrap(kek: hex(v["kek"]), targetKey: hex(v["plaintext"]), context: v["context"] as! String, nonce: hex(v["nonce"]))
            XCTAssertEqual(blob, hex(v["blob"]), "symWrap vector \(key)")
            let opened = try V1.symUnwrap(kek: hex(v["kek"]), blob: blob, context: v["context"] as! String)
            XCTAssertEqual(opened, hex(v["plaintext"]), "symUnwrap vector \(key)")
        }
    }

    func testRecoveryAuthTag() throws {
        let v = sub(["recovery_auth_tag"])
        XCTAssertEqual(V1.recoveryAuthTag(masterKey: hex(v["masterKey"])), hex(v["tag"]))
    }

    /// auth_salt + auth_keypair pinned against the SHARED vectors (ADR 0003) — byte-parity with web/android;
    /// plus a sign/verify round-trip on the vectored keypair to pin the authSign/authVerify wiring.
    func testAuthSaltKeypairAndSign() throws {
        let s = sub(["auth", "auth_salt"])
        XCTAssertEqual(V1.deriveAuthSalt(instanceSalt: hex(s["instance_salt"]), email: s["email_utf8"] as! String), hex(s["auth_salt"]))

        let k = sub(["auth", "auth_keypair"])
        let kp = V1.deriveAuthKeypair(password: k["password_utf8"] as! String, authSalt: hex(k["auth_salt"]), params: params(k))
        XCTAssertEqual(kp.privateKey, hex(k["private_key"]))
        XCTAssertEqual(kp.publicKey, hex(k["public_key"]))

        let msg = Data("login-nonce".utf8)
        let sig = V1.authSign(privateKey: kp.privateKey, message: msg)
        XCTAssertTrue(V1.authVerify(publicKey: kp.publicKey, message: msg, signature: sig))
        XCTAssertFalse(V1.authVerify(publicKey: kp.publicKey, message: Data("other".utf8), signature: sig))
    }

    // MARK: - hybrid KEM + asymWrap + identity (cr-0025)

    /// Deterministic decapsulation KAT: reconstruct the secret from the 64-byte ML-KEM seed + X25519 secret
    /// and recover the recorded shared secret.
    func testKemDecapKat() throws {
        let d = sub(["kem_v1", "decap_kat"])
        let sk = V1.HybridSecret(x: hex(d["sk_x25519"]), m: hex(d["mlkem_seed"]))
        let pk = V1.HybridPublic(x: hex(d["pk_x25519"]), m: hex(d["pk_mlkem"]))
        let ss = try V1.kemDecap(sk, pk, ct: hex(d["ct"]))
        XCTAssertEqual(ss, hex(d["shared_secret"]))
    }

    /// Unwrap the recorded `asym_scope_grant` blob (asym wrap is randomized via encap, but DECAP is
    /// deterministic given the recorded ct — so the fixed blob round-trips with the kem_v1 secret).
    func testAsymScopeGrantUnwrap() throws {
        let a = sub(["wrap", "asym_scope_grant"])
        let d = sub(["kem_v1", "decap_kat"])
        let sk = V1.HybridSecret(x: hex(d["sk_x25519"]), m: hex(d["mlkem_seed"]))
        let pk = V1.HybridPublic(x: hex(a["pk_x25519"]), m: hex(a["pk_mlkem"]))
        let recovered = try V1.asymUnwrap(sk: sk, pk: pk, blob: hex(a["blob"]), context: a["context"] as! String)
        XCTAssertEqual(recovered, hex(a["plaintext"]))
    }

    /// Locally generated identity: encap→decap round-trips, and a wrong context fails (AAD + HKDF binding).
    func testAsymWrapRoundTrip() throws {
        let id = V1.generateIdentity()
        let pk = try V1.hybridPublic(id.priv)
        let sk = V1.hybridSecret(id.priv)
        let plaintext = Data((0 ..< 32).map { UInt8($0) })
        let blob = try V1.asymWrap(pk: pk, targetKey: plaintext, context: "scope-grant",
                                   nonce: Data((0 ..< 12).map { UInt8($0) }))
        XCTAssertEqual(try V1.asymUnwrap(sk: sk, pk: pk, blob: blob, context: "scope-grant"), plaintext)
        XCTAssertThrowsError(try V1.asymUnwrap(sk: sk, pk: pk, blob: blob, context: "other"))
    }

    func testIdentitySerializeRoundTrip() throws {
        let id = V1.generateIdentity()
        let priv = try V1.deserializeIdentityPrivate(V1.serializeIdentityPrivate(id.priv))
        XCTAssertEqual(priv.kemX, id.priv.kemX)
        XCTAssertEqual(priv.mlkemSeed, id.priv.mlkemSeed)
        XCTAssertEqual(priv.sign, id.priv.sign)
        XCTAssertEqual(V1.serializeIdentityPublic(id.pub).count, (4 + 32) + (4 + 1184) + (4 + 32))
    }

    func testFingerprint() throws {
        let f = sub(["fingerprint"])
        let pub = V1.IdentityPublic(kemX: hex(f["kem_x"]), kemM: hex(f["kem_m"]), sign: hex(f["sign"]))
        XCTAssertEqual(V1.fingerprint(pub), f["digits"] as! String)
    }

    func testSlugToken() throws {
        let v = sub(["slug_token"])
        let token = V1.slugToken(contentKey: hex(v["contentKey"]), parentSlot: v["parentSlot"] as! String, slug: v["slug"] as! String)
        XCTAssertEqual(token, hex(v["token"]))
    }

    func testChunkFrame() throws {
        let v = sub(["chunk_frame"])
        let frame = V1.sealFrame(contentKey: hex(v["contentKey"]), index: v["index"] as! Int, total: v["total"] as! Int,
                                 plaintext: hex(v["plaintext"]), nonce: hex(v["nonce"]))
        XCTAssertEqual(frame, hex(v["frame"]))
        let opened = try V1.openFrame(contentKey: hex(v["contentKey"]), index: v["index"] as! Int, total: v["total"] as! Int, frame: frame)
        XCTAssertEqual(opened, hex(v["plaintext"]))
    }

    // MARK: - helpers

    private func sub(_ path: [String]) -> [String: Any] {
        var node: Any = vectors!
        for key in path { node = (node as! [String: Any])[key]! }
        return node as! [String: Any]
    }

    private func params(_ v: [String: Any]) -> V1.ArgonParams {
        let p = v["params"] as! [String: Any]
        return V1.ArgonParams(t: p["t"] as! Int, m: p["m"] as! Int, p: p["p"] as! Int, dkLen: p["dkLen"] as! Int)
    }

    private func hex(_ any: Any?) -> Data {
        let s = any as! String
        var out = Data(capacity: s.count / 2)
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            out.append(UInt8(s[idx ..< next], radix: 16)!)
            idx = next
        }
        return out
    }
}
