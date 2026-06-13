// uc.spaces / content (web) — the first real use of a scope: write and read encrypted content under it.
// AD-03: a stable per-scope content key (wrapped to the scope public key) seals items; the server only
// ever stores ciphertext. Targets the personal scope here, but the ops are scope-generic.
import * as v1 from '../../shared/crypto/v1'
import type { UnlockedSession } from '../account/login/login'

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) => c.charCodeAt(0))
}
const EMPTY = new Uint8Array(0)

export type OpenScope = { scopeId: string; scopePub: { x: Uint8Array; m: Uint8Array }; scopePriv: v1.Identity['priv'] }

const auth = (token: string) => ({ authorization: `Bearer ${token}` })

/** Fetch + open the caller's personal scope: unwrap its private key with the user's identity. */
export async function openPersonalScope(apiBase: string, session: UnlockedSession): Promise<OpenScope> {
  const res = await fetch(`${apiBase}/api/me/personal-scope`, { headers: auth(session.sessionToken) })
  if (!res.ok) throw new Error(`personal-scope failed: ${res.status} ${await res.text()}`)
  const g = await res.json()
  const idKem = fromB64url(session.identityPublicKey.kemPublicKey)
  const scopePrivBytes = v1.asymUnwrap(
    v1.hybridSecret(session.identity),
    { x: idKem.slice(0, 32), m: idKem.slice(32) },
    fromB64url(g.wrappedScopePrivateKey.bytes),
    'personal-scope-grant',
  )
  const scopeKem = fromB64url(g.scopePublicKey.kemPublicKey)
  return { scopeId: g.scopeId, scopePub: { x: scopeKem.slice(0, 32), m: scopeKem.slice(32) }, scopePriv: v1.deserializeIdentityPrivate(scopePrivBytes) }
}

/** Get the scope's content key, provisioning one (wrapped to the scope) on first use. */
export async function getOrCreateContentKey(apiBase: string, session: UnlockedSession, scope: OpenScope): Promise<Uint8Array> {
  const url = `${apiBase}/api/scopes/${scope.scopeId}/content-key`
  const got = await fetch(url, { headers: auth(session.sessionToken) })
  if (got.ok) {
    const { wrappedContentKey } = await got.json()
    return v1.asymUnwrap(v1.hybridSecret(scope.scopePriv), scope.scopePub, fromB64url(wrappedContentKey.bytes), 'content-key')
  }
  if (got.status !== 404) throw new Error(`content-key fetch failed: ${got.status}`)

  const ck = v1.randomBytes(32)
  const wrapped = v1.asymWrap(scope.scopePub, ck, 'content-key', { nonce: v1.randomBytes(12) })
  const put = await fetch(url, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({ wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrapped) } }),
  })
  if (put.status === 409) return getOrCreateContentKey(apiBase, session, scope) // raced — adopt the existing key
  if (!put.ok) throw new Error(`content-key provision failed: ${put.status} ${await put.text()}`)
  return ck
}

/** Seal a string under the content key and store it; returns the item id. */
export async function writeItem(apiBase: string, session: UnlockedSession, scope: OpenScope, contentKey: Uint8Array, plaintext: string): Promise<string> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(plaintext), EMPTY))
  const res = await fetch(`${apiBase}/api/scopes/${scope.scopeId}/items`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session.sessionToken) },
    body: JSON.stringify({ sealedContent: { suiteId: 'v1', bytes: b64url(sealed) } }),
  })
  if (!res.ok) throw new Error(`item create failed: ${res.status} ${await res.text()}`)
  return (await res.json()).id
}

/** List the scope's items and decrypt each back to its plaintext string. */
export async function readItems(apiBase: string, session: UnlockedSession, scope: OpenScope, contentKey: Uint8Array): Promise<string[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scope.scopeId}/items`, { headers: auth(session.sessionToken) })
  if (!res.ok) throw new Error(`item list failed: ${res.status}`)
  const { items } = await res.json()
  return items.map((it: { sealedContent: { bytes: string } }) => {
    const sealed = fromB64url(it.sealedContent.bytes)
    return new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
  })
}
