import Foundation
import XCTest

@testable import VeyqCrypto

/// Conformance of the Apple `v1` auth/unlock subset against the SHARED vectors
/// (`contracts/crypto/vectors/v1.json`, ADR 0009) — the same file the web TS suite is pinned to, so a green
/// run here proves cross-implementation byte-parity. ML-KEM / content-side vectors are out of this CR's scope.
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
        for key in ["sym_master_key", "session_token"] {
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

    /// No v1.json auth vector yet; the keypair composes vectored Argon2id (seed) + standard Ed25519, so this
    /// pins the native wiring via a sign/verify round-trip (derived salt → keypair → signature).
    func testAuthKeypairRoundTrip() throws {
        let salt = V1.deriveAuthSalt(instanceSalt: Data(repeating: 7, count: 16), email: "  Owner@Example.com ")
        XCTAssertEqual(salt.count, 16)
        let kp = V1.deriveAuthKeypair(password: "correct horse battery staple", authSalt: salt)
        XCTAssertEqual(kp.publicKey.count, 32)
        let msg = Data("login-nonce".utf8)
        let sig = V1.authSign(privateKey: kp.privateKey, message: msg)
        XCTAssertTrue(V1.authVerify(publicKey: kp.publicKey, message: msg, signature: sig))
        XCTAssertFalse(V1.authVerify(publicKey: kp.publicKey, message: Data("other".utf8), signature: sig))
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
