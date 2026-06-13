// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { fetchPersonalScope, login, openPersonalScopeGrant } from '../login/login'
import { createTenant, issueOwnerInvite, getMyScopeGrant, openScopeGrant } from '../../admin/createTenantInviteOwner/tenant'
import { getInviteMetadata, registerViaInvitation } from './registerViaInvitation'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

suite('register-via-invitation E2E (web -> live backend)', () => {
  it('registers a new owner from an invite, then logs in and round-trips the scope grant', async () => {
    // a Server Owner provisions the tenant + owner invite
    const adminPassword = 'correct horse battery staple'
    const { emergencyKit: adminKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', adminPassword)
    const admin = await login(apiBase, 'admin@example.com', adminPassword, adminKit.secretKey)
    const tenant = await createTenant(apiBase, admin.sessionToken, 'acme', 'Acme', 'acme.example')
    const invite = await issueOwnerInvite(apiBase, admin.sessionToken, tenant.id)

    // public metadata is visible without the bootstrap secret
    const meta = await getInviteMetadata(apiBase, invite.inviteId)
    expect(meta.tenant.name).toBe('acme')
    expect(meta.role).toBe('Owner')
    expect(meta.status).toBe('PENDING')

    // the invitee (a brand-new account) registers and claims the tenant in one step
    const inviteePassword = 'a whole new passphrase entirely'
    const result = await registerViaInvitation(
      apiBase,
      invite.inviteId,
      invite.bootstrapSecret,
      'tenant-owner@example.com',
      'Tenant Owner',
      inviteePassword,
    )
    expect(result.tenant.status).toBe('ACTIVE')
    expect(result.user.email).toBe('tenant-owner@example.com')
    expect(result.user.serverRole).toBe('ServerMember')
    expect(result.sessionToken.length).toBeGreaterThan(0)

    // the new account can log in independently (proves the auth verifier + master wrap are consistent)
    const session = await login(apiBase, 'tenant-owner@example.com', inviteePassword, result.emergencyKit.secretKey)
    expect(session.user.id).toBe(result.user.id)

    // and recover its personal scope grant — unshared, scoped to this account
    const personalGrant = await fetchPersonalScope(apiBase, session.sessionToken)
    const personalPriv = openPersonalScopeGrant(personalGrant, session.identity, session.identityPublicKey.kemPublicKey)
    const recoveredPersonalXPub = x25519.getPublicKey(personalPriv.kemX)
    const storedPersonalXPub = fromB64url(personalGrant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredPersonalXPub)).toEqual(Array.from(storedPersonalXPub))

    // and recover its tenant scope grant — the server never held the scope private key
    const grant = await getMyScopeGrant(apiBase, session.sessionToken, tenant.id)
    const scopePriv = openScopeGrant(grant, session.identity, session.identityPublicKey.kemPublicKey)
    const recoveredXPub = x25519.getPublicKey(scopePriv.kemX)
    const storedXPub = fromB64url(grant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredXPub)).toEqual(Array.from(storedXPub))
  })
})
