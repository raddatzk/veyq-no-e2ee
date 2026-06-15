// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership } from '../../admin/createTenantInviteOwner/tenant'
import { getOrCreateContentKey, writeItem, readItems } from '../../content/scopeContent'
import { listMySpaces, openSpace } from '../switchActiveSpace/switchActiveSpace'
import { createGroupSpace, openSealedName } from './groupSpaces'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('groups E2E (web -> live backend)', () => {
  it('creates a group within a tenant, lists it (sealed name decrypted) and reads/writes its content', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'gadmin@example.com', 'GAdmin', pass)
    const a = await login(apiBase, 'gadmin@example.com', pass, emergencyKit.secretKey)
    const tenant = await createTenant(apiBase, a.sessionToken, 'gacme', 'GAcme')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, tenant.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // create a group inside the tenant — the creator becomes its GroupOwner
    const group = await createGroupSpace(apiBase, a, tenant.id, 'Reisegruppe')
    expect(group.role).toBe('GroupOwner')
    expect(group.tenantId).toBe(tenant.id)

    // the roster shows PERSONAL + TENANT + GROUP
    const spaces = await listMySpaces(apiBase, a)
    expect(spaces.map((s) => s.kind).sort()).toEqual(['GROUP', 'PERSONAL', 'TENANT'])
    const groupEntry = spaces.find((s) => s.kind === 'GROUP')!

    // open the group scope by id, decrypt its sealed name, and round-trip an item under its content key
    const scope = await openSpace(apiBase, a, groupEntry)
    const ck = await getOrCreateContentKey(apiBase, a, scope)
    expect(openSealedName(ck, groupEntry.sealedName!.bytes)).toBe('Reisegruppe')
    await writeItem(apiBase, a, scope, ck, 'group note')
    expect(await readItems(apiBase, a, scope, ck)).toContain('group note')
  })
})
