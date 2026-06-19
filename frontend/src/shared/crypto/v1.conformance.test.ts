import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as v1 from './v1'

const unhex = (s: string) => Uint8Array.from(Buffer.from(s, 'hex'))
const hex = (u: Uint8Array) => Buffer.from(u).toString('hex')

const here = dirname(fileURLToPath(import.meta.url))
// repo docs/api/crypto-vectors/v1.json (4 levels up from src/shared/crypto/)
const vectors = JSON.parse(
  readFileSync(resolve(here, '../../../../docs/api/crypto-vectors/v1.json'), 'utf8'),
)

describe('v1 crypto conformance (web TS vs shared vectors)', () => {
  it('targets suite v1', () => {
    expect(vectors.suite).toBe('v1')
  })

  it('HKDF-SHA256 (RFC 5869 case 1)', () => {
    const h = vectors.primitives.hkdf_sha256_rfc5869_case1
    expect(hex(v1.hkdfSha256(unhex(h.ikm), unhex(h.salt), unhex(h.info), h.length))).toBe(h.okm)
  })

  it('AES-256-GCM', () => {
    const p = vectors.primitives.aes256gcm
    expect(hex(v1.aeadSeal(unhex(p.key), unhex(p.nonce), unhex(p.plaintext), unhex(p.aad)))).toBe(
      p.ciphertext_and_tag,
    )
  })

  it('password + secret key KEK (Argon2id)', () => {
    const k = vectors.kek.password_secret_argon2id
    expect(hex(v1.kekPasswordSecret(unhex(k.secret_key), k.password_utf8, unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('recovery code KEK (Argon2id)', () => {
    const k = vectors.kek.recovery_argon2id
    expect(hex(v1.kekRecovery(unhex(k.recovery_code), unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('recovery KEK at PRODUCTION Argon2 params m=32 MiB (pins the invite-temp-key KEK)', () => {
    const k = vectors.kek.recovery_argon2id_m32768
    expect(hex(v1.kekRecovery(unhex(k.recovery_code), unhex(k.salt), k.params))).toBe(k.kek)
  })

  it('password-protected share wrap key (web-only by design)', () => {
    const s = vectors.kek.share_pw
    expect(hex(v1.shareWrapKey(unhex(s.share_key), s.password_utf8, unhex(s.salt)))).toBe(s.wrap_key)
  })

  it('kdfParams wire format (encode + decode round-trip)', () => {
    const k = vectors.kdf_params
    expect(hex(v1.encodeKdfParams(k.params, unhex(k.salt)))).toBe(k.encoded)
    const d = v1.decodeKdfParams(unhex(k.encoded))
    expect(d.params).toEqual(k.params)
    expect(hex(d.salt)).toBe(k.salt)
  })

  it('KEM-v1 decap (hybrid X25519 + ML-KEM-768)', () => {
    const d = vectors.kem_v1.decap_kat
    const ss = v1.kemDecap(
      { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
      { x: unhex(d.pk_x25519), m: unhex(d.pk_mlkem) },
      unhex(d.ct),
    )
    expect(hex(ss)).toBe(d.shared_secret)
  })

  it('symmetric master-key wrap', () => {
    const w = vectors.wrap.sym_master_key
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
  })

  it('symmetric wrap with the invite-temp-key context', () => {
    const w = vectors.wrap.sym_invite_temp_key
    expect(hex(v1.symWrap(unhex(w.kek), unhex(w.plaintext), w.context, unhex(w.nonce)))).toBe(w.blob)
  })

  it('asymmetric wrap round-trip (unwrap a recorded blob)', () => {
    const a = vectors.wrap.asym_scope_grant
    const d = vectors.kem_v1.decap_kat
    const recovered = v1.asymUnwrap(
      { x: unhex(d.sk_x25519), m: unhex(d.sk_mlkem) },
      { x: unhex(a.pk_x25519), m: unhex(a.pk_mlkem) },
      unhex(a.blob),
      a.context,
    )
    expect(hex(recovered)).toBe(a.plaintext)
  })

  it('blinded slug token (AD-26)', () => {
    const s = vectors.slug_token
    expect(hex(v1.slugToken(unhex(s.contentKey), s.parentSlot, s.slug))).toBe(s.token)
  })

  it('media dedup token (AD-09)', () => {
    const d = vectors.dedup_token
    expect(hex(v1.dedupToken(unhex(d.contentKey), unhex(d.content)))).toBe(d.token)
  })

  it('recovery commitment (password reset)', () => {
    const r = vectors.recovery_auth_tag
    expect(hex(v1.recoveryAuthTag(unhex(r.masterKey)))).toBe(r.tag)
  })

  it('journal seal (per-message sub-key)', () => {
    const j = vectors.journal_open
    expect(hex(v1.journalOpen(unhex(j.contentKey), unhex(j.blob)))).toBe(j.plaintext)
    // round-trip the SEAL side too (randomized salt/nonce, so we can't pin a fixed blob — but our own seal
    // must open to the same plaintext, exercising the seal-side HKDF/layout the fixed-blob vector can't).
    const sealed = v1.journalSeal(unhex(j.contentKey), unhex(j.plaintext))
    expect(hex(v1.journalOpen(unhex(j.contentKey), sealed))).toBe(j.plaintext)
  })

  it('chunked-AEAD frame (resumable upload / streaming)', () => {
    const f = vectors.chunk_frame
    const frame = v1.sealFrame(unhex(f.contentKey), f.index, f.total, unhex(f.plaintext), unhex(f.nonce))
    expect(hex(frame)).toBe(f.frame)
    expect(hex(v1.openFrame(unhex(f.contentKey), f.index, f.total, unhex(f.frame)))).toBe(f.plaintext)
  })

  it('identity fingerprint / safety number', () => {
    const f = vectors.fingerprint
    expect(v1.fingerprint({ kemX: unhex(f.kem_x), kemM: unhex(f.kem_m), sign: unhex(f.sign) })).toBe(f.digits)
  })
})
