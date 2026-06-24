// uc.admin.invite-members-roles (web) — member management for a tenant: temp-wrap invites for new
// people (email / white-label link), listing + rotating the scope, removing members and changing roles.
// Adding people is invite-only — there is no directory lookup of arbitrary emails. The server stays blind.
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import { getMyScopeGrant, openScopeGrant } from '../createTenantInviteOwner/tenant'
import { getInviteMetadata, getInviteMaterial, registerWithScope, type RegisterResult } from '../../account/registerViaInvitation/registerViaInvitation'
import { decodeNode, resealNode, slugTokenFor, type MemoryNode } from '../../memory/createTree/memoryTree'
import type { UnlockedSession } from '../../account/login/login'
import { b64url, fromB64url } from '../../../shared/base64'

const EMPTY = new Uint8Array(0)

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
  const res = await fetch(`${apiBase}/api/tenant/members`, { headers: { authorization: `Bearer ${token}` } })
  if (!res.ok) throw await apiError(res, 'list members')
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
  const newScopePub = { x: newScope.pub.kemX, m: newScope.pub.kemM }
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

  // Forward secrecy: if the scope has content, mint a FRESH content key (not a re-wrap) and re-seal every
  // item under it. A removed member who cached the OLD content key cannot read content written after the
  // rotation, and the old ciphertext is overwritten — so the cached key opens nothing going forward.
  const myGrant = await getMyScopeGrant(apiBase, owner.sessionToken, tenantId)
  let wrappedContentKey: { suiteId: string; bytes: string } | undefined
  let reSealedItems: { id: string; sealedContent: { suiteId: string; bytes: string } }[] | undefined
  let reSealedNodes: { id: string; slugToken: string; sealedContent: { suiteId: string; bytes: string } }[] | undefined
  const ckRes = await fetch(`${apiBase}/api/scopes/${myGrant.scopeId}/content-key`, { headers: { authorization: `Bearer ${owner.sessionToken}` } })
  if (ckRes.ok) {
    const oldScopePriv = openScopeGrant(myGrant, owner.identity, owner.identityPublicKey.kemPublicKey)
    const oldKem = fromB64url(myGrant.scopePublicKey.kemPublicKey)
    const oldCk = v1.asymUnwrap(
      v1.hybridSecret(oldScopePriv),
      { x: oldKem.slice(0, 32), m: oldKem.slice(32) },
      fromB64url((await ckRes.json()).wrappedContentKey.bytes),
      'content-key',
    )
    const newCk = v1.randomBytes(32)
    wrappedContentKey = { suiteId: 'v1', bytes: b64url(v1.asymWrap(newScopePub, newCk, 'content-key', { nonce: v1.randomBytes(12) })) }

    const itemsRes = await fetch(`${apiBase}/api/scopes/${myGrant.scopeId}/items`, { headers: { authorization: `Bearer ${owner.sessionToken}` } })
    if (!itemsRes.ok) throw new Error(`item list failed: ${itemsRes.status}`)
    reSealedItems = (await itemsRes.json()).items.map((it: { id: string; sealedContent: { bytes: string } }) => {
      const sealed = fromB64url(it.sealedContent.bytes)
      const pt = v1.aeadOpen(oldCk, sealed.slice(0, 12), sealed.slice(12), EMPTY)
      const nonce = v1.randomBytes(12)
      return { id: it.id, sealedContent: { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(newCk, nonce, pt, EMPTY))) } }
    })

    // Memory-tree nodes are sealed under the content key too (name + slug → slug token). Re-seal each under
    // the fresh key and recompute its slug token, or names orphan and a removed member's cached key reads them.
    const nodesRes = await fetch(`${apiBase}/api/scopes/${myGrant.scopeId}/nodes`, { headers: { authorization: `Bearer ${owner.sessionToken}` } })
    if (!nodesRes.ok) throw new Error(`node list failed: ${nodesRes.status}`)
    reSealedNodes = (await nodesRes.json()).nodes.map((n: MemoryNode) => {
      const content = decodeNode(oldCk, n)
      const parentSlot = n.parentId ?? myGrant.scopeId // root node → the scope id (the server's parent_id sentinel)
      return { id: n.id, slugToken: slugTokenFor(newCk, parentSlot, content.slug), sealedContent: { suiteId: 'v1', bytes: b64url(resealNode(newCk, content)) } }
    })
  } else if (ckRes.status !== 404) throw new Error(`content-key fetch failed: ${ckRes.status}`)

  const res = await fetch(`${apiBase}/api/tenant/scope/rotate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.sessionToken}` },
    body: JSON.stringify({
      scopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(v1.concat(newScope.pub.kemX, newScope.pub.kemM)) },
      grants,
      ...(wrappedContentKey ? { wrappedContentKey } : {}),
      ...(reSealedItems ? { reSealedItems } : {}),
      ...(reSealedNodes ? { reSealedNodes } : {}),
    }),
  })
  if (!res.ok) throw await apiError(res, 'rotate scope')
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
  const res = await fetch(`${apiBase}/api/tenant/members/${encodeURIComponent(userId)}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${ownerToken}` },
  })
  if (!res.ok) throw await apiError(res, 'remove member')
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
  const res = await fetch(`${apiBase}/api/tenant/members/${encodeURIComponent(userId)}/role`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${ownerToken}` },
    body: JSON.stringify({ role }),
  })
  if (!res.ok) throw await apiError(res, 'change role')
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

  const res = await fetch(`${apiBase}/api/tenant/member-invites`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${owner.sessionToken}` },
    body: JSON.stringify({
      role,
      tempPublicKey: { suiteId: 'v1', kemPublicKey: b64url(tempPublic) },
      tempWrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(tempWrapped) },
    }),
  })
  if (!res.ok) throw await apiError(res, 'member-invite')
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
  if (!meta.scopePublicKey) throw new Error('invite is not a temp-wrap member invite')
  // the temp-wrapped grant is gated behind the bootstrap secret (not in public metadata)
  const material = await getInviteMaterial(apiBase, inviteId, bootstrapSecret)
  const tempPriv = v1.deserializeIdentityPrivate(fromB64url(tempPrivateKeyB64))
  const tempKem = fromB64url(material.tempPublicKey.kemPublicKey)
  const scopePrivateBytes = v1.asymUnwrap(
    v1.hybridSecret(tempPriv),
    { x: tempKem.slice(0, 32), m: tempKem.slice(32) },
    fromB64url(material.tempWrappedScopePrivateKey.bytes),
    'scope-grant',
  )
  const scopePublicKey = meta.scopePublicKey.kemPublicKey
  return registerWithScope(apiBase, inviteId, bootstrapSecret, email, displayName, password, (idPub) => ({
    scopePublicKey,
    wrappedScopePrivateKey: b64url(v1.asymWrap(idPub, scopePrivateBytes, 'scope-grant', { nonce: v1.randomBytes(12) })),
  }))
}

/**
 * Accept a temp-wrap member invite onto an EXISTING (already logged-in) account — the "confirm" path.
 * Unwrap the scope with the temp key (from the URL fragment) and re-wrap it to the caller's own identity;
 * the server adds the grant + membership on the existing scope. No new account.
 */
export async function acceptMemberInvite(
  apiBase: string,
  session: UnlockedSession,
  inviteId: string,
  bootstrapSecret: string,
  tempPrivateKeyB64: string,
): Promise<MembershipSummary> {
  // the temp-wrapped grant is gated behind the bootstrap secret (not in public metadata)
  const material = await getInviteMaterial(apiBase, inviteId, bootstrapSecret)
  const tempPriv = v1.deserializeIdentityPrivate(fromB64url(tempPrivateKeyB64))
  const tempKem = fromB64url(material.tempPublicKey.kemPublicKey)
  const scopePrivateBytes = v1.asymUnwrap(
    v1.hybridSecret(tempPriv),
    { x: tempKem.slice(0, 32), m: tempKem.slice(32) },
    fromB64url(material.tempWrappedScopePrivateKey.bytes),
    'scope-grant',
  )
  const ownKem = fromB64url(session.identityPublicKey.kemPublicKey)
  const rewrapped = v1.asymWrap({ x: ownKem.slice(0, 32), m: ownKem.slice(32) }, scopePrivateBytes, 'scope-grant', {
    nonce: v1.randomBytes(12),
  })
  const res = await fetch(`${apiBase}/api/tenant-invites/${inviteId}/accept`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ bootstrapSecret, wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(rewrapped) } }),
  })
  if (!res.ok) throw await apiError(res, 'accept')
  return res.json()
}
