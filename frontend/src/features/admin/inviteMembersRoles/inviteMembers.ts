// uc.admin.invite-members-roles (web) — existing-user direct grant. A key-holding Tenant Owner reads
// their own scope grant, unwraps the scope private key, re-wraps it to an existing user's published
// identity public key (from the key directory) and assigns a role. The server stays blind: it only
// ever sees ciphertext + the recipient id + role.
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

export type DirectoryEntry = {
  userId: string
  displayName: string
  identityPublicKey: { suiteId: string; kemPublicKey: string; signPublicKey: string }
}
export type MembershipSummary = { tenantId: string; userId: string; role: string; status: string }
export type MemberRole = 'Owner' | 'Member'

/** Look up a user's published identity public key by email (the key directory). */
export async function lookupDirectoryEntry(apiBase: string, token: string, email: string): Promise<DirectoryEntry> {
  const res = await fetch(`${apiBase}/api/directory/users?email=${encodeURIComponent(email)}`, {
    headers: { authorization: `Bearer ${token}` },
  })
  if (!res.ok) throw new Error(`directory lookup failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/**
 * Grant an existing user (looked up by email) access to the tenant scope + a role. The owner unwraps
 * their own scope private key and re-wraps it to the recipient's identity public key (a direct wrap —
 * no temp-wrap, since the recipient already has an identity).
 */
export async function grantScopeToMember(
  apiBase: string,
  owner: UnlockedSession,
  tenantId: string,
  recipientEmail: string,
  role: MemberRole,
): Promise<MembershipSummary> {
  // 1. read our own grant and recover the scope private key (raw serialized bytes)
  const myGrant = await getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
  const ownerKem = fromB64url(owner.identityPublicKey.kemPublicKey)
  const ownerPub = { x: ownerKem.slice(0, 32), m: ownerKem.slice(32) }
  const sk = v1.hybridSecret(owner.identity)
  const scopePrivateBytes = v1.asymUnwrap(sk, ownerPub, fromB64url(myGrant.wrappedScopePrivateKey.bytes), 'scope-grant')

  // 2. look up the recipient and re-wrap the scope private key to their identity public key
  const recipient = await lookupDirectoryEntry(apiBase, owner.sessionToken, recipientEmail)
  const rKem = fromB64url(recipient.identityPublicKey.kemPublicKey)
  const rewrapped = v1.asymWrap({ x: rKem.slice(0, 32), m: rKem.slice(32) }, scopePrivateBytes, 'scope-grant', {
    nonce: v1.randomBytes(12),
  })

  // 3. hand the server ciphertext + the recipient + the role
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/grants`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.sessionToken}` },
    body: JSON.stringify({
      recipientUserId: recipient.userId,
      role,
      wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(rewrapped) },
    }),
  })
  if (!res.ok) throw new Error(`grant failed: ${res.status} ${await res.text()}`)
  return res.json()
}

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
