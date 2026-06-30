// @vitest-environment happy-dom
// uc.account.remember-this-device (web "off" tier, cr-0055) — the session-scoped vault round-trips the Master
// Key through sessionStorage, normalizes the email, clears cleanly, and tolerates corrupt/absent storage.
import { describe, it, expect, beforeEach } from 'vitest'
import { cacheSessionVault, loadSessionVault, clearSessionVault, isSessionVaultSupported } from './sessionVault'

const masterKey = () => new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))

describe('sessionVault (web "off" tier, cr-0055)', () => {
  beforeEach(() => sessionStorage.clear())

  it('is supported in a DOM environment', () => {
    expect(isSessionVaultSupported()).toBe(true)
  })

  it('round-trips the Master Key and normalizes the email', () => {
    cacheSessionVault('User@Example.com', masterKey())
    const v = loadSessionVault()
    expect(v?.email).toBe('user@example.com')
    expect(v?.masterKey).toEqual(masterKey())
  })

  it('returns null when nothing is cached', () => {
    expect(loadSessionVault()).toBeNull()
  })

  it('clears the vault', () => {
    cacheSessionVault('a@b.com', masterKey())
    clearSessionVault()
    expect(loadSessionVault()).toBeNull()
  })

  it('returns null on corrupt data instead of throwing', () => {
    sessionStorage.setItem('veyq.session-vault', 'not-json')
    expect(loadSessionVault()).toBeNull()
  })
})
