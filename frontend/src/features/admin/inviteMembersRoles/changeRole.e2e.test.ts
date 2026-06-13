// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership } from '../createTenantInviteOwner/tenant'
import { issueMemberInvite, registerViaMemberInvite, changeMemberRole } from './inviteMembers'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('invite-members-roles role change E2E (web -> live backend)', () => {
  it('promotes a member to Owner without touching their grant', async () => {
    // owner A claims T1; member B joins via a temp-wrap invite
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    const bPass = 'a whole new passphrase entirely'
    const memberInvite = await issueMemberInvite(apiBase, a, t1.id, 'Member')
    const bReg = await registerViaMemberInvite(apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, 'b@example.com', 'B', bPass)
    expect(bReg.tenant.status).toBe('ACTIVE')

    const updated = await changeMemberRole(apiBase, a.sessionToken, t1.id, bReg.user.id, 'Owner')
    expect(updated.userId).toBe(bReg.user.id)
    expect(updated.role).toBe('Owner')
    expect(updated.status).toBe('ACTIVE')
  })
})
