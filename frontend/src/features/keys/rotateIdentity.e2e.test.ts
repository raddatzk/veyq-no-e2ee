// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../admin/setupServer/setupServer'
import { login } from '../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant, openScopeGrant } from '../admin/createTenantInviteOwner/tenant'
import { openPersonalScope, getOrCreateContentKey, writeItem, readItems } from '../content/scopeContent'
import { rotateIdentity } from './rotateIdentity'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

suite('rotate-identity E2E (web -> live backend)', () => {
  it('rotates the identity and keeps access to every scope + content via the new identity', async () => {
    const pass = 'correct horse battery staple'
    const { emergencyKit } = await registerServerOwner(apiBase, 'owner@example.com', 'Owner', pass)
    const a = await login(apiBase, 'owner@example.com', pass, emergencyKit.secretKey)

    // a tenant scope grant + encrypted content under the personal scope
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme', 'acme.example')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)
    const scope = await openPersonalScope(apiBase, a)
    const ck = await getOrCreateContentKey(apiBase, a, scope)
    await writeItem(apiBase, a, scope, ck, 'personal secret')

    // rotate the identity keypair
    await rotateIdentity(apiBase, a)

    // re-login unlocks the NEW identity under the unchanged master key
    const a2 = await login(apiBase, 'owner@example.com', pass, emergencyKit.secretKey)
    expect(a2.identityPublicKey.kemPublicKey).not.toBe(a.identityPublicKey.kemPublicKey)

    // the personal scope + its content are still readable via the new identity
    const scope2 = await openPersonalScope(apiBase, a2)
    const ck2 = await getOrCreateContentKey(apiBase, a2, scope2)
    expect(await readItems(apiBase, a2, scope2, ck2)).toContain('personal secret')

    // and the tenant scope grant opens under the new identity too
    const tGrant = await getMyScopeGrant(apiBase, a2.sessionToken, t1.id)
    const tScopePriv = openScopeGrant(tGrant, a2.identity, a2.identityPublicKey.kemPublicKey)
    const recoveredX = Array.from(x25519.getPublicKey(tScopePriv.kemX))
    const storedX = Array.from(fromB64url(tGrant.scopePublicKey.kemPublicKey).slice(0, 32))
    expect(recoveredX).toEqual(storedX)
  })
})
