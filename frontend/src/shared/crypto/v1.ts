// veyq v1 crypto suite — web (TS). Conformance: docs/api/crypto-vectors/v1.json (see crypto-spec.md).
import { x25519 } from '@noble/curves/ed25519.js'
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js'
import { hkdf } from '@noble/hashes/hkdf.js'
import { sha256 } from '@noble/hashes/sha2.js'
import { argon2id } from '@noble/hashes/argon2.js'
import { gcm } from '@noble/ciphers/aes.js'

const te = new TextEncoder()
export const utf8 = (s: string): Uint8Array => te.encode(s.normalize('NFC'))

export function concat(...arrs: Uint8Array[]): Uint8Array {
  const total = arrs.reduce((n, a) => n + a.length, 0)
  const out = new Uint8Array(total)
  let o = 0
  for (const a of arrs) {
    out.set(a, o)
    o += a.length
  }
  return out
}

const u32be = (n: number): Uint8Array => {
  const b = new Uint8Array(4)
  new DataView(b.buffer).setUint32(0, n >>> 0, false)
  return b
}
/** length-prefixed: u32(len) || bytes */
export const lp = (x: Uint8Array): Uint8Array => concat(u32be(x.length), x)

export type ArgonParams = { t: number; m: number; p: number; dkLen: number }
/** production default: m in KiB = 64 MiB (vectors use lighter params, carried in the vector) */
export const ARGON: ArgonParams = { t: 3, m: 65536, p: 1, dkLen: 32 }

export type HybridPublic = { x: Uint8Array; m: Uint8Array }
export type HybridSecret = { x: Uint8Array; m: Uint8Array }

export const aeadSeal = (key: Uint8Array, nonce: Uint8Array, pt: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).encrypt(pt)
export const aeadOpen = (key: Uint8Array, nonce: Uint8Array, ct: Uint8Array, aad: Uint8Array): Uint8Array =>
  gcm(key, nonce, aad).decrypt(ct)

function combiner(
  ssM: Uint8Array,
  ssX: Uint8Array,
  ctM: Uint8Array,
  epkX: Uint8Array,
  pkM: Uint8Array,
  pkX: Uint8Array,
): Uint8Array {
  const ikm = concat(lp(ssM), lp(ssX))
  const salt = sha256(concat(lp(ctM), lp(epkX), lp(pkM), lp(pkX)))
  return hkdf(sha256, ikm, salt, utf8('veyq/v1/kem'), 32)
}

export function kemEncap(
  pk: HybridPublic,
  opts: { eskX?: Uint8Array; mlkemMsg?: Uint8Array } = {},
): { ct: Uint8Array; ss: Uint8Array } {
  const esk = opts.eskX ?? crypto.getRandomValues(new Uint8Array(32))
  const epkX = x25519.getPublicKey(esk)
  const ssX = x25519.getSharedSecret(esk, pk.x)
  const enc = ml_kem768.encapsulate(pk.m, opts.mlkemMsg)
  const ss = combiner(enc.sharedSecret, ssX, enc.cipherText, epkX, pk.m, pk.x)
  return { ct: concat(epkX, enc.cipherText), ss }
}

export function kemDecap(sk: HybridSecret, pk: HybridPublic, ct: Uint8Array): Uint8Array {
  const epkX = ct.slice(0, 32)
  const ctM = ct.slice(32)
  const ssX = x25519.getSharedSecret(sk.x, epkX)
  const ssM = ml_kem768.decapsulate(ctM, sk.m)
  return combiner(ssM, ssX, ctM, epkX, pk.m, pk.x)
}

/** §5b symmetric wrap: 0x02 || nonce(12) || aead */
export function symWrap(kek: Uint8Array, targetKey: Uint8Array, context: string, nonce: Uint8Array): Uint8Array {
  return concat(Uint8Array.of(0x02), nonce, aeadSeal(kek, nonce, targetKey, utf8('v1' + context)))
}
export function symUnwrap(kek: Uint8Array, blob: Uint8Array, context: string): Uint8Array {
  return aeadOpen(kek, blob.slice(1, 13), blob.slice(13), utf8('v1' + context))
}

/** §5a asymmetric wrap: 0x01 || ct(1120) || nonce(12) || aead */
export function asymWrap(
  pk: HybridPublic,
  targetKey: Uint8Array,
  context: string,
  opts: { eskX?: Uint8Array; mlkemMsg?: Uint8Array; nonce: Uint8Array },
): Uint8Array {
  const { ct, ss } = kemEncap(pk, opts)
  const kek = hkdf(sha256, ss, new Uint8Array(0), utf8('veyq/v1/wrap' + context), 32)
  return concat(Uint8Array.of(0x01), ct, opts.nonce, aeadSeal(kek, opts.nonce, targetKey, utf8('v1' + context)))
}
export function asymUnwrap(sk: HybridSecret, pk: HybridPublic, blob: Uint8Array, context: string): Uint8Array {
  const ct = blob.slice(1, 1 + 1120)
  const nonce = blob.slice(1 + 1120, 1 + 1120 + 12)
  const aead = blob.slice(1 + 1120 + 12)
  const ss = kemDecap(sk, pk, ct)
  const kek = hkdf(sha256, ss, new Uint8Array(0), utf8('veyq/v1/wrap' + context), 32)
  return aeadOpen(kek, nonce, aead, utf8('v1' + context))
}

export const kekPasswordSecret = (
  secretKey: Uint8Array,
  password: string,
  salt: Uint8Array,
  params: ArgonParams = ARGON,
): Uint8Array => argon2id(concat(lp(secretKey), lp(utf8(password))), salt, params)

export const kekRecovery = (recoveryCode: Uint8Array, salt: Uint8Array, params: ArgonParams = ARGON): Uint8Array =>
  argon2id(recoveryCode, salt, params)

export const kekPasskey = (prfOutput: Uint8Array): Uint8Array =>
  hkdf(sha256, prfOutput, new Uint8Array(0), utf8('veyq/v1/passkey-kek'), 32)
