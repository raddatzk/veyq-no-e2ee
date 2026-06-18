import { describe, expect, it } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'

const EMPTY = new Uint8Array(0)

// Mirrors createShare({ password }) / resolvePublicShare({ password }) crypto without the network:
// the content key is wrapped under shareWrapKey(shareKey, password, salt); only the right passphrase
// re-derives the wrap key and opens it (a wrong/empty passphrase is an AEAD failure → wrong-password).
describe('password-protected share wrap', () => {
  const shareKey = v1.randomBytes(32)
  const contentKey = v1.randomBytes(32)
  const salt = v1.randomBytes(16)
  const wrapKey = v1.shareWrapKey(shareKey, 'correct horse battery', salt)
  const nonce = v1.randomBytes(12)
  const wrapped = v1.concat(nonce, v1.aeadSeal(wrapKey, nonce, contentKey, EMPTY))

  it('opens the content key under the right passphrase', () => {
    const k = v1.shareWrapKey(shareKey, 'correct horse battery', salt)
    const out = v1.aeadOpen(k, wrapped.slice(0, 12), wrapped.slice(12), EMPTY)
    expect([...out]).toEqual([...contentKey])
  }, 60000)

  it('fails (throws) under a wrong passphrase', () => {
    const k = v1.shareWrapKey(shareKey, 'wrong passphrase', salt)
    expect(() => v1.aeadOpen(k, wrapped.slice(0, 12), wrapped.slice(12), EMPTY)).toThrow()
  }, 60000)

  it('fails (throws) under an empty passphrase', () => {
    const k = v1.shareWrapKey(shareKey, '', salt)
    expect(() => v1.aeadOpen(k, wrapped.slice(0, 12), wrapped.slice(12), EMPTY)).toThrow()
  }, 60000)
})
