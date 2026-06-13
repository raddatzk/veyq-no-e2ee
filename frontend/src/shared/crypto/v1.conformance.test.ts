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

  it('passkey PRF KEK (HKDF)', () => {
    const k = vectors.kek.passkey_prf_hkdf
    expect(hex(v1.kekPasskey(unhex(k.prf_output)))).toBe(k.kek)
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
})
