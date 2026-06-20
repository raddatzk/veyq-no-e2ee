import { beforeEach, describe, expect, it } from 'vitest'
import { clearLegacyDeviceUnlockLogin, clearRememberedLogin, readRememberedLogin, rememberedLoginEnabled, saveRememberedLogin } from './rememberedLogin'

describe('rememberedLogin', () => {
  beforeEach(() => localStorage.clear())

  it('stores and reads email, password and Secret Key from localStorage', () => {
    saveRememberedLogin({ email: 'kevin@example.com', password: 'correct horse', secretKey: 'veyq-secret' })

    expect(rememberedLoginEnabled()).toBe(true)
    expect(readRememberedLogin()).toEqual({ email: 'kevin@example.com', password: 'correct horse', secretKey: 'veyq-secret' })
  })

  it('ignores invalid stored data', () => {
    localStorage.setItem('veyq.rememberedLogin', JSON.stringify({ email: 'kevin@example.com' }))

    expect(rememberedLoginEnabled()).toBe(false)
    expect(readRememberedLogin()).toBeNull()
  })

  it('clears the remembered login and legacy device-unlock state', () => {
    saveRememberedLogin({ email: 'kevin@example.com', password: 'correct horse', secretKey: 'veyq-secret' })
    localStorage.setItem('veyq.deviceUnlock', JSON.stringify({ credentialId: 'old', vault: 'old' }))

    clearRememberedLogin()

    expect(localStorage.getItem('veyq.rememberedLogin')).toBeNull()
    expect(localStorage.getItem('veyq.deviceUnlock')).toBeNull()
  })

  it('can clear only the legacy device-unlock state', () => {
    saveRememberedLogin({ email: 'kevin@example.com', password: 'correct horse', secretKey: 'veyq-secret' })
    localStorage.setItem('veyq.deviceUnlock', JSON.stringify({ credentialId: 'old', vault: 'old' }))

    clearLegacyDeviceUnlockLogin()

    expect(readRememberedLogin()?.email).toBe('kevin@example.com')
    expect(localStorage.getItem('veyq.deviceUnlock')).toBeNull()
  })
})
