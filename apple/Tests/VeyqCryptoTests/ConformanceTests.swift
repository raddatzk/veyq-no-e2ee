import XCTest
import Foundation
@testable import VeyqCrypto

final class ConformanceTests: XCTestCase {
    private func loadVectors() throws -> [String: Any] {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() } // file -> VeyqCryptoTests -> Tests -> apple -> repo root
        url.appendPathComponent("docs/api/crypto-vectors/v1.json")
        let data = try Data(contentsOf: url)
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    private func hex(_ s: String) -> Data {
        var out = Data()
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            out.append(UInt8(s[idx..<next], radix: 16)!)
            idx = next
        }
        return out
    }
    private func toHex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

    private func params(_ m: [String: Any]) -> V1.ArgonParams {
        let p = m["params"] as! [String: Any]
        return V1.ArgonParams(
            t: (p["t"] as! NSNumber).intValue, m: (p["m"] as! NSNumber).intValue,
            p: (p["p"] as! NSNumber).intValue, dkLen: (p["dkLen"] as! NSNumber).intValue
        )
    }

    func testConformance() throws {
        let v = try loadVectors()
        XCTAssertEqual(v["suite"] as? String, "v1")

        let prim = v["primitives"] as! [String: Any]
        let hk = prim["hkdf_sha256_rfc5869_case1"] as! [String: Any]
        XCTAssertEqual(
            toHex(V1.hkdf(ikm: hex(hk["ikm"] as! String), salt: hex(hk["salt"] as! String),
                          info: hex(hk["info"] as! String), len: (hk["length"] as! NSNumber).intValue)),
            hk["okm"] as! String, "HKDF-SHA256 RFC 5869")

        let aes = prim["aes256gcm"] as! [String: String]
        XCTAssertEqual(
            toHex(try V1.aeadSeal(key: hex(aes["key"]!), nonce: hex(aes["nonce"]!),
                                  plaintext: hex(aes["plaintext"]!), aad: hex(aes["aad"]!))),
            aes["ciphertext_and_tag"]!, "AES-256-GCM")

        let kek = v["kek"] as! [String: Any]

        let pw = kek["password_secret_argon2id"] as! [String: Any]
        XCTAssertEqual(
            toHex(try V1.kekPasswordSecret(secretKey: hex(pw["secret_key"] as! String),
                                           password: pw["password_utf8"] as! String,
                                           salt: hex(pw["salt"] as! String), params: params(pw))),
            pw["kek"] as! String, "password+secret KEK")

        let rec = kek["recovery_argon2id"] as! [String: Any]
        XCTAssertEqual(
            toHex(try V1.kekRecovery(recoveryCode: hex(rec["recovery_code"] as! String),
                                     salt: hex(rec["salt"] as! String), params: params(rec))),
            rec["kek"] as! String, "recovery KEK")

        let pk = kek["passkey_prf_hkdf"] as! [String: String]
        XCTAssertEqual(toHex(V1.kekPasskey(prfOutput: hex(pk["prf_output"]!))), pk["kek"]!, "passkey KEK")

        let d = (v["kem_v1"] as! [String: Any])["decap_kat"] as! [String: String]
        XCTAssertEqual(
            toHex(try V1.kemDecap(skX: hex(d["sk_x25519"]!), mlkemSeed: hex(d["mlkem_seed"]!),
                                  pkX: hex(d["pk_x25519"]!), pkM: hex(d["pk_mlkem"]!), ct: hex(d["ct"]!))),
            d["shared_secret"]!, "KEM-v1 decap")

        let wrap = v["wrap"] as! [String: Any]
        let sym = wrap["sym_master_key"] as! [String: String]
        XCTAssertEqual(
            toHex(try V1.symWrap(kek: hex(sym["kek"]!), targetKey: hex(sym["plaintext"]!),
                                 context: sym["context"]!, nonce: hex(sym["nonce"]!))),
            sym["blob"]!, "sym wrap")

        let asym = wrap["asym_scope_grant"] as! [String: String]
        XCTAssertEqual(
            toHex(try V1.asymUnwrap(skX: hex(d["sk_x25519"]!), mlkemSeed: hex(d["mlkem_seed"]!),
                                    pkX: hex(asym["pk_x25519"]!), pkM: hex(asym["pk_mlkem"]!),
                                    blob: hex(asym["blob"]!), context: asym["context"]!)),
            asym["plaintext"]!, "asym wrap round-trip")

        let slug = v["slug_token"] as! [String: String]
        XCTAssertEqual(
            toHex(V1.slugToken(contentKey: hex(slug["contentKey"]!), parentSlot: slug["parentSlot"]!, slug: slug["slug"]!)),
            slug["token"]!, "blinded slug token")

        let dd = v["dedup_token"] as! [String: String]
        XCTAssertEqual(
            toHex(V1.dedupToken(contentKey: hex(dd["contentKey"]!), content: hex(dd["content"]!))),
            dd["token"]!, "media dedup token")

        let rt = v["recovery_auth_tag"] as! [String: String]
        XCTAssertEqual(toHex(V1.recoveryAuthTag(masterKey: hex(rt["masterKey"]!))), rt["tag"]!, "recovery commitment")

        let jo = v["journal_open"] as! [String: String]
        XCTAssertEqual(toHex(V1.journalOpen(contentKey: hex(jo["contentKey"]!), blob: hex(jo["blob"]!))!), jo["plaintext"]!, "journal seal")
        // round-trip the SEAL side too (randomized salt/nonce → can't pin a fixed blob)
        let jSealed = V1.journalSeal(contentKey: hex(jo["contentKey"]!), plaintext: hex(jo["plaintext"]!))
        XCTAssertEqual(toHex(V1.journalOpen(contentKey: hex(jo["contentKey"]!), blob: jSealed)!), jo["plaintext"]!, "journal seal round-trip")

        let f = v["chunk_frame"] as! [String: Any]
        XCTAssertEqual(
            toHex(try V1.sealFrame(contentKey: hex(f["contentKey"] as! String), index: f["index"] as! Int, total: f["total"] as! Int,
                                   plaintext: hex(f["plaintext"] as! String), nonce: hex(f["nonce"] as! String))),
            f["frame"] as! String, "chunked-AEAD frame")
        XCTAssertEqual(
            toHex(try V1.openFrame(contentKey: hex(f["contentKey"] as! String), index: f["index"] as! Int, total: f["total"] as! Int,
                                   frame: hex(f["frame"] as! String))),
            f["plaintext"] as! String, "chunked-AEAD frame open")
    }
}
