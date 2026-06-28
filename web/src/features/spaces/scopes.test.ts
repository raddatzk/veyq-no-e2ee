import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  accountApi: vi.fn(),
}))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))

import { ensurePersonalScope, openPersonalScope, listMyTenants } from './scopes'
import { scopesApi } from '../../api/client'
import { identityPrivate } from '../../app/vault'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

// A real identity for the test; the mocked vault hands out a FRESH copy each call (the data layer zeroes
// the copy it receives), while we keep this master to verify the grants it produces.
const ID = v1.generateIdentity()
const copyPriv = () => ({ kemX: ID.priv.kemX.slice(), mlkemSeed: ID.priv.mlkemSeed.slice(), sign: ID.priv.sign.slice() })

function mockScopes(list: ReturnType<typeof vi.fn>, create: ReturnType<typeof vi.fn>) {
  vi.mocked(scopesApi).mockReturnValue({
    listMyScopes: list,
    createPersonalScope: create,
  } as unknown as ReturnType<typeof scopesApi>)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(identityPrivate).mockImplementation(() => copyPriv())
})

describe('ensurePersonalScope', () => {
  it('births a personal scope (operator-blind grant that opens back to a 32-byte key) when none exists', async () => {
    const list = vi.fn().mockResolvedValue([{ id: 't', kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }])
    const create = vi.fn().mockResolvedValue({ scopeId: 's', kind: 'PERSONAL' })
    mockScopes(list, create)

    const created = await ensurePersonalScope()
    expect(created).toBe(true)
    expect(create).toHaveBeenCalledTimes(1)

    // The posted grant is a real asymWrap to our identity — it must asymUnwrap back to a 32-byte scope key.
    const grant: string = create.mock.calls[0][0].createPersonalScopeRequest.wrappedGrant
    const opened = v1.asymUnwrap(v1.hybridSecret(copyPriv()), v1.hybridPublic(copyPriv()), fromB64std(grant), 'scope-grant')
    expect(opened.length).toBe(32)
  })

  it('is a no-op when a personal scope already exists', async () => {
    const list = vi.fn().mockResolvedValue([{ id: 'p', kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' }])
    const create = vi.fn()
    mockScopes(list, create)

    expect(await ensurePersonalScope()).toBe(false)
    expect(create).not.toHaveBeenCalled()
  })
})

describe('openPersonalScope', () => {
  it('asymUnwraps the personal grant back to the scope key', async () => {
    const scopeKey = v1.randomBytes(32)
    const grant = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), scopeKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const list = vi.fn().mockResolvedValue([
      { id: 't', kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' },
      { id: 'p', kind: 'PERSONAL', tenantId: 't', wrappedGrant: grant },
    ])
    mockScopes(list, vi.fn())

    const opened = await openPersonalScope()
    expect(opened).not.toBeNull()
    expect(Array.from(opened!)).toEqual(Array.from(scopeKey))
  })

  it('returns null when there is no personal scope', async () => {
    mockScopes(vi.fn().mockResolvedValue([{ id: 't', kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }]), vi.fn())
    expect(await openPersonalScope()).toBeNull()
  })
})

describe('listMyTenants', () => {
  it('returns the caller’s tenants (membership metadata only) for the switcher', async () => {
    const tenants = [{ tenantId: 't1', name: 'Fam', domain: '127.0.0.1', role: 'OWNER' }]
    vi.mocked(scopesApi).mockReturnValue({
      listMyTenants: vi.fn().mockResolvedValue(tenants),
    } as unknown as ReturnType<typeof scopesApi>)
    expect(await listMyTenants()).toEqual(tenants)
  })
})
