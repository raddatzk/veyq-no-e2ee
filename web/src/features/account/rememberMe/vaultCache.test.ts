// uc.account.auto-unlock (L3, web "remember me", cr-0052) — the local vault cache round-trips the Master Key
// under a non-extractable AES-GCM key in IndexedDB, normalizes the email, and clears cleanly.
import 'fake-indexeddb/auto'
import { describe, it, expect, beforeEach } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { cacheMasterKey, loadCachedMasterKey, clearMasterKeyCache } from './vaultCache'

const masterKey = () => new Uint8Array(Array.from({ length: 32 }, (_, i) => i + 1))

describe('vaultCache (L3 remember-me, cr-0052)', () => {
  beforeEach(() => {
    globalThis.indexedDB = new IDBFactory() // a fresh, empty store per test
  })

  it('round-trips the Master Key and normalizes the email', async () => {
    await cacheMasterKey('User@Example.com', masterKey())
    const loaded = await loadCachedMasterKey()
    expect(loaded?.email).toBe('user@example.com')
    expect(loaded?.masterKey).toEqual(masterKey())
  })

  it('returns null when nothing is cached', async () => {
    expect(await loadCachedMasterKey()).toBeNull()
  })

  it('drops the cache on clear', async () => {
    await cacheMasterKey('a@b.com', masterKey())
    await clearMasterKeyCache()
    expect(await loadCachedMasterKey()).toBeNull()
  })
})
