// uc.spaces / content (web) — the first real use of a scope: write and read encrypted content under it.
// AD-03: a stable per-scope content key (wrapped to the scope public key) seals items; the server only
// ever stores ciphertext. Targets the personal scope here, but the ops are scope-generic.
import { apiError } from '../../shared/apiError'
import { authFetch } from '../../shared/api/sessionFetch'
import * as v1 from '../../shared/crypto/v1'
import { openScopeGrant } from '../admin/createTenantInviteOwner/tenant'
import type { UnlockedSession } from '../account/login/login'
import { b64url, fromB64url } from '../../shared/base64'

const EMPTY = new Uint8Array(0)

export type OpenScope = {
  scopeId: string
  scopePub: { x: Uint8Array; m: Uint8Array }
  scopePriv: v1.Identity['priv']
}

/** Fetch + open the caller's personal scope: unwrap its private key with the user's identity. */
export async function openPersonalScope(
  apiBase: string,
  session: UnlockedSession
): Promise<OpenScope> {
  const res = await authFetch(apiBase, session, '/api/spaces')
  if (!res.ok) throw await apiError(res, 'list spaces')
  const personal = (await res.json()).spaces.find((space: { kind: string }) => space.kind === 'PERSONAL')
  if (!personal) throw new Error('personal space not found')
  return openPersonalScopeById(apiBase, session, personal.scopeId)
}

/** Open a concrete tenant-bound personal scope from the space roster. */
export async function openPersonalScopeById(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string
): Promise<OpenScope> {
  const res = await authFetch(apiBase, session, `/api/scopes/${scopeId}/grants/me`)
  if (!res.ok) throw await apiError(res, 'personal scope grant')
  const g = await res.json()
  const idKem = fromB64url(session.identityPublicKey.kemPublicKey)
  const scopePrivBytes = v1.asymUnwrap(
    v1.hybridSecret(session.identity),
    { x: idKem.slice(0, 32), m: idKem.slice(32) },
    fromB64url(g.wrappedScopePrivateKey.bytes),
    'personal-scope-grant'
  )
  const scopeKem = fromB64url(g.scopePublicKey.kemPublicKey)
  return {
    scopeId: g.scopeId,
    scopePub: { x: scopeKem.slice(0, 32), m: scopeKem.slice(32) },
    scopePriv: v1.deserializeIdentityPrivate(scopePrivBytes)
  }
}

/** Fetch + open a tenant scope the caller holds a grant for (re-fetches the current grant each call). */
export async function openTenantScope(
  apiBase: string,
  session: UnlockedSession,
  tenantId: string
): Promise<OpenScope> {
  const res = await authFetch(apiBase, session, '/api/spaces')
  if (!res.ok) throw await apiError(res, 'list spaces')
  const tenant = (await res.json()).spaces.find((space: { kind: string; tenantId?: string }) => space.kind === 'TENANT' && space.tenantId === tenantId)
  if (!tenant) throw new Error('tenant space not found')
  return openScopeById(apiBase, session, tenant.scopeId)
}

/** Open ANY scope the caller holds a grant for, by scope id (e.g. a group scope, which has no tenant path). */
export async function openScopeById(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string
): Promise<OpenScope> {
  const res = await authFetch(apiBase, session, `/api/scopes/${scopeId}/grants/me`)
  if (!res.ok) throw await apiError(res, 'scope grant')
  const g = await res.json()
  const scopePriv = openScopeGrant(g, session.identity, session.identityPublicKey.kemPublicKey)
  const scopeKem = fromB64url(g.scopePublicKey.kemPublicKey)
  return {
    scopeId: g.scopeId,
    scopePub: { x: scopeKem.slice(0, 32), m: scopeKem.slice(32) },
    scopePriv
  }
}

/** Get the scope's content key, provisioning one (wrapped to the scope) on first use. */
export async function getOrCreateContentKey(
  apiBase: string,
  session: UnlockedSession,
  scope: OpenScope
): Promise<Uint8Array> {
  const got = await authFetch(apiBase, session, `/api/scopes/${scope.scopeId}/content-key`)
  if (got.ok) {
    const { wrappedContentKey } = await got.json()
    return v1.asymUnwrap(
      v1.hybridSecret(scope.scopePriv),
      scope.scopePub,
      fromB64url(wrappedContentKey.bytes),
      'content-key'
    )
  }
  if (got.status !== 404) throw new Error(`content-key fetch failed: ${got.status}`)

  const ck = v1.randomBytes(32)
  const wrapped = v1.asymWrap(scope.scopePub, ck, 'content-key', { nonce: v1.randomBytes(12) })
  const put = await authFetch(apiBase, session, `/api/scopes/${scope.scopeId}/content-key`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrapped) } })
  })
  if (put.status === 409) return getOrCreateContentKey(apiBase, session, scope) // raced — adopt the existing key
  if (!put.ok) throw await apiError(put, 'content-key provision')
  return ck
}

/** Seal a string under the content key and store it; returns the item id. */
export async function writeItem(
  apiBase: string,
  session: UnlockedSession,
  scope: OpenScope,
  contentKey: Uint8Array,
  plaintext: string
): Promise<string> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(plaintext), EMPTY))
  const res = await authFetch(apiBase, session, `/api/scopes/${scope.scopeId}/items`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedContent: { suiteId: 'v1', bytes: b64url(sealed) } })
  })
  if (!res.ok) throw await apiError(res, 'item create')
  return (await res.json()).id
}

/** List the scope's items and decrypt each back to its plaintext string. */
export async function readItems(
  apiBase: string,
  session: UnlockedSession,
  scope: OpenScope,
  contentKey: Uint8Array
): Promise<string[]> {
  const res = await authFetch(apiBase, session, `/api/scopes/${scope.scopeId}/items`)
  if (!res.ok) throw new Error(`item list failed: ${res.status}`)
  const { items } = await res.json()
  return items.map((it: { sealedContent: { bytes: string } }) => {
    const sealed = fromB64url(it.sealedContent.bytes)
    return new TextDecoder().decode(
      v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)
    )
  })
}
