import { describe, expect, it } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { protectTempKey, unprotectTempKey } from './groups'
import { b64url } from '../../../shared/base64'

// b64url of a representative temp private key (any bytes — protect treats it opaquely).

describe('protectTempKey', () => {
  const tempKey = b64url(v1.randomBytes(120))

  it('round-trips the temp key under the right passphrase', () => {
    const prot = protectTempKey(tempKey, 'correct horse battery staple')
    expect(prot.encTempKey).not.toContain(tempKey) // ciphertext, not the key
    expect(unprotectTempKey(prot, 'correct horse battery staple')).toBe(tempKey)
  })

  it('fails (throws) under a wrong passphrase', () => {
    const prot = protectTempKey(tempKey, 'the real one')
    expect(() => unprotectTempKey(prot, 'a guess')).toThrow()
  })

  it('uses a fresh salt each time (same key+passphrase → different ciphertext)', () => {
    const a = protectTempKey(tempKey, 'pw')
    const b = protectTempKey(tempKey, 'pw')
    expect(a.pwSalt).not.toBe(b.pwSalt)
    expect(a.encTempKey).not.toBe(b.encTempKey)
  })
})
