// @vitest-environment node
// Live group-invite loop (opt-in via VEYQ_SEED=1): owner creates a group + an invite LINK; an existing user
// parses the link and joins via acceptMemberInvite, then collaborates on the shared scope. Exercises
// createGroup + inviteToGroup + the link format + the logged-in accept path against a real backend.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login } from '../../account/login/login'
import { registerViaMemberInvite, acceptMemberInvite } from '../../admin/inviteMembersRoles/inviteMembers'
import { openTenantScope, getOrCreateContentKey, writeItem, readItems } from '../../content/scopeContent'
import { listMySpaces } from '../switchActiveSpace/switchActiveSpace'
import { createGroup, inviteToGroup } from './groups'
import { parseInviteHash } from './inviteLink'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip
const frag = (link: string) => parseInviteHash(link.slice(link.indexOf('#')))!

run('group invite e2e', () => {
  it('owner creates a group + link; an existing user joins via the link', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'owner@veyq.app', 'Owner', pass)
    const a = await login(apiBase, 'owner@veyq.app', pass, aKit.secretKey)

    // bootstrap an existing user B via a first group + register-via-invite
    const g1 = await createGroup(apiBase, a, 'Group One')
    const p1 = frag(await inviteToGroup(apiBase, a, g1.id, 'Member', 'https://veyq.app'))
    const bPass = 'a whole new passphrase entirely'
    const bReg = await registerViaMemberInvite(apiBase, p1.inviteId, p1.bootstrapSecret, p1.tempPrivateKey, 'b@veyq.app', 'B', bPass)
    const b = await login(apiBase, 'b@veyq.app', bPass, bReg.emergencyKit.secretKey)

    // owner creates a SECOND group and invites B; B (an existing account) joins via the link
    const g2 = await createGroup(apiBase, a, 'Group Two')
    const p2 = frag(await inviteToGroup(apiBase, a, g2.id, 'Member', 'https://veyq.app'))
    await acceptMemberInvite(apiBase, b, p2.inviteId, p2.bootstrapSecret, p2.tempPrivateKey)

    // B now sees g2 among their spaces and reads content the owner wrote there
    const spaces = await listMySpaces(apiBase, b)
    expect(spaces.map((s) => s.tenantId)).toContain(g2.id)

    const aScope = await openTenantScope(apiBase, a, g2.id)
    const aCk = await getOrCreateContentKey(apiBase, a, aScope)
    await writeItem(apiBase, a, aScope, aCk, 'hello from owner')

    const bScope = await openTenantScope(apiBase, b, g2.id)
    const bCk = await getOrCreateContentKey(apiBase, b, bScope)
    expect(await readItems(apiBase, b, bScope, bCk)).toContain('hello from owner')
  }, 120000)
})
