// uc.memory.journal (web) — a Memory's journal is a Markdown body sealed under the scope content key
// (nonce || AEAD), the same envelope used for node names. The server stores and returns ciphertext only;
// we seal before PUT and open after GET. Whole-document replace (no live collaboration in the greenfield).
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'

const EMPTY = new Uint8Array(0)

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromB64url(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(b.length)
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i)
  return out
}

export type Journal = { markdown: string; updatedAt: string | null }

/** Load and decrypt a memory's journal Markdown ('' when none has been written yet). */
export async function getJournal(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<Journal> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/journal`, {
    headers: { authorization: `Bearer ${session.sessionToken}` },
  })
  if (!res.ok) throw new Error(`get journal failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  if (!body.sealedContent) return { markdown: '', updatedAt: null }
  const sealed = fromB64url(body.sealedContent.bytes)
  const markdown = new TextDecoder().decode(v1.aeadOpen(active.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
  return { markdown, updatedAt: body.updatedAt ?? null }
}

/** Seal and store a memory's journal Markdown (whole-document replace). */
export async function updateJournal(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, markdown: string): Promise<Journal> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(active.contentKey, nonce, v1.utf8(markdown), EMPTY))
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/journal`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ sealedContent: { suiteId: 'v1', bytes: b64url(sealed) } }),
  })
  if (!res.ok) throw new Error(`update journal failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  return { markdown, updatedAt: body.updatedAt ?? null }
}
