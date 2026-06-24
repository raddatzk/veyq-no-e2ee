import { beforeEach, describe, expect, it } from 'vitest'
import * as v1 from '../../../shared/crypto/v1'
import { b64url } from '../../../shared/base64'
import {
  clearLegacyDeviceUnlockLogin,
  clearRememberedLogin,
  clearSessionUnlock,
  hasSessionUnlock,
  readRememberedLogin,
  readRememberedUnlock,
  readSessionUnlock,
  rememberedLoginEnabled,
  saveRememberedLogin,
  saveSessionUnlock
} from './rememberedLogin'
import type { UnlockedSession } from '../login/login'

describe('rememberedLogin', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('stores and reads email and Secret Key from localStorage', () => {
    saveRememberedLogin({ email: 'kevin@example.com', secretKey: 'veyq-secret' })

    expect(rememberedLoginEnabled()).toBe(true)
    expect(readRememberedLogin()).toEqual({ email: 'kevin@example.com', secretKey: 'veyq-secret' })
  })

  it('stores and restores the local unlock state with a fresh session token', () => {
    const session = unlockedSession()

    saveRememberedLogin({ email: 'kevin@example.com', secretKey: 'veyq-secret' }, session)

    const restored = readRememberedUnlock('fresh-session-token')
    expect(restored?.sessionToken).toBe('fresh-session-token')
    expect(restored?.user.email).toBe('kevin@example.com')
    expect(Array.from(restored?.masterKey ?? [])).toEqual(Array.from(session.masterKey))
    expect(Array.from(restored?.identity.kemX ?? [])).toEqual(Array.from(session.identity.kemX))
  })

  it('stores and restores the tab unlock state from sessionStorage', () => {
    const session = unlockedSession()

    saveSessionUnlock(session)

    expect(hasSessionUnlock()).toBe(true)
    const restored = readSessionUnlock()
    expect(restored?.sessionToken).toBe('session-token')
    expect(restored?.user.id).toBe('user-1')
    expect(Array.from(restored?.masterKey ?? [])).toEqual(Array.from(session.masterKey))

    const refreshed = readSessionUnlock('fresh-tab-token')
    expect(refreshed?.sessionToken).toBe('fresh-tab-token')

    clearSessionUnlock()
    expect(hasSessionUnlock()).toBe(false)
  })

  it('ignores invalid stored data', () => {
    localStorage.setItem('veyq.rememberedLogin', JSON.stringify({ email: 'kevin@example.com' }))

    expect(rememberedLoginEnabled()).toBe(false)
    expect(readRememberedLogin()).toBeNull()
  })

  it('clears the remembered login and legacy device-unlock state', () => {
    saveRememberedLogin({ email: 'kevin@example.com', secretKey: 'veyq-secret' })
    localStorage.setItem('veyq.deviceUnlock', JSON.stringify({ credentialId: 'old', vault: 'old' }))
    localStorage.setItem('veyq.deviceId', 'old-device-id')
    localStorage.setItem('veyq.deviceKey', 'old-device-key')

    clearRememberedLogin()

    expect(localStorage.getItem('veyq.rememberedLogin')).toBeNull()
    expect(localStorage.getItem('veyq.deviceUnlock')).toBeNull()
    expect(localStorage.getItem('veyq.deviceId')).toBeNull()
    expect(localStorage.getItem('veyq.deviceKey')).toBeNull()
  })

  it('can clear only the legacy device-unlock state', () => {
    saveRememberedLogin({ email: 'kevin@example.com', secretKey: 'veyq-secret' })
    localStorage.setItem('veyq.deviceUnlock', JSON.stringify({ credentialId: 'old', vault: 'old' }))
    localStorage.setItem('veyq.deviceId', 'old-device-id')
    localStorage.setItem('veyq.deviceKey', 'old-device-key')

    clearLegacyDeviceUnlockLogin()

    expect(readRememberedLogin()?.email).toBe('kevin@example.com')
    expect(localStorage.getItem('veyq.deviceUnlock')).toBeNull()
    expect(localStorage.getItem('veyq.deviceId')).toBeNull()
    expect(localStorage.getItem('veyq.deviceKey')).toBeNull()
  })
})

function unlockedSession(): UnlockedSession {
  const identity = v1.generateIdentity()
  return {
    sessionToken: 'session-token',
    user: { id: 'user-1', email: 'kevin@example.com', displayName: 'Kevin', serverRole: 'ServerOwner', username: 'kevin' },
    identity: identity.priv,
    identityPublicKey: {
      suiteId: 'v1',
      kemPublicKey: b64url(v1.concat(identity.pub.kemX, identity.pub.kemM)),
      signPublicKey: b64url(identity.pub.sign),
    },
    masterKey: v1.randomBytes(32),
  }
}
