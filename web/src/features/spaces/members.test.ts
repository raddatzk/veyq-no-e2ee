import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../../api/client', () => ({
  scopesApi: vi.fn(),
  tenantsApi: vi.fn(),
}))

import { addGroupMember, listScopeMembers } from './members'
import { scopesApi } from '../../api/client'

beforeEach(() => vi.clearAllMocks())

describe('addGroupMember (cr-0086)', () => {
  it('posts the username to the scope members endpoint and returns the grant-less directory row', async () => {
    // The added member comes back awaiting access — the key is delivered later by grant-delivery, not here.
    const row = { userId: 'u1', username: 'partner', role: 'MEMBER', identityPublicKey: 'cGs=', hasGrant: false }
    const addGroupMemberFn = vi.fn().mockResolvedValue(row)
    vi.mocked(scopesApi).mockReturnValue({ addGroupMember: addGroupMemberFn } as unknown as ReturnType<typeof scopesApi>)

    const result = await addGroupMember('g1', 'partner')

    expect(addGroupMemberFn).toHaveBeenCalledWith({ scopeId: 'g1', addGroupMemberRequest: { username: 'partner' } })
    expect(result).toEqual(row)
    expect(result.hasGrant).toBe(false)
  })
})

describe('listScopeMembers', () => {
  it('reads a scope’s member directory by id (operator-blind operational metadata only)', async () => {
    const rows = [
      { userId: 'o', username: 'owner', role: 'OWNER', identityPublicKey: 'cGs=', hasGrant: true },
      { userId: 'u1', username: 'partner', role: 'MEMBER', identityPublicKey: 'cGs=', hasGrant: false },
    ]
    const listFn = vi.fn().mockResolvedValue(rows)
    vi.mocked(scopesApi).mockReturnValue({ listScopeMembers: listFn } as unknown as ReturnType<typeof scopesApi>)

    expect(await listScopeMembers('g1')).toEqual(rows)
    expect(listFn).toHaveBeenCalledWith({ scopeId: 'g1' })
  })
})
