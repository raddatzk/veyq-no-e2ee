import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({ favoritesApi: vi.fn() }))
vi.mock('../account/login/login', () => ({ withRefresh: (fn: () => unknown) => fn() }))

import { loadFavorites, saveFavorites } from './favorites'
import { favoritesApi } from '../../api/client'
import * as v1 from '../../shared/crypto/v1'

const K = v1.randomBytes(32)

function mockApi(get: ReturnType<typeof vi.fn>, put: ReturnType<typeof vi.fn>) {
  vi.mocked(favoritesApi).mockReturnValue({ getFavorites: get, putFavorites: put } as unknown as ReturnType<typeof favoritesApi>)
}

beforeEach(() => vi.clearAllMocks())

describe('favorites data layer', () => {
  it('round-trips the pinned set through seal → store → load → decrypt', async () => {
    let stored: string | null = null
    const put = vi.fn(({ putFavoritesRequest }) => { stored = putFavoritesRequest.ciphertext })
    const get = vi.fn(() => Promise.resolve({ ciphertext: stored }))
    mockApi(get, put)

    await saveFavorites('scope', K, new Set(['m1', 'm2']))
    expect(stored).toBeTruthy()
    // operator-blind: the stored blob is opaque ciphertext, never the plaintext ids
    expect(stored).not.toContain('m1')

    const loaded = await loadFavorites('scope', K)
    expect([...loaded].sort()).toEqual(['m1', 'm2'])
  })

  it('returns an empty set when nothing is stored', async () => {
    mockApi(vi.fn(() => Promise.resolve({ ciphertext: null })), vi.fn())
    expect((await loadFavorites('scope', K)).size).toBe(0)
  })

  it('returns an empty set on an unreadable blob (wrong key) instead of throwing', async () => {
    let stored: string | null = null
    const put = vi.fn(({ putFavoritesRequest }) => { stored = putFavoritesRequest.ciphertext })
    mockApi(vi.fn(() => Promise.resolve({ ciphertext: stored })), put)
    await saveFavorites('scope', v1.randomBytes(32), new Set(['x'])) // sealed under a DIFFERENT key
    expect((await loadFavorites('scope', K)).size).toBe(0)
  })
})
