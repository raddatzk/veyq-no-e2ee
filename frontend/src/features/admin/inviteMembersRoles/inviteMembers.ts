// uc.admin.invite-members-roles (web) — member management for a tenant: temp-wrap invites for new
// people (email / white-label link), listing + rotating the scope, removing members and changing roles.
// Adding people is invite-only — there is no directory lookup of arbitrary emails. The server stays blind.
import * as v1 from '../../../shared/crypto/v1'
import { getMyScopeGrant } from '../createTenantInviteOwner/tenant'
import { getInviteMetadata, registerWithScope, type RegisterResult } from '../../account/registerViaInvitation/registerViaInvitation'
import type { UnlockedSession } from '../../account/login/login'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}

export type MembershipSummary = { tenantId: string; userId: string; role: string; status: string }
export type MemberRole = 'Owner' | 'Member'

export type TenantMember = {
  userId: string
  displayName: string
  role: string
  status: string
  identityPublicKey: { suiteId: string; kemPublicKey: string; signPublicKey: string }
}

/** List a tenant's members with their published identity public keys (for re-wrapping a rotated scope). */
export async function listTenantMembers(apiBase: string, token: string, tenantId: string): Promise<TenantMember[]> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/members`, { headers: { authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`list members failed: ${res.status} ${await res.text()}`)
  return (await res.json()).members
}

/**
 * Rotate the tenant scope (eventual layer of AD-24): a key-holding Owner mints a NEW scope keypair and
 * re-wraps it to every active member, so a previously-removed member loses key access to future content.
 * The owner needs no old key — a fresh scope is born and granted to all remaining members.
 */
export async function rotateScope(
  apiBase: string,
  owner: UnlockedSession,
  tenantId: string,
): Promise<{ scopeId: string; memberCount: number }> {
  const members = (await listTenantMembers(apiBase, owner.sessionToken, tenantId)).filter((m) => m.status === 'ACTIVE')
  const newScope = v1.generateIdentity()
  const newScopePrivate = v1.serializeIdentityPrivate(newScope)
  const grants = members.map((m) => {
    const kem = fromB64url(m.identityPublicKey.kemPublicKey)
    return {
      userId: m.userId,
      wrappedScopePrivateKey: {
        suiteId: 'v1',
        bytes: b64url(v1.asymWrap({ x: kem.slice(0, 32), m: kem.slice(32) }, newScopePrivate, 'scope-grant', { nonce: v1.randomBytes(12) })),
      },
    }
  })
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/scope/rotate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.sessionToken}` },
    body: JSON.stringify({
      scopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(v1.concat(newScope.pub.kemX, newScope.pub.kemM)) },
      grants,
    }),
  })
  if (!res.ok) throw new Error(`rotate scope failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/**
 * Remove a member (instant layer of AD-24): the Owner revokes the member's scope grant + sessions. The
 * membership becomes REMOVED (rejoinable). No crypto — eventual scope-key rotation is a separate step.
 */
export async function removeMember(
  apiBase: string,
  ownerToken: string,
  tenantId: string,
  userId: string,
): Promise<MembershipSummary> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/members/${encodeURIComponent(userId)}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${ownerToken}` },
  })
  if (!res.ok) throw new Error(`remove member failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** Change a member's role (membership/settings authority only — not content access, ADR 0005). No crypto. */
export async function changeMemberRole(
  apiBase: string,
  ownerToken: string,
  tenantId: string,
  userId: string,
  role: MemberRole,
): Promise<MembershipSummary> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/members/${encodeURIComponent(userId)}/role`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${ownerToken}` },
    body: JSON.stringify({ role }),
  })
  if (!res.ok) throw new Error(`change role failed: ${res.status} ${await res.text()}`)
  return res.json()
}

export type MemberInvite = { inviteId: string; bootstrapSecret: string; tempPrivateKey: string }

/**
 * Issue a temp-wrap invite for a NEW user. The owner unwraps their own scope private key, generates a
 * one-time temp keypair, and wraps the scope to the temp public key. The server stores only the temp
 * public key + ciphertext; the temp private key is returned to ride the invite URL fragment.
 */
export async function issueMemberInvite(
  apiBase: string,
  owner: UnlockedSession,
  tenantId: string,
  role: MemberRole,
): Promise<MemberInvite> {
  const myGrant = await getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
  const ownerKem = fromB64url(owner.identityPublicKey.kemPublicKey)
  const sk = v1.hybridSecret(owner.identity)
  const scopePrivateBytes = v1.asymUnwrap(
    sk,
    { x: ownerKem.slice(0, 32), m: ownerKem.slice(32) },
    fromB64url(myGrant.wrappedScopePrivateKey.bytes),
    'scope-grant',
  )

  const temp = v1.generateIdentity()
  const tempPublic = v1.concat(temp.pub.kemX, temp.pub.kemM)
  const tempWrapped = v1.asymWrap({ x: temp.pub.kemX, m: temp.pub.kemM }, scopePrivateBytes, 'scope-grant', {
    nonce: v1.randomBytes(12),
  })

  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/member-invites`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.sessionToken}` },
    body: JSON.stringify({
      role,
      tempPublicKey: { suiteId: 'v1', kemPublicKey: b64url(tempPublic) },
      tempWrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(tempWrapped) },
    }),
  })
  if (!res.ok) throw new Error(`member-invite failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  return {
    inviteId: body.inviteId,
    bootstrapSecret: body.bootstrapSecret,
    tempPrivateKey: b64url(v1.serializeIdentityPrivate(temp)),
  }
}

/**
 * Register a NEW account from a temp-wrap member invite. The invitee unwraps the scope private key with
 * the temp private key (from the URL fragment) and re-wraps it to their own identity — the temp-wrap
 * counterpart to the owner-claim path. The tenant scope is the existing one (echoed from metadata).
 */
export async function registerViaMemberInvite(
  apiBase: string,
  inviteId: string,
  bootstrapSecret: string,
  tempPrivateKeyB64: string,
  email: string,
  displayName: string,
  password: string,
): Promise<RegisterResult> {
  const meta = await getInviteMetadata(apiBase, inviteId)
  if (!meta.tempPublicKey || !meta.tempWrappedScopePrivateKey || !meta.scopePublicKey) {
    throw new Error('invite is not a temp-wrap member invite')
  }
  const tempPriv = v1.deserializeIdentityPrivate(fromB64url(tempPrivateKeyB64))
  const tempKem = fromB64url(meta.tempPublicKey.kemPublicKey)
  const scopePrivateBytes = v1.asymUnwrap(
    v1.hybridSecret(tempPriv),
    { x: tempKem.slice(0, 32), m: tempKem.slice(32) },
    fromB64url(meta.tempWrappedScopePrivateKey.bytes),
    'scope-grant',
  )
  const scopePublicKey = meta.scopePublicKey.kemPublicKey
  return registerWithScope(apiBase, inviteId, bootstrapSecret, email, displayName, password, (idPub) => ({
    scopePublicKey,
    wrappedScopePrivateKey: b64url(v1.asymWrap(idPub, scopePrivateBytes, 'scope-grant', { nonce: v1.randomBytes(12) })),
  }))
}
