import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../features/spaces/scopes', () => ({
  ensurePersonalScope: vi.fn(),
  openScope: vi.fn(),
}))

import { activeSpace, activeScopeKey, enterScope, clearActiveSpace } from './activeSpace'
import { ensurePersonalScope, openScope } from '../features/spaces/scopes'
import { ScopeKind } from '../api/generated/scopes'
import { TENANT_SCOPE } from '../features/memory/location'

const PERSONAL_SCOPE = '~me'
import { treeStore, resetTree } from '../features/memory/treeStore'
import { favoritesStore, clearFavorites } from './favoritesStore'
import type { TreeNode } from '../features/memory/tree'

const TENANT_ID = '22222222-2222-2222-2222-222222222222'
const TENANT_SCOPE_ID = 'tn-1'
const PERSONAL_SCOPE_ID = '11111111-1111-1111-1111-111111111111'

const opened = (scopeId: string, kind: ScopeKind, key: Uint8Array) => ({ scopeId, tenantId: TENANT_ID, kind, key })

beforeEach(() => {
  vi.clearAllMocks()
  clearActiveSpace()
  resetTree()
  clearFavorites()
})

describe('enterScope — tenant', () => {
  it('opens the TENANT grant → ready with scopeId + K_SC + kind, tenant available', async () => {
    const key = new Uint8Array(32).fill(9)
    vi.mocked(openScope).mockResolvedValue(opened(TENANT_SCOPE_ID, ScopeKind.Tenant, key))

    await enterScope(TENANT_SCOPE)

    expect(openScope).toHaveBeenCalledWith(ScopeKind.Tenant)
    expect(ensurePersonalScope).not.toHaveBeenCalled() // open-only: the tenant scope is never minted client-side
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.scopeId).toBe(TENANT_SCOPE_ID)
    expect(activeSpace.kind).toBe(ScopeKind.Tenant)
    expect(activeSpace.tenantUnavailable).toBe(false)
    expect(activeScopeKey()).toBe(key)
  })

  it('falls back to Personal (auto-birth + open) when there is no TENANT grant, flagging it unavailable', async () => {
    const key = new Uint8Array(32).fill(5)
    vi.mocked(openScope).mockImplementation(async (kind) =>
      kind === ScopeKind.Personal ? opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, key) : null,
    )
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)

    await enterScope(TENANT_SCOPE)

    expect(ensurePersonalScope).toHaveBeenCalledOnce() // personal is the one birth path
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.scopeId).toBe(PERSONAL_SCOPE_ID)
    expect(activeSpace.kind).toBe(ScopeKind.Personal)
    expect(activeSpace.tenantUnavailable).toBe(true)
    expect(activeScopeKey()).toBe(key)
  })
})

describe('enterScope — personal', () => {
  it('auto-births + opens the personal scope → ready, tenant flag clear', async () => {
    const key = new Uint8Array(32).fill(7)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, key))

    await enterScope(PERSONAL_SCOPE)

    expect(openScope).not.toHaveBeenCalledWith(ScopeKind.Tenant) // `~me` never touches the tenant grant
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.kind).toBe(ScopeKind.Personal)
    expect(activeSpace.tenantUnavailable).toBe(false)
  })

  it('maps a 404 (no tenant for this Host) to status no-tenant', async () => {
    vi.mocked(ensurePersonalScope).mockRejectedValue({ response: { status: 404 } })
    await enterScope(PERSONAL_SCOPE)
    expect(activeSpace.status).toBe('no-tenant')
    expect(activeScopeKey()).toBeNull()
  })

  it('falls back to error when no scope can be opened', async () => {
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(null)
    await enterScope(PERSONAL_SCOPE)
    expect(activeSpace.status).toBe('error')
  })
})

describe('enterScope — re-entry resets per-scope state', () => {
  it('clears the tree + favorites so nothing bleeds across a scope switch', async () => {
    // Seed stale per-scope state as if a previous scope was loaded.
    treeStore.nodes = [{ id: 'stale', name: 'Stale', parentId: null } as unknown as TreeNode]
    treeStore.loaded = true
    favoritesStore.ids = new Set(['fav-1'])
    favoritesStore.loaded = true

    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, new Uint8Array(32).fill(1)))

    await enterScope(PERSONAL_SCOPE)

    expect(treeStore.nodes).toEqual([])
    expect(treeStore.loaded).toBe(false)
    expect(favoritesStore.ids.size).toBe(0)
    expect(favoritesStore.loaded).toBe(false)
  })
})

describe('clearActiveSpace', () => {
  it('zeroes the held key and resets state', async () => {
    const key = new Uint8Array(32).fill(7)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(openScope).mockResolvedValue(opened(PERSONAL_SCOPE_ID, ScopeKind.Personal, key))
    await enterScope(PERSONAL_SCOPE)

    clearActiveSpace()

    expect(activeSpace.status).toBe('idle')
    expect(activeSpace.scopeId).toBeNull()
    expect(activeSpace.kind).toBeNull()
    expect(activeScopeKey()).toBeNull()
    expect(Array.from(key)).toEqual(Array(32).fill(0)) // zeroed in place
  })
})
