// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant, openScopeGrant } from './tenant'
import { fromB64url } from '../../../shared/base64'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

suite('create-tenant-invite-owner E2E (web -> live backend)', () => {
  it('provisions a tenant, claims ownership, and round-trips the scope grant', async () => {
    const password = 'correct horse battery staple'
    // an existing Server Owner (the existing-user A1 path)
    const { emergencyKit } = await registerServerOwner(apiBase, 'owner@example.com', 'Owner', password)
    const session = await login(apiBase, 'owner@example.com', password, emergencyKit.secretKey)

    // 1. provision a tenant shell + owner invite
    const tenant = await createTenant(apiBase, session.sessionToken, 'acme', 'Acme', 'acme.example')
    expect(tenant.status).toBe('PENDING')
    const invite = await issueOwnerInvite(apiBase, session.sessionToken, tenant.id)
    expect(invite.bootstrapSecret.length).toBeGreaterThan(0)

    // 2. claim ownership — mints the tenant scope keypair, grants it to self
    const claimed = await claimTenantOwnership(
      apiBase,
      session.sessionToken,
      invite.inviteId,
      invite.bootstrapSecret,
      session.identityPublicKey.kemPublicKey,
    )
    expect(claimed.status).toBe('ACTIVE')

    // 3. fetch the stored grant and unwrap it with the identity private key (server never held it)
    const grant = await getMyScopeGrant(apiBase, session.sessionToken, tenant.id)
    const scopePriv = openScopeGrant(grant, session.identity, session.identityPublicKey.kemPublicKey)

    // the recovered scope private key matches the stored scope public key (X25519 half)
    const recoveredXPub = x25519.getPublicKey(scopePriv.kemX)
    const storedXPub = fromB64url(grant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredXPub)).toEqual(Array.from(storedXPub))
  })
})
