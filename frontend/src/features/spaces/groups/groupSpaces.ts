// uc.spaces.groups (web) — a Group is a collaborative sub-space within a tenant (glossary: Tenant > Group
// > Personal). Create one by minting a GROUP_ROOT scope keypair, self-granting it, provisioning a content
// key, and sealing the group NAME under that content key (operator-blind). Members are added by wrapping
// the group scope key to their identity. The server stays blind to the name and the content.
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'

const EMPTY = new Uint8Array(0)
function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}
const auth = (token: string) => ({ authorization: `Bearer ${token}` })

export type Blob = { suiteId: string; bytes: string }
export type GroupSummary = { scopeId: string; tenantId: string; role: string; scopePublicKey: { suiteId: string; kemPublicKey: string }; sealedName: Blob }
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

  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/groups`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({
      scopePublicKey: { suiteId: 'v1', kemPublicKey: b64url(scopePublic) },
      wrappedScopePrivateKey: { suiteId: 'v1', bytes: b64url(grant) },
      contentKey: { suiteId: 'v1', bytes: b64url(wrappedCk) },
      sealedName: { suiteId: 'v1', bytes: b64url(sealName(ck, name.trim())) },
    }),
  })
  if (!res.ok) throw new Error(`create group failed: ${res.status} ${await res.text()}`)
  return res.json()
}
