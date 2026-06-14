// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership } from '../../admin/createTenantInviteOwner/tenant'
import { issueMemberInvite, registerViaMemberInvite } from '../../admin/inviteMembersRoles/inviteMembers'
import { openTenantScope, getOrCreateContentKey, writeItem, readItems } from '../../content/scopeContent'
import { listMyGroups } from './useGroup'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('use-group E2E (web -> live backend): two members collaborate on one shared scope', () => {
  it('lists the group for both members and both read each other\'s shared items', async () => {
    // owner A claims tenant T1
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme Group')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // member B joins T1 via a temp-wrap invite
    const bPass = 'a whole new passphrase entirely'
    const memberInvite = await issueMemberInvite(apiBase, a, t1.id, 'Member')
    const bReg = await registerViaMemberInvite(apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, 'b@example.com', 'B', bPass)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)

    // the group roster shows T1 to both — with each member's own role
    const aGroups = await listMyGroups(apiBase, a)
    expect(aGroups.map((g) => g.tenantId)).toContain(t1.id)
    expect(aGroups.find((g) => g.tenantId === t1.id)?.role).toBe('Owner')
    expect(aGroups.find((g) => g.tenantId === t1.id)?.displayName).toBe('Acme Group')

    const bGroups = await listMyGroups(apiBase, b)
    expect(bGroups.find((g) => g.tenantId === t1.id)?.role).toBe('Member')

    // both members write shared content under the one group scope (provisioning the content key once)
    const aScope = await openTenantScope(apiBase, a, t1.id)
    const aCk = await getOrCreateContentKey(apiBase, a, aScope)
    await writeItem(apiBase, a, aScope, aCk, 'note from A')

    const bScope = await openTenantScope(apiBase, b, t1.id)
    const bCk = await getOrCreateContentKey(apiBase, b, bScope) // adopts A's content key (unwraps with B's grant)
    await writeItem(apiBase, b, bScope, bCk, 'note from B')

    // any member reads the whole shared set — both see both items (access = the wrap, ADR 0005)
    expect(await readItems(apiBase, a, aScope, aCk)).toEqual(expect.arrayContaining(['note from A', 'note from B']))
    expect(await readItems(apiBase, b, bScope, bCk)).toEqual(expect.arrayContaining(['note from A', 'note from B']))
  })
})
