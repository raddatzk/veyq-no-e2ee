// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant, openScopeGrant } from '../createTenantInviteOwner/tenant'
import { issueMemberInvite, registerViaMemberInvite } from './inviteMembers'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

suite('invite-members-roles temp-wrap E2E (web -> live backend)', () => {
  it('issues a temp-wrap member invite, a new user registers onto the existing scope', async () => {
    // owner A claims tenant T1 (holds its scope)
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    // A issues a temp-wrap member invite (scope wrapped to a one-time temp key)
    const memberInvite = await issueMemberInvite(apiBase, a, t1.id, 'Member')
    expect(memberInvite.tempPrivateKey.length).toBeGreaterThan(0)

    // B (a brand-new account) accepts: unwraps with the temp key, re-wraps to self, registers
    const bPass = 'a whole new passphrase entirely'
    const bReg = await registerViaMemberInvite(
      apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, 'b@example.com', 'B', bPass,
    )
    expect(bReg.tenant.status).toBe('ACTIVE')
    expect(bReg.user.serverRole).toBe('ServerMember')

    // B logs in and recovers T1's scope private key from their own grant (full temp-wrap round trip)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)
    const grant = await getMyScopeGrant(apiBase, b.sessionToken, t1.id)
    const scopePriv = openScopeGrant(grant, b.identity, b.identityPublicKey.kemPublicKey)
    const recoveredXPub = x25519.getPublicKey(scopePriv.kemX)
    const storedXPub = fromB64url(grant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredXPub)).toEqual(Array.from(storedXPub))
  })
})
