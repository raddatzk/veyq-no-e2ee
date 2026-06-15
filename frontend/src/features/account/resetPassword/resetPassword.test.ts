import { describe, expect, it } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { buildCreateServerOwnerRequest } from '../../admin/setupServer/setupServer'

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}
const wrapOf = (req: { masterKeyWraps: Array<{ unlockMethod: string; wrappedKey: string; kdfParams?: string }> }, m: string) =>
  req.masterKeyWraps.find((w) => w.unlockMethod === m)!

// Mirrors confirmPasswordReset's crypto without the network: open the RECOVERY wrap with the Secret Key,
// then re-wrap the master key under a new password — exactly the reset flow.
describe('password reset crypto', () => {
  const { request, emergencyKit } = buildCreateServerOwnerRequest('u@example.com', 'U', 'old password')
  const secretKey = fromB64url(emergencyKit.secretKey)

  it('registration produces both a PASSWORD and a RECOVERY master-key wrap', () => {
    expect(wrapOf(request, 'PASSWORD')).toBeTruthy()
    expect(wrapOf(request, 'RECOVERY')).toBeTruthy()
  }, 60000)

  it('the RECOVERY wrap opens with the Secret Key alone and yields the SAME master key as the PASSWORD wrap', () => {
    const pw = wrapOf(request, 'PASSWORD')
    const pwParams = v1.decodeKdfParams(fromB64url(pw.kdfParams!))
    const masterViaPassword = v1.symUnwrap(v1.kekPasswordSecret(secretKey, 'old password', pwParams.salt, pwParams.params), fromB64url(pw.wrappedKey), 'master-key')

    const rec = wrapOf(request, 'RECOVERY')
    const recParams = v1.decodeKdfParams(fromB64url(rec.kdfParams!))
    const masterViaRecovery = v1.symUnwrap(v1.kekRecovery(secretKey, recParams.salt, recParams.params), fromB64url(rec.wrappedKey), 'master-key')

    expect([...masterViaRecovery]).toEqual([...masterViaPassword])
  }, 60000)

  it('re-wrapping the recovered master key under a NEW password round-trips (and the old password no longer opens it)', () => {
    const rec = wrapOf(request, 'RECOVERY')
    const recParams = v1.decodeKdfParams(fromB64url(rec.kdfParams!))
    const master = v1.symUnwrap(v1.kekRecovery(secretKey, recParams.salt, recParams.params), fromB64url(rec.wrappedKey), 'master-key')

    const newSalt = v1.randomBytes(16)
    const newWrap = v1.symWrap(v1.kekPasswordSecret(secretKey, 'a brand new password', newSalt, v1.ARGON), master, 'master-key', v1.randomBytes(12))

    // the new password opens it...
    expect([...v1.symUnwrap(v1.kekPasswordSecret(secretKey, 'a brand new password', newSalt, v1.ARGON), newWrap, 'master-key')]).toEqual([...master])
    // ...the old password does not
    expect(() => v1.symUnwrap(v1.kekPasswordSecret(secretKey, 'old password', newSalt, v1.ARGON), newWrap, 'master-key')).toThrow()
  }, 60000)
})
