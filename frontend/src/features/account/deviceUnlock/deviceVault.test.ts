// @vitest-environment node
import { describe, expect, it } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { openVault, sealVault } from './deviceVault'

describe('deviceVault', () => {
  const creds = { email: 'a@veyq.app', password: 'correct horse', secretKey: 'AAAA' }

  it('round-trips credentials under a device key', () => {
    const key = v1.randomBytes(32)
    expect(openVault(key, sealVault(key, creds))).toEqual(creds)
  })

  it('cannot be opened with a different device key (wrong biometric/passkey)', () => {
    const blob = sealVault(v1.randomBytes(32), creds)
    expect(() => openVault(v1.randomBytes(32), blob)).toThrow()
  })
})
