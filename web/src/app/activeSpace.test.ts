import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../features/spaces/scopes', () => ({
  ensurePersonalScope: vi.fn(),
  listMyScopes: vi.fn(),
  openPersonalScope: vi.fn(),
}))

import { activeSpace, activeScopeKey, enterPersonalSpace, clearActiveSpace } from './activeSpace'
import { ensurePersonalScope, listMyScopes, openPersonalScope } from '../features/spaces/scopes'
import { ScopeKind } from '../api/generated/scopes'

const SCOPE_ID = '11111111-1111-1111-1111-111111111111'
const personal = { id: SCOPE_ID, kind: ScopeKind.Personal, tenantId: 't', wrappedGrant: 'Zw==' }

beforeEach(() => {
  vi.clearAllMocks()
  clearActiveSpace()
})

describe('enterPersonalSpace', () => {
  it('auto-births + opens the personal scope → ready with scopeId + K_SC', async () => {
    const key = new Uint8Array(32).fill(9)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(listMyScopes).mockResolvedValue([personal] as never)
    vi.mocked(openPersonalScope).mockResolvedValue(key)

    await enterPersonalSpace()

    expect(ensurePersonalScope).toHaveBeenCalledOnce()
    expect(activeSpace.status).toBe('ready')
    expect(activeSpace.scopeId).toBe(SCOPE_ID)
    expect(activeScopeKey()).toBe(key)
  })

  it('maps a 404 (no tenant for this Host) to status no-tenant', async () => {
    vi.mocked(ensurePersonalScope).mockRejectedValue({ response: { status: 404 } })
    await enterPersonalSpace()
    expect(activeSpace.status).toBe('no-tenant')
    expect(activeScopeKey()).toBeNull()
  })

  it('falls back to error on other failures', async () => {
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(listMyScopes).mockResolvedValue([] as never) // no personal scope present
    await enterPersonalSpace()
    expect(activeSpace.status).toBe('error')
  })
})

describe('clearActiveSpace', () => {
  it('zeroes the held key and resets state', async () => {
    const key = new Uint8Array(32).fill(7)
    vi.mocked(ensurePersonalScope).mockResolvedValue(false)
    vi.mocked(listMyScopes).mockResolvedValue([personal] as never)
    vi.mocked(openPersonalScope).mockResolvedValue(key)
    await enterPersonalSpace()

    clearActiveSpace()

    expect(activeSpace.status).toBe('idle')
    expect(activeSpace.scopeId).toBeNull()
    expect(activeScopeKey()).toBeNull()
    expect(Array.from(key)).toEqual(Array(32).fill(0)) // zeroed in place
  })
})
