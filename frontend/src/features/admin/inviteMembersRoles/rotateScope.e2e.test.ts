// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { x25519 } from '@noble/curves/ed25519.js'
import { registerServerOwner } from '../setupServer/setupServer'
import { login } from '../../account/login/login'
import { createTenant, issueOwnerInvite, claimTenantOwnership, getMyScopeGrant, openScopeGrant } from '../createTenantInviteOwner/tenant'
import { issueMemberInvite, registerViaMemberInvite, rotateScope } from './inviteMembers'
import { fromB64url } from '../../../shared/base64'

const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env ?? {}
const apiBase = env.VEYQ_API_BASE ?? 'http://localhost:8080'
const suite = env.VEYQ_E2E === '1' ? describe : describe.skip

const xPubOf = (g: Awaited<ReturnType<typeof getMyScopeGrant>>, session: Awaited<ReturnType<typeof login>>) =>
  Array.from(x25519.getPublicKey(openScopeGrant(g, session.identity, session.identityPublicKey.kemPublicKey).kemX))

suite('invite-members-roles rotation E2E (web -> live backend)', () => {
  it('rotates the scope, re-granting a new keypair to every active member', async () => {
    // owner A claims T1; member B joins via a temp-wrap invite
    const aPass = 'correct horse battery staple'
    const { emergencyKit: aKit } = await registerServerOwner(apiBase, 'admin@example.com', 'Admin', aPass)
    const a = await login(apiBase, 'admin@example.com', aPass, aKit.secretKey)
    const t1 = await createTenant(apiBase, a.sessionToken, 'acme', 'Acme', 'acme.example')
    const inv = await issueOwnerInvite(apiBase, a.sessionToken, t1.id)
    await claimTenantOwnership(apiBase, a.sessionToken, inv.inviteId, inv.bootstrapSecret, a.identityPublicKey.kemPublicKey)

    const bPass = 'a whole new passphrase entirely'
    const memberInvite = await issueMemberInvite(apiBase, a, t1.id, 'Member')
    const bReg = await registerViaMemberInvite(apiBase, memberInvite.inviteId, memberInvite.bootstrapSecret, memberInvite.tempPrivateKey, 'b@example.com', 'B', bPass)
    const b = await login(apiBase, 'b@example.com', bPass, bReg.emergencyKit.secretKey)

    const before = fromB64url((await getMyScopeGrant(apiBase, a.sessionToken, t1.id)).scopePublicKey.kemPublicKey).slice(0, 32)

    // A rotates the scope — a fresh keypair re-wrapped to both members
    const result = await rotateScope(apiBase, a, t1.id)
    expect(result.memberCount).toBe(2)

    // both members now recover the SAME new scope keypair from their new grant, and it changed
    const aGrant = await getMyScopeGrant(apiBase, a.sessionToken, t1.id)
    const bGrant = await getMyScopeGrant(apiBase, b.sessionToken, t1.id)
    const storedNew = Array.from(fromB64url(aGrant.scopePublicKey.kemPublicKey).slice(0, 32))
    expect(xPubOf(aGrant, a)).toEqual(storedNew)
    expect(xPubOf(bGrant, b)).toEqual(storedNew)
    expect(storedNew).not.toEqual(Array.from(before))
  })
})
