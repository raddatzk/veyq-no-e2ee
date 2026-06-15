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
import { createGroup, inviteToGroup, emailInviteToGroup, unprotectTempKey } from './groups'
import { parseInviteHash, isProtectedInvite, type InviteParts } from './inviteLink'

const apiBase = process.env.VEYQ_API_BASE ?? 'http://localhost:8080'
const run = process.env.VEYQ_SEED === '1' ? describe : describe.skip
const frag = (link: string) => parseInviteHash(link.slice(link.indexOf('#'))) as InviteParts

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

    // passphrase-protected invite (operator-blind): owner mints a 3rd group + a protected link; a brand-new
    // user resolves the temp key with the out-of-band passphrase, then registers + joins.
    const g3 = await createGroup(apiBase, a, 'Group Three')
    const invitePass = 'shared out of band'
    const protLink = await inviteToGroup(apiBase, a, g3.id, 'Member', 'https://veyq.app', invitePass)
    const parsed = parseInviteHash(protLink.slice(protLink.indexOf('#')))!
    expect(isProtectedInvite(parsed)).toBe(true)
    if (!isProtectedInvite(parsed)) throw new Error('expected protected invite')
    const tempKey = unprotectTempKey(parsed, invitePass)
    const cPass = 'carols own password'
    const cReg = await registerViaMemberInvite(apiBase, parsed.inviteId, parsed.bootstrapSecret, tempKey, 'c@veyq.app', 'C', cPass)
    const c = await login(apiBase, 'c@veyq.app', cPass, cReg.emergencyKit.secretKey)
    expect((await listMySpaces(apiBase, c)).map((s) => s.tenantId)).toContain(g3.id)

    // email relay (mailer mock): both modes return 204 when the link targets this instance's app origin.
    const appOrigin = process.env.VEYQ_APP_BASE ?? 'http://localhost:5173'
    await emailInviteToGroup(apiBase, a, g3.id, 'Member', 'pragmatic@veyq.app', appOrigin)
    await emailInviteToGroup(apiBase, a, g3.id, 'Member', 'protected@veyq.app', appOrigin, 'another out-of-band secret')
  }, 120000)
})
