// @vitest-environment node
// Live member-admin flow (opt-in via VEYQ_SEED=1): owner creates a group + a member, then lists members,
// promotes/demotes a role, removes the member, and rotates the scope keys — the operations behind Phase D's
// owner UI, end-to-end against a real backend.
import { describe, expect, it } from 'vitest'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { registerViaMemberInvite, listTenantMembers, changeMemberRole, removeMember, rotateScope } from './inviteMembers'
import { createGroup, inviteToGroup } from '../../spaces/invite/groups'
import { parseInviteHash, type InviteParts } from '../../spaces/invite/inviteLink'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip
const frag = (link: string) => parseInviteHash(link.slice(link.indexOf('#'))) as InviteParts

run('member admin e2e', () => {
  it('owner lists, re-roles, removes a member, and rotates the scope', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'owner@veyq.app', 'Owner', pass)
    const a = await login(apiBase, 'owner@veyq.app', pass, aKit.secretKey)
    const g = await createGroup(apiBase, a, 'Crew')
    const p = frag(await inviteToGroup(apiBase, a, g.id, 'Member', 'https://veyq.app'))
    const bReg = await registerViaMemberInvite(apiBase, p.inviteId, p.bootstrapSecret, p.tempPrivateKey, 'b@veyq.app', 'Bea', 'a whole new passphrase')
    const bId = bReg.user.id

    // the roster shows both, with their roles
    let members = await listTenantMembers(apiBase, a.sessionToken, g.id)
    expect(members.filter((m) => m.status === 'ACTIVE')).toHaveLength(2)
    expect(members.find((m) => m.userId === bId)?.role).toBe('Member')

    // promote Bea to Owner, then demote back
    await changeMemberRole(apiBase, a.sessionToken, g.id, bId, 'Owner')
    expect((await listTenantMembers(apiBase, a.sessionToken, g.id)).find((m) => m.userId === bId)?.role).toBe('Owner')
    await changeMemberRole(apiBase, a.sessionToken, g.id, bId, 'Member')

    // remove Bea, then rotate the scope so a removed member loses access to future content
    await removeMember(apiBase, a.sessionToken, g.id, bId)
    members = await listTenantMembers(apiBase, a.sessionToken, g.id)
    expect(members.filter((m) => m.status === 'ACTIVE')).toHaveLength(1)
    const rot = await rotateScope(apiBase, a, g.id)
    expect(rot.memberCount).toBe(1)
  }, 120000)
})
