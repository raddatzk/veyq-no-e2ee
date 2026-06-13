// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant, openScopeGrant } from '../createTenantInviteOwner/tenant'
import { registerViaInvitation } from '../../account/registerViaInvitation/registerViaInvitation'
import { grantScopeToMember } from './inviteMembers'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

suite('invite-members-roles E2E (web -> live backend)', () => {
  it('grants an existing user direct scope access + role, recipient unwraps the cross-user grant', async () => {
    // owner A: server owner, claims tenant T1 (holds its scope)
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme')
    const t1Invite = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, t1Invite.inviteId, t1Invite.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // user B: a brand-new account (becomes owner of T2) — now in the key directory
    const bPass = 'a whole new passphrase entirely'
    const t2 = await createTenant(apiBase, a.sessionToken, 'globex', 'Globex')
    const t2Invite = await issueOwnerInvite(apiBase, a.sessionToken, t2.id)
    const bReg = await registerViaInvitation(apiBase, t2Invite.inviteId, t2Invite.bootstrapSecret, 'b@example.com', 'B', bPass)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)

    // A grants B direct access to T1's scope + the Member role
    const membership = await grantScopeToMember(apiBase, a, t1.id, 'b@example.com', 'Member')
    expect(membership.userId).toBe(b.user.id)
    expect(membership.role).toBe('Member')
    expect(membership.status).toBe('ACTIVE')

    // B recovers T1's scope private key from the grant A wrapped to B's identity (server never held it)
    const grant = await getMyScopeGrant(apiBase, b.sessionToken, t1.id)
    const scopePriv = openScopeGrant(grant, b.identity, b.identityPublicKey.kemPublicKey)
    const recoveredXPub = x25519.getPublicKey(scopePriv.kemX)
    const storedXPub = fromB64url(grant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredXPub)).toEqual(Array.from(storedXPub))
  })
})
