// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant, openScopeGrant } from '../createTenantInviteOwner/tenant'
import { registerViaInvitation } from '../../account/registerViaInvitation/registerViaInvitation'
import { issueMemberInvite, acceptMemberInvite } from './inviteMembers'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

suite('invite-members-roles accept E2E (web -> live backend)', () => {
  it('an existing user confirms a member invite onto their existing account (no new account)', async () => {
    // owner A claims T1
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme', 'acme.example')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // B already has a standalone account (owner of their own tenant T2)
    const bPass = 'a whole new passphrase entirely'
    const t2 = await createTenant(apiBase, a.sessionToken, 'globex', 'Globex', 'globex.example')
    const t2Invite = await issueOwnerInvite(apiBase, a.sessionToken, t2.id)
    const bReg = await registerViaInvitation(apiBase, t2Invite.inviteId, t2Invite.bootstrapSecret, 'b@example.com', 'B', bPass)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)

    // A invites B to T1; B confirms onto their EXISTING account
    const memberInvite = await issueMemberInvite(apiBase, a, t1.id, 'Member')
    const membership = await acceptMemberInvite(apiBase, b, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey)

    // same account — no second account was created
    expect(membership.userId).toBe(bReg.user.id)
    expect(membership.role).toBe('Member')
    expect(membership.status).toBe('ACTIVE')

    // B recovers T1's scope from the grant they re-wrapped to their own identity
    const grant = await getMyScopeGrant(apiBase, b.sessionToken, t1.id)
    const scopePriv = openScopeGrant(grant, b.identity, b.identityPublicKey.kemPublicKey)
    const recoveredXPub = x25519.getPublicKey(scopePriv.kemX)
    const storedXPub = fromB64url(grant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredXPub)).toEqual(Array.from(storedXPub))
  })
})
