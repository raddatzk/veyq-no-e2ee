// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../setupServer/setupServer'
import { login, fetchCurrentUser } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant } from '../createTenantInviteOwner/tenant'
import { registerViaInvitation } from '../../account/registerViaInvitation/registerViaInvitation'
import { grantScopeToMember, removeMember } from './inviteMembers'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('invite-members-roles removal E2E (web -> live backend)', () => {
  it('removes a member: their grant is revoked and their session stops resolving', async () => {
    // owner A claims tenant T1
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // user B registers (lands in the directory), then A grants B access to T1
    const bPass = 'a whole new passphrase entirely'
    const t2 = await createTenant(apiBase, a.sessionToken, 'globex', 'Globex')
    const t2Invite = await issueOwnerInvite(apiBase, a.sessionToken, t2.id)
    const bReg = await registerViaInvitation(apiBase, t2Invite.inviteId, t2Invite.bootstrapSecret, 'b@example.com', 'B', bPass)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)
    await grantScopeToMember(apiBase, a, t1.id, 'b@example.com', 'Member')

    // B can read T1's scope grant up front
    expect((await getMyScopeGrant(apiBase, b.sessionToken, t1.id)).wrappedScopePrivateKey.bytes.length).toBeGreaterThan(0)

    // A removes B
    const membership = await removeMember(apiBase, a.sessionToken, t1.id, b.user.id)
    expect(membership.status).toBe('REMOVED')

    // instant revocation: B's session no longer resolves and the grant is gone
    await expect(fetchCurrentUser(apiBase, b.sessionToken)).rejects.toThrow()
    await expect(getMyScopeGrant(apiBase, b.sessionToken, t1.id)).rejects.toThrow()
  })
})
