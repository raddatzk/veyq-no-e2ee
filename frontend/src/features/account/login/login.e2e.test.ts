// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../../admin/setupServer/setupServer'
import { login, logout, fetchCurrentUser, fetchPersonalScope, openPersonalScopeGrant } from './login'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
// Real end-to-end against a running backend; opt-in so plain `npm test` doesn't require it.
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  const bin = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4))
  return Uint8Array.from(bin, (c) => c.charCodeAt(0))
}

suite('login + unlock E2E (web v1 crypto -> live backend)', () => {
  it('registers, logs in, and unlocks the identity key matching the stored public key', async () => {
    const email = `login-${Date.now()}@example.com`
    const password = 'correct horse battery staple'

    const { emergencyKit } = await registerServerOwner(apiBase, email, 'Owner', password)

    const session = await login(apiBase, email, password, emergencyKit.secretKey)
    expect(session.sessionToken.length).toBeGreaterThan(0)
    expect(session.identity.kemX.length).toBe(32)
    expect(session.identity.mlkemSeed.length).toBe(64)
    expect(session.identity.sign.length).toBe(32)

    // the session resolves on the server
    const me = await fetchCurrentUser(apiBase, session.sessionToken)
    expect(me.email).toBe(email)

    // the personal scope grant belongs to this identity and can be opened client-side only
    const personalGrant = await fetchPersonalScope(apiBase, session.sessionToken)
    const personalPriv = openPersonalScopeGrant(personalGrant, session.identity, session.identityPublicKey.kemPublicKey)
    const recoveredPersonalXPub = x25519.getPublicKey(personalPriv.kemX)
    const storedPersonalXPub = fromB64url(personalGrant.scopePublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredPersonalXPub)).toEqual(Array.from(storedPersonalXPub))

    // the recovered private key matches the server's stored public key (X25519 half)
    const recoveredXPub = x25519.getPublicKey(session.identity.kemX)
    const storedXPub = fromB64url(session.identityPublicKey.kemPublicKey).slice(0, 32)
    expect(Array.from(recoveredXPub)).toEqual(Array.from(storedXPub))

    // logout invalidates the session: the same token no longer resolves
    await logout(apiBase, session.sessionToken)
    await expect(fetchCurrentUser(apiBase, session.sessionToken)).rejects.toThrow()
  })
})
