// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership } from '../../admin/createTenantInviteOwner/tenant'
import { issueMemberInvite, registerViaMemberInvite } from '../../admin/inviteMembersRoles/inviteMembers'
import { getOrCreateContentKey, writeItem, readItems } from '../../content/scopeContent'
import { createChild, deleteNode } from '../../memory/curate/curate'
import { listChildren } from '../../memory/browse/browse'
import { deleteTenant } from '../invite/groups'
import { listMySpaces, openSpace } from '../switchActiveSpace/switchActiveSpace'
import { addGroupMember, createGroupSpace, deleteGroup, listGroupMembers, openSealedName, setGroupMemberRole } from './groupSpaces'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

// One server owner per backend boot, so the whole group lifecycle runs in a single test.
suite('groups E2E (web -> live backend)', () => {
  it('creates a group, lists it (sealed name), and an invited member opens it after re-wrap', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'gadmin@example.com', 'GAdmin', pass)
    const owner = await login(apiBase, 'gadmin@example.com', pass, emergencyKit.secretKey)
    const tenant = await createTenant(apiBase, owner.sessionToken, 'gacme', 'GAcme')
    const oinv = await issueOwnerInvite(apiBase, owner.sessionToken, tenant.id)
    await claimTenantOwnership(apiBase, owner.sessionToken, oinv.inviteId, oinv.bootstrapSecret, owner.identityPublicKey.kemPublicKey)

    // create a group inside the tenant — the creator becomes its GroupOwner
    const group = await createGroupSpace(apiBase, owner, tenant.id, 'Reisegruppe')
    expect(group.role).toBe('GroupOwner')
    expect(group.tenantId).toBe(tenant.id)

    // the roster shows PERSONAL + TENANT + GROUP; the group scope opens by id and round-trips an item
    const spaces = await listMySpaces(apiBase, owner)
    expect(spaces.map((s) => s.kind).sort()).toEqual(['GROUP', 'PERSONAL', 'TENANT'])
    const groupEntry = spaces.find((s) => s.kind === 'GROUP')!
    const oScope = await openSpace(apiBase, owner, groupEntry)
    const oCk = await getOrCreateContentKey(apiBase, owner, oScope)
    expect(openSealedName(oCk, groupEntry.sealedName!.bytes)).toBe('Reisegruppe')
    await writeItem(apiBase, owner, oScope, oCk, 'crew secret')
    expect(await readItems(apiBase, owner, oScope, oCk)).toContain('crew secret')

    // a second user joins the tenant via a member invite, then the owner adds them to the group
    const minv = await issueMemberInvite(apiBase, owner, tenant.id, 'Member')
    const reg = await registerViaMemberInvite(apiBase, minv.inviteId, minv.bootstrapSecret, minv.tempPrivateKey, 'gbob@example.com', 'Bob', pass)
    const bob = await login(apiBase, 'gbob@example.com', pass, reg.emergencyKit.secretKey)
    await addGroupMember(apiBase, owner, tenant.id, group.scopeId, { userId: bob.user.id, kemPublicKeyB64: bob.identityPublicKey.kemPublicKey })

    // Bob now sees the group and his re-wrapped grant decrypts its name + content
    const bobGroup = (await listMySpaces(apiBase, bob)).find((s) => s.kind === 'GROUP' && s.scopeId === group.scopeId)!
    expect(bobGroup).toBeTruthy()
    const bScope = await openSpace(apiBase, bob, bobGroup)
    const bCk = await getOrCreateContentKey(apiBase, bob, bScope)
    expect(openSealedName(bCk, bobGroup.sealedName!.bytes)).toBe('Reisegruppe')
    expect(await readItems(apiBase, bob, bScope, bCk)).toContain('crew secret')

    // owner sees 2 members and can promote Bob to GroupOwner
    expect((await listGroupMembers(apiBase, owner, tenant.id, group.scopeId)).length).toBe(2)
    const promoted = await setGroupMemberRole(apiBase, owner, tenant.id, group.scopeId, bob.user.id, 'GroupOwner')
    expect(promoted.role).toBe('GroupOwner')

    // delete a node in the group (recursive cascade) → it's gone from the level
    const active = { entry: groupEntry, scope: oScope, contentKey: oCk }
    const folder = await createChild(apiBase, owner, active, 'FOLDER', 'Trip', 0)
    expect((await listChildren(apiBase, owner, group.scopeId)).some((n) => n.id === folder.id)).toBe(true)
    await deleteNode(apiBase, owner, active, folder.id)
    expect((await listChildren(apiBase, owner, group.scopeId)).some((n) => n.id === folder.id)).toBe(false)

    // delete the group → gone from the roster
    await deleteGroup(apiBase, owner, tenant.id, group.scopeId)
    expect((await listMySpaces(apiBase, owner)).some((s) => s.scopeId === group.scopeId)).toBe(false)

    // delete the tenant → its TENANT space is gone; the personal space remains
    await deleteTenant(apiBase, owner, tenant.id)
    const after = await listMySpaces(apiBase, owner)
    expect(after.some((s) => s.kind === 'TENANT')).toBe(false)
    expect(after.some((s) => s.kind === 'PERSONAL')).toBe(true)
  })
})
