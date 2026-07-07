import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  loginApi: vi.fn(),
  tokenApi: vi.fn(),
  sessionApi: vi.fn(),
  accountApi: vi.fn(),
}))
vi.mock('../../app/vault', () => ({ identityPrivate: vi.fn() }))
// The TOFU pin store is exercised in identityPins.test.ts; here we drive checkAndPin's outcome directly so the
// rotation re-grant's per-member gate is under our control (default: first-use → proceed).
vi.mock('../account/identityPins', () => ({
  checkAndPin: vi.fn(),
  IdentityChangedError: class IdentityChangedError extends Error {
    constructor(public userId: string) {
      super(`identity key changed for ${userId}`)
    }
  },
}))

import { ensurePersonalScope, openPersonalScope, openScope, openScopeById, listMyTenants, createGroup, listMyGroups, rotateGroupForRemoval, renameGroup, deleteGroup, restoreGroup } from './scopes'
import { ScopeKind } from '../../api/generated/scopes'
import { scopesApi } from '../../api/client'
import { checkAndPin } from '../account/identityPins'
import { identityPrivate } from '../../app/vault'
import { openName, sealName } from '../memory/tree'
import { deriveGroupNameKey } from './inviteName'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'

// A real identity for the test; the mocked vault hands out a FRESH copy each call (the data layer zeroes
// the copy it receives), while we keep this master to verify the grants it produces.
const ID = v1.generateIdentity()
const copyPriv = () => ({ kemX: ID.priv.kemX.slice(), mlkemSeed: ID.priv.mlkemSeed.slice(), sign: ID.priv.sign.slice() })

function mockScopes(list: ReturnType<typeof vi.fn>, create: ReturnType<typeof vi.fn>, createGroupFn = vi.fn()) {
  vi.mocked(scopesApi).mockReturnValue({
    listMyScopes: list,
    createPersonalScope: create,
    createGroup: createGroupFn,
  } as unknown as ReturnType<typeof scopesApi>)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(identityPrivate).mockImplementation(() => copyPriv())
  // Default: every contact is new/unchanged → the rotation re-grants them (TOFU). Overridden per test.
  vi.mocked(checkAndPin).mockResolvedValue('firstUse')
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

describe('openScope', () => {
  it('asymUnwraps the TENANT grant back to a 32-byte scope key — open-only, never POSTs (no client birth)', async () => {
    const scopeKey = v1.randomBytes(32)
    const grant = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), scopeKey, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const list = vi.fn().mockResolvedValue([
      { id: 'tn', kind: 'TENANT', tenantId: 't', wrappedGrant: grant },
      { id: 'p', kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' },
    ])
    const create = vi.fn()
    mockScopes(list, create)

    const opened = await openScope(ScopeKind.Tenant)
    expect(opened).not.toBeNull()
    expect(opened!.scopeId).toBe('tn')
    expect(opened!.kind).toBe('TENANT')
    expect(opened!.key.length).toBe(32)
    expect(Array.from(opened!.key)).toEqual(Array.from(scopeKey))
    expect(create).not.toHaveBeenCalled() // the tenant scope is born cold at activation — never minted here
  })

  it('returns null when the caller holds no scope of that kind (→ the shell falls back to Personal)', async () => {
    mockScopes(vi.fn().mockResolvedValue([{ id: 'p', kind: 'PERSONAL', tenantId: 't', wrappedGrant: 'AQ==' }]), vi.fn())
    expect(await openScope(ScopeKind.Tenant)).toBeNull()
  })
})

describe('createGroup', () => {
  it('posts a self-grant + a name sealed under K_name_0 derived from the birth K_SC (operator-blind — only ciphertext leaves)', async () => {
    const list = vi.fn().mockResolvedValue([{ id: 't', kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }])
    const createGroupFn = vi.fn().mockResolvedValue({ scopeId: 'g1', kind: 'GROUP' })
    mockScopes(list, vi.fn(), createGroupFn)

    const scopeId = await createGroup('Familie')
    expect(scopeId).toBe('g1')
    expect(createGroupFn).toHaveBeenCalledTimes(1)

    // Recover K_SC (= E_0) from the posted grant; the posted encryptedName must decrypt back to the plaintext name
    // under K_name_0 = HKDF(E_0) (cr-0096), NOT under K_SC directly — proving the derived-name-key seal.
    const { encryptedName, wrappedGrant } = createGroupFn.mock.calls[0][0].createGroupRequest
    const key = v1.asymUnwrap(v1.hybridSecret(copyPriv()), v1.hybridPublic(copyPriv()), fromB64std(wrappedGrant), 'scope-grant')
    expect(key.length).toBe(32)
    expect(openName(deriveGroupNameKey(key), encryptedName)).toBe('Familie')
    expect(() => openName(key, encryptedName)).toThrow() // NOT sealed under K_SC directly
  })
})

describe('listMyGroups', () => {
  it('decrypts each group’s name via K_name (from the name-epoch key) on-device (the row carries only ciphertext)', async () => {
    const key = v1.randomBytes(32) // E_0
    const grant = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), key, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const encryptedName = sealName(deriveGroupNameKey(key), 'Reise-Crew') // sealed under K_name_0
    const list = vi.fn().mockResolvedValue([
      { id: 't', kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' },
      { id: 'g1', kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName, nameEpoch: 0, grantedEpoch: 0 },
    ])
    mockScopes(list, vi.fn())

    expect(await listMyGroups()).toEqual([{ scopeId: 'g1', tenantId: 't', name: 'Reise-Crew', deletedAt: null, purgeAfter: null }])
  })
})

describe('openScopeById', () => {
  it('opens a scope by id and decrypts a GROUP’s display name from encryptedName (via K_name)', async () => {
    const key = v1.randomBytes(32)
    const grant = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), key, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const encryptedName = sealName(deriveGroupNameKey(key), 'Reise-Crew')
    const list = vi.fn().mockResolvedValue([
      { id: 'g1', kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName, nameEpoch: 0, grantedEpoch: 0 },
    ])
    mockScopes(list, vi.fn())

    const opened = await openScopeById('g1')
    expect(opened).not.toBeNull()
    expect(opened!.kind).toBe('GROUP')
    expect(opened!.name).toBe('Reise-Crew')
    expect(Array.from(opened!.key)).toEqual(Array.from(key))
  })

  it('returns null when the caller holds no scope with that id', async () => {
    mockScopes(vi.fn().mockResolvedValue([{ id: 't', kind: 'TENANT', tenantId: 't', wrappedGrant: 'AQ==' }]), vi.fn())
    expect(await openScopeById('missing')).toBeNull()
  })
})

describe('rotateGroupForRemoval (cr-0089 — the client-side forward rotation)', () => {
  it('mints a fresh epoch, chain-links the old one under it, and re-grants it to every remaining member', async () => {
    // The caller holds the group at epoch 0 (E_0). rotateGroupForRemoval opens that grant to derive E_0.
    const e0 = v1.randomBytes(32)
    const grant0 = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), e0, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const list = vi.fn().mockResolvedValue([{ id: 'g1', kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 }])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)

    // Two surviving members with real identities (the removed one is simply absent from this list).
    const memberA = v1.generateIdentity()
    const memberB = v1.generateIdentity()
    const remaining = [
      { userId: 'a', username: 'a', role: 'OWNER', identityPublicKey: b64std(v1.serializeIdentityPublic(memberA.pub)), hasGrant: true },
      { userId: 'b', username: 'b', role: 'MEMBER', identityPublicKey: b64std(v1.serializeIdentityPublic(memberB.pub)), hasGrant: true },
    ]

    const newEpoch = await rotateGroupForRemoval('g1', remaining)
    expect(newEpoch).toBe(1)
    expect(rotate).toHaveBeenCalledTimes(1)

    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual(['a', 'b'])

    // Each member's grant opens (with their OWN identity) to the same fresh epoch key E_1 — operator-blind: only
    // ciphertext was posted, and every remaining member can read forward.
    const e1a = v1.asymUnwrap(v1.hybridSecret(memberA.priv), v1.hybridPublic(memberA.priv), fromB64std(payload.grants[0].wrappedGrant), 'scope-grant')
    const e1b = v1.asymUnwrap(v1.hybridSecret(memberB.priv), v1.hybridPublic(memberB.priv), fromB64std(payload.grants[1].wrappedGrant), 'scope-grant')
    expect(e1a.length).toBe(32)
    expect(Array.from(e1a)).toEqual(Array.from(e1b))

    // The chain-link wraps the OLD epoch key under the new one: symUnwrap(E_1, wrappedPrev, 'scope-epoch') === E_0,
    // so a member can still peel down to read pre-rotation content.
    const peeledE0 = v1.symUnwrap(e1a, fromB64std(payload.wrappedPrev), 'scope-epoch')
    expect(Array.from(peeledE0)).toEqual(Array.from(e0))

    // Forward-confidentiality: the new epoch differs from the old, so content sealed under E_1 is unreadable with E_0.
    expect(Array.from(e1a)).not.toEqual(Array.from(e0))
  })

  it('excludes a member whose identity key CHANGED from the re-grant, but still rotates for the others (cr-0098)', async () => {
    // TOFU at the rotation site: a surviving member whose directory key differs from the pin is NOT re-granted the
    // fresh epoch (never silently wrap to a possibly-substituted key). The rotation still proceeds — forward secrecy
    // against the removed member must not hinge on one contact's changed key. The excluded member stays awaiting-access.
    const e0 = v1.randomBytes(32)
    const grant0 = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), e0, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const list = vi.fn().mockResolvedValue([{ id: 'g1', kind: 'GROUP', tenantId: 't', wrappedGrant: grant0, grantedEpoch: 0 }])
    const rotate = vi.fn().mockResolvedValue({ epoch: 1 })
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, rotateScope: rotate } as unknown as ReturnType<typeof scopesApi>)

    const memberA = v1.generateIdentity()
    const memberB = v1.generateIdentity()
    const remaining = [
      { userId: 'a', username: 'a', role: 'OWNER', identityPublicKey: b64std(v1.serializeIdentityPublic(memberA.pub)), hasGrant: true },
      { userId: 'b', username: 'b', role: 'MEMBER', identityPublicKey: b64std(v1.serializeIdentityPublic(memberB.pub)), hasGrant: true },
    ]
    // b's key changed since it was pinned → exclude b, keep a.
    vi.mocked(checkAndPin).mockImplementation(async (userId: string) => (userId === 'b' ? 'changed' : 'match'))

    const newEpoch = await rotateGroupForRemoval('g1', remaining)
    expect(newEpoch).toBe(1)
    const payload = rotate.mock.calls[0][0].rotateScopeRequest
    expect(payload.grants.map((g: { userId: string }) => g.userId)).toEqual(['a']) // b excluded, a still re-granted
  })
})

describe('renameGroup (cr-0097 — re-seal the name under the current epoch)', () => {
  function mockGroupForRename(rename: ReturnType<typeof vi.fn>, currentEpoch = 0, grantedEpoch = 0) {
    const key = v1.randomBytes(32) // E_grantedEpoch = the write key the caller holds
    const grant = b64std(v1.asymWrap(v1.hybridPublic(copyPriv()), key, 'scope-grant', { nonce: v1.randomBytes(12) }))
    const list = vi.fn().mockResolvedValue([
      { id: 'g1', kind: 'GROUP', tenantId: 't', wrappedGrant: grant, encryptedName: sealName(deriveGroupNameKey(key), 'Alt'), nameEpoch: 0, grantedEpoch, currentEpoch },
    ])
    vi.mocked(scopesApi).mockReturnValue({ listMyScopes: list, renameGroup: rename } as unknown as ReturnType<typeof scopesApi>)
    return key
  }

  it('seals the new name under K_name of the write-key epoch and posts { encryptedName, nameEpoch }', async () => {
    const rename = vi.fn().mockResolvedValue(undefined)
    const key = mockGroupForRename(rename)

    await renameGroup('g1', 'Neu')

    expect(rename).toHaveBeenCalledTimes(1)
    const req = rename.mock.calls[0][0].renameGroupRequest
    expect(req.nameEpoch).toBe(0)
    // The posted ciphertext decrypts under K_name derived from the epoch key back to the NEW plaintext.
    expect(openName(deriveGroupNameKey(key), req.encryptedName)).toBe('Neu')
  })

  it('retries once on 409 stale_epoch (a concurrent rotation), then succeeds', async () => {
    const stale = { response: new Response(JSON.stringify({ code: 'stale_epoch' }), { status: 409 }) }
    const rename = vi.fn().mockRejectedValueOnce(stale).mockResolvedValueOnce(undefined)
    mockGroupForRename(rename)

    await renameGroup('g1', 'Neu')
    expect(rename).toHaveBeenCalledTimes(2) // refetch + retry once
  })

  it('does not retry on a non-stale failure (e.g. 403) — it propagates', async () => {
    const forbidden = { response: new Response(JSON.stringify({ code: 'not_scope_owner' }), { status: 403 }) }
    const rename = vi.fn().mockRejectedValue(forbidden)
    mockGroupForRename(rename)

    await expect(renameGroup('g1', 'Neu')).rejects.toBe(forbidden)
    expect(rename).toHaveBeenCalledTimes(1)
  })
})

describe('deleteGroup / restoreGroup (cr-0090 — group lifecycle data layer)', () => {
  it('posts a soft-delete then a restore to the scope lifecycle endpoints', async () => {
    const deleteFn = vi.fn().mockResolvedValue(undefined)
    const restoreFn = vi.fn().mockResolvedValue(undefined)
    vi.mocked(scopesApi).mockReturnValue({ deleteGroup: deleteFn, restoreGroup: restoreFn } as unknown as ReturnType<typeof scopesApi>)

    await deleteGroup('g1')
    expect(deleteFn).toHaveBeenCalledWith({ scopeId: 'g1' })

    await restoreGroup('g1')
    expect(restoreFn).toHaveBeenCalledWith({ scopeId: 'g1' })
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
