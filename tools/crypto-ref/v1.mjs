// Reference implementation of the veyq v1 crypto suite (see docs/wiki/crosscutting/crypto-spec.md).
// Used only to generate conformance test vectors; NOT production code.
import { x25519 } from "@noble/curves/ed25519.js";
import { ml_kem768 } from "@noble/post-quantum/ml-kem.js";
import { hkdf } from "@noble/hashes/hkdf.js";
import { sha256 } from "@noble/hashes/sha2.js";
import { argon2id } from "@noble/hashes/argon2.js";
import { gcm } from "@noble/ciphers/aes.js";

const te = new TextEncoder();
export const utf8 = (s) => te.encode(s.normalize("NFC"));
export const concat = (...arrs) => {
  const total = arrs.reduce((n, a) => n + a.length, 0);
  const out = new Uint8Array(total);
  let o = 0;
  for (const a of arrs) { out.set(a, o); o += a.length; }
  return out;
};
const u32be = (n) => {
  const b = new Uint8Array(4);
  new DataView(b.buffer).setUint32(0, n >>> 0, false);
  return b;
};
// length-prefixed: u32(len) || bytes
export const lp = (x) => concat(u32be(x.length), x);

// ---- AEAD: AES-256-GCM (12-byte nonce, tag appended) ----
export const aeadSeal = (key, nonce, plaintext, aad) => gcm(key, nonce, aad).encrypt(plaintext);
export const aeadOpen = (key, nonce, ciphertext, aad) => gcm(key, nonce, aad).decrypt(ciphertext);

// ---- Hybrid KEM (KEM-v1): X25519 + ML-KEM-768, HKDF-SHA256 combiner ----
// Deterministic-friendly: ephemeral x25519 secret and ml-kem message are passed in for vectors.
export function kemEncap(pk, { eskX, mlkemMsg } = {}) {
  const { x: pkX, m: pkM } = pk;
  const esk = eskX ?? x25519.utils.randomSecretKey();
  const epkX = x25519.getPublicKey(esk);
  const ssX = x25519.getSharedSecret(esk, pkX);
  const enc = ml_kem768.encapsulate(pkM, mlkemMsg);
  const ctM = enc.cipherText;
  const ssM = enc.sharedSecret;
  const ss = combiner(ssM, ssX, ctM, epkX, pkM, pkX);
  const ct = concat(epkX, ctM);
  return { ct, ss };
}

export function kemDecap(sk, pk, ct) {
  const { x: skX, m: skM } = sk;
  const { x: pkX, m: pkM } = pk;
  const epkX = ct.slice(0, 32);
  const ctM = ct.slice(32);
  const ssX = x25519.getSharedSecret(skX, epkX);
  const ssM = ml_kem768.decapsulate(ctM, skM);
  return combiner(ssM, ssX, ctM, epkX, pkM, pkX);
}

function combiner(ssM, ssX, ctM, epkX, pkM, pkX) {
  const ikm = concat(lp(ssM), lp(ssX));
  const salt = sha256(concat(lp(ctM), lp(epkX), lp(pkM), lp(pkX)));
  return hkdf(sha256, ikm, salt, utf8("veyq/v1/kem"), 32);
}

export function kemKeygen({ mlkemSeed, x25519Secret }) {
  const km = ml_kem768.keygen(mlkemSeed);
  const skX = x25519Secret;
  const pkX = x25519.getPublicKey(skX);
  return {
    pk: { x: pkX, m: km.publicKey },
    sk: { x: skX, m: km.secretKey },
  };
}

// ---- Wraps ----
// 5a: asymmetric wrap to a hybrid public key.  blob = 0x01 || ct(1120) || nonce(12) || aead
export function asymWrap(pk, targetKey, context, { eskX, mlkemMsg, nonce }) {
  const { ct, ss } = kemEncap(pk, { eskX, mlkemMsg });
  const kek = hkdf(sha256, ss, new Uint8Array(0), utf8("veyq/v1/wrap" + context), 32);
  const aead = aeadSeal(kek, nonce, targetKey, utf8("v1" + context));
  return concat(Uint8Array.of(0x01), ct, nonce, aead);
}
export function asymUnwrap(sk, pk, blob, context) {
  const ct = blob.slice(1, 1 + 1120);
  const nonce = blob.slice(1 + 1120, 1 + 1120 + 12);
  const aead = blob.slice(1 + 1120 + 12);
  const ss = kemDecap(sk, pk, ct);
  const kek = hkdf(sha256, ss, new Uint8Array(0), utf8("veyq/v1/wrap" + context), 32);
  return aeadOpen(kek, nonce, aead, utf8("v1" + context));
}

// 5b: symmetric wrap under a KEK.  blob = 0x02 || nonce(12) || aead
export function symWrap(kek, targetKey, context, nonce) {
  const aead = aeadSeal(kek, nonce, targetKey, utf8("v1" + context));
  return concat(Uint8Array.of(0x02), nonce, aead);
}
export function symUnwrap(kek, blob, context) {
  const nonce = blob.slice(1, 13);
  const aead = blob.slice(13);
  return aeadOpen(kek, nonce, aead, utf8("v1" + context));
}

// ---- Per-method KEK derivations ----
const ARGON = { t: 3, m: 65536, p: 1, dkLen: 32 }; // production default: m in KiB = 64 MiB
export const kekPasswordSecret = (secretKey, password, salt, params = ARGON) =>
  argon2id(concat(lp(secretKey), lp(utf8(password))), salt, params);
export const kekRecovery = (recoveryCode, salt, params = ARGON) =>
  argon2id(recoveryCode, salt, params);
export const kekPasskey = (prfOutput) =>
  hkdf(sha256, prfOutput, new Uint8Array(0), utf8("veyq/v1/passkey-kek"), 32);

export const ARGON_PARAMS = ARGON;
