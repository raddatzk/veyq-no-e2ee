// uc.admin.invite-members-roles (web) — existing-user direct grant. A key-holding Tenant Owner reads
// their own scope grant, unwraps the scope private key, re-wraps it to an existing user's published
// identity public key (from the key directory) and assigns a role. The server stays blind: it only
// ever sees ciphertext + the recipient id + role.
import * as v1 from '../../../shared/crypto/v1'
import { getMyScopeGrant } from '../createTenantInviteOwner/tenant'
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
