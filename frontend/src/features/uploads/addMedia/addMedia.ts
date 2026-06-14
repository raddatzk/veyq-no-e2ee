// uc.uploads.add-media (web) — get encrypted media into a Memory. The bytes and a light client-side
// thumbnail derivative are AEAD-sealed under the scope content key; the server stores ciphertext and only
// low-sensitivity metadata (node, mime, size, takenAt for ordering). Media live only in a Memory/Chapter.
import * as v1 from '../../../shared/crypto/v1'
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
const EMPTY = new Uint8Array(0)

export type Blob = { suiteId: string; bytes: string }
export type MediaItem = {
  id: string
  scopeId: string
  nodeId: string
  takenAt: string
  mimeType: string
  byteSize: number
  sealedBlob: Blob
  sealedThumb?: Blob
  createdAt: string
}
export type NewMedia = { bytes: Uint8Array; thumb?: Uint8Array; takenAt: string; mimeType: string }

const seal = (contentKey: Uint8Array, plain: Uint8Array): Blob => {
  const nonce = v1.randomBytes(12)
  return { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(contentKey, nonce, plain, EMPTY))) }
}

/** Seal a media's bytes (+ optional thumbnail) under the content key and attach it to a Memory/Chapter node. */
export async function addMedia(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string,
  contentKey: Uint8Array,
  nodeId: string,
  m: NewMedia,
): Promise<MediaItem> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({
      takenAt: m.takenAt,
      mimeType: m.mimeType,
      byteSize: m.bytes.length,
      sealedBlob: seal(contentKey, m.bytes),
      ...(m.thumb ? { sealedThumb: seal(contentKey, m.thumb) } : {}),
    }),
  })
  if (!res.ok) throw new Error(`add media failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** List a Memory's media, ordered by takenAt (gallery order). */
export async function listMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`list media failed: ${res.status} ${await res.text()}`)
  return (await res.json()).media
}

/** Decrypt a sealed media/thumbnail blob back to its bytes. */
export function decodeBlob(contentKey: Uint8Array, blob: Blob): Uint8Array {
  const sealed = fromB64url(blob.bytes)
  return v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)
}
