// Generates the shared conformance test vectors for the veyq v1 crypto suite.
// Run: npm run generate  (writes ../../docs/api/crypto-vectors/v1.json)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as v1 from "./v1.mjs";
import { sha256 } from "@noble/hashes/sha2.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { gcm } from "@noble/ciphers/aes.js";

const hex = (u8) => Buffer.from(u8).toString("hex");
const unhex = (s) => Uint8Array.from(Buffer.from(s, "hex"));
const eq = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);
let checks = 0;
const assertEq = (a, b, what) => {
  checks++;
  if (!eq(a, b)) throw new Error(`SELF-TEST FAILED: ${what}`);
};

// ---- fixed, deterministic inputs ----
const x25519Secret = unhex("01".repeat(32));
const mlkemSeed = unhex("02".repeat(64));
const eskX = unhex("03".repeat(32));
const mlkemMsg = unhex("04".repeat(32));
const nonce = unhex("0a0b0c0d0e0f101112131415"); // 12 bytes
const salt = unhex("a0".repeat(16));
const password = "correct horse battery staple";
const secretKey = unhex("11".repeat(32));
const recoveryCode = unhex("22".repeat(16)); // 128-bit
const prfOutput = unhex("33".repeat(32));
const masterKey = unhex("44".repeat(32));
// light Argon2 params for fast cross-stack conformance (production default is 64 MiB; see crypto-spec)
const argonVec = { t: 3, m: 8192, p: 1, dkLen: 32 };

// ---- key material ----
const { pk, sk } = v1.kemKeygen({ mlkemSeed, x25519Secret });

// ---- KEM-v1: deterministic encap -> the Decap KAT ----
const { ct, ss } = v1.kemEncap(pk, { eskX, mlkemMsg });
assertEq(v1.kemDecap(sk, pk, ct), ss, "KEM-v1 encap/decap roundtrip");

// ---- per-method KEKs ----
const kekPw = v1.kekPasswordSecret(secretKey, password, salt, argonVec);
const kekRec = v1.kekRecovery(recoveryCode, salt, argonVec);
const kekPk = v1.kekPasskey(prfOutput);

// ---- wraps of the master key ----
const symBlob = v1.symWrap(kekPw, masterKey, "master-key", nonce);
assertEq(v1.symUnwrap(kekPw, symBlob, "master-key"), masterKey, "symWrap roundtrip");

const asymBlob = v1.asymWrap(pk, masterKey, "scope-grant", { eskX, mlkemMsg, nonce });
assertEq(v1.asymUnwrap(sk, pk, asymBlob, "scope-grant"), masterKey, "asymWrap roundtrip");

// ---- borrowed primitive KAT: HKDF-SHA256, RFC 5869 test case 1 ----
const hkdfKat = {
  ikm: "0b".repeat(22),
  salt: "000102030405060708090a0b0c",
  info: "f0f1f2f3f4f5f6f7f8f9",
  length: 42,
  okm: hex(hkdf(sha256, unhex("0b".repeat(22)), unhex("000102030405060708090a0b0c"), unhex("f0f1f2f3f4f5f6f7f8f9"), 42)),
};
const RFC5869_OKM1 = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865";
if (hkdfKat.okm !== RFC5869_OKM1) throw new Error("SELF-TEST FAILED: HKDF does not match RFC 5869 vector");
checks++;

// ---- raw AEAD KAT ----
const aeadKey = unhex("55".repeat(32));
const aeadPt = v1.utf8("hello veyq");
const aeadAad = v1.utf8("v1/test");
const aeadCt = v1.aeadSeal(aeadKey, nonce, aeadPt, aeadAad);
assertEq(v1.aeadOpen(aeadKey, nonce, aeadCt, aeadAad), aeadPt, "AES-GCM roundtrip");

const vectors = {
  suite: "v1",
  note: "Conformance vectors for the veyq v1 crypto suite. Bytes are lowercase hex. Argon2 here uses light params (m=8 MiB) for fast conformance; production uses 64 MiB (see crypto-spec.md). ML-KEM/X25519 encapsulation is randomized in practice, so the KEM is conformance-tested via the deterministic Decap KAT plus client-internal encap->decap round-trips.",
  primitives: {
    hkdf_sha256_rfc5869_case1: hkdfKat,
    aes256gcm: {
      key: hex(aeadKey), nonce: hex(nonce), aad: hex(aeadAad),
      plaintext: hex(aeadPt), ciphertext_and_tag: hex(aeadCt),
    },
  },
  kek: {
    password_secret_argon2id: {
      params: argonVec,
      secret_key: hex(secretKey), password_utf8: password, salt: hex(salt),
      kek: hex(kekPw),
    },
    recovery_argon2id: {
      params: argonVec,
      recovery_code: hex(recoveryCode), salt: hex(salt), kek: hex(kekRec),
    },
    passkey_prf_hkdf: { prf_output: hex(prfOutput), kek: hex(kekPk) },
  },
  kem_v1: {
    decap_kat: {
      sk_x25519: hex(sk.x), sk_mlkem: hex(sk.m),
      pk_x25519: hex(pk.x), pk_mlkem: hex(pk.m),
      ct: hex(ct),
      shared_secret: hex(ss),
    },
  },
  wrap: {
    sym_master_key: {
      kek: hex(kekPw), context: "master-key", nonce: hex(nonce),
      plaintext: hex(masterKey), blob: hex(symBlob),
    },
    asym_scope_grant: {
      note: "asymmetric wrap is randomized via encap; verify by round-trip (unwrap with sk -> plaintext). The recorded blob is one valid instance.",
      pk_x25519: hex(pk.x), pk_mlkem: hex(pk.m), context: "scope-grant",
      plaintext: hex(masterKey), blob: hex(asymBlob),
    },
  },
};

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(here, "../../docs/api/crypto-vectors");
fs.mkdirSync(outDir, { recursive: true });
const outFile = path.join(outDir, "v1.json");
fs.writeFileSync(outFile, JSON.stringify(vectors, null, 2) + "\n");
console.log(`✓ ${checks} self-tests passed`);
console.log(`✓ wrote ${path.relative(process.cwd(), outFile)}`);
console.log(`  ML-KEM-768 pk ${pk.m.length} B, KEM-v1 ct ${ct.length} B, ss ${ss.length} B`);
