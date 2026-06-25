// uc.spaces.create-group (web) — a Group is a collaborative sub-space within a tenant (glossary: Tenant > Group
// > Personal). Create one by minting a GROUP_ROOT scope keypair, self-granting it, provisioning a content
// key, and sealing the group NAME under that content key (operator-blind). Members are added by wrapping
// the group scope key to their identity. The server stays blind to the name and the content.
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import { b64url, fromB64url } from '../../../shared/base64'

const EMPTY = new Uint8Array(0)
const auth = (token: string) => ({ authorization: `Bearer ${token}` })

export type Blob = { suiteId: string; bytes: string }
export type GroupSummary = { groupId: string; scopeId: string; tenantId: string; role: string; scopePublicKey: { suiteId: string; kemPublicKey: string }; sealedName: Blob }
export type GroupMember = { userId: string; displayName: string; role: string }

/** Seal a name under a content key (nonce || AEAD, EMPTY aad) — same framing as a sealed item. */
function sealName(contentKey: Uint8Array, name: string): Uint8Array {
  const nonce = v1.randomBytes(12)
  return v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(name), EMPTY))
}

/** Decrypt a group's sealed name with its content key. */
export function openSealedName(contentKey: Uint8Array, sealedB64: string): string {
  const sealed = fromB64url(sealedB64)
  return new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
}

/** Create a group within a tenant: mint the scope, self-grant, provision the content key, seal the name. */
export async function createGroupSpace(apiBase: string, session: UnlockedSession, tenantId: string, name: string): Promise<GroupSummary> {
  const idKem = fromB64url(session.identityPublicKey.kemPublicKey)
  const scope = v1.generateIdentity() // hybrid-KEM bundle for the scope (sign field unused)
  const scopePublic = v1.concat(scope.pub.kemX, scope.pub.kemM)
  const scopePub = { x: scope.pub.kemX, m: scope.pub.kemM }
  const grant = v1.asymWrap({ x: idKem.slice(0, 32), m: idKem.slice(32) }, v1.serializeIdentityPrivate(scope), 'scope-grant', { nonce: v1.randomBytes(12) })
  const ck = v1.randomBytes(32)
  const wrappedCk = v1.asymWrap(scopePub, ck, 'content-key', { nonce: v1.randomBytes(12) })

  const res = await fetch(`${apiBase}/api/groups`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({
      scopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(scopePublic) },
      wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(grant) },
      wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrappedCk) },
      sealedName: { suiteId: 'v1', bytes: b64url(sealName(ck, name.trim())) },
    }),
  })
  if (!res.ok) throw await apiError(res, 'create group')
  return res.json()
}

export type GroupRole = 'GroupOwner' | 'GroupMember'

/** List a group's members (any group member may). */
export async function listGroupMembers(apiBase: string, session: UnlockedSession, tenantId: string, groupId: string): Promise<GroupMember[]> {
  const res = await fetch(`${apiBase}/api/groups/${groupId}/members`, { headers: auth(session.sessionToken) })
  if (!res.ok) throw await apiError(res, 'list group members')
  return (await res.json()).members
}

/**
 * Add a tenant member to a group (GroupOwner only). Unwraps the caller's OWN group scope grant to the raw
 * scope private bytes, then re-wraps them to the target's identity — the server never sees the scope key.
 */
export async function addGroupMember(
  apiBase: string,
  session: UnlockedSession,
  tenantId: string,
  groupId: string,
  target: { userId: string; kemPublicKeyB64: string },
  role: GroupRole = 'GroupMember',
): Promise<GroupMember> {
  const gRes = await fetch(`${apiBase}/api/scopes/${groupId}/grants/me`, { headers: auth(session.sessionToken) })
  if (!gRes.ok) throw await apiError(gRes, 'group grant')
  const g = await gRes.json()
  const myKem = fromB64url(session.identityPublicKey.kemPublicKey)
  const scopePrivBytes = v1.asymUnwrap(v1.hybridSecret(session.identity), { x: myKem.slice(0, 32), m: myKem.slice(32) }, fromB64url(g.wrappedScopePrivateKey.bytes), 'scope-grant')
  const tKem = fromB64url(target.kemPublicKeyB64)
  const wrapped = v1.asymWrap({ x: tKem.slice(0, 32), m: tKem.slice(32) }, scopePrivBytes, 'scope-grant', { nonce: v1.randomBytes(12) })

  const res = await fetch(`${apiBase}/api/groups/${groupId}/members`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({ userId: target.userId, wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(wrapped) }, role }),
  })
  if (!res.ok) throw await apiError(res, 'add group member')
  return res.json()
}

/** Change a group member's role (GroupOwner only; the server refuses to demote the last GroupOwner). */
export async function setGroupMemberRole(apiBase: string, session: UnlockedSession, tenantId: string, groupId: string, userId: string, role: GroupRole): Promise<GroupMember> {
  const res = await fetch(`${apiBase}/api/groups/${groupId}/members/${userId}/role`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({ role }),
  })
  if (!res.ok) throw await apiError(res, 'set group role')
  return res.json()
}

/** Remove a member from a group (GroupOwner only; the server refuses to remove the last GroupOwner). */
export async function removeGroupMember(apiBase: string, session: UnlockedSession, tenantId: string, groupId: string, userId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/groups/${groupId}/members/${userId}`, { method: 'DELETE', headers: auth(session.sessionToken) })
  if (!res.ok) throw await apiError(res, 'remove group member')
}

/** Delete a group entirely (GroupOwner only). */
export async function deleteGroup(apiBase: string, session: UnlockedSession, tenantId: string, groupId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/groups/${groupId}`, { method: 'DELETE', headers: auth(session.sessionToken) })
  if (!res.ok) throw await apiError(res, 'delete group')
}
