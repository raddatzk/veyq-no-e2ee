// uc.uploads.resumable-upload (web) — chunked, resumable media upload. A large media is split into
// independent AEAD frames (v1.sealFrame, AAD binds index+total); the ciphertext frames go to the server's
// BlobStore (never the DB). The session survives interruptions: ask which chunks landed and re-send only
// the missing ones. Each frame is self-contained — download decrypts frame by frame and concatenates.
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

export type UploadSession = {
  uploadId: string
  scopeId: string
  nodeId: string
  totalChunks: number
  mimeType: string
  byteSize: number
  takenAt: string
  status: 'OPEN' | 'COMPLETED'
  createdAt: string
}
export type UploadProgress = { uploadId: string; totalChunks: number; receivedChunks: number[]; status: 'OPEN' | 'COMPLETED' }

const auth = (s: UnlockedSession) => ({ authorization: `Bearer ${s.sessionToken}` })

/** Split a blob into fixed-size plaintext chunks. */
export function chunkBytes(bytes: Uint8Array, chunkSize: number): Uint8Array[] {
  const out: Uint8Array[] = []
  for (let o = 0; o < bytes.length; o += chunkSize) out.push(bytes.slice(o, o + chunkSize))
  return out.length ? out : [new Uint8Array(0)]
}

/** Open a resumable upload session for a chunked media on a Memory/Chapter node. */
export async function createUpload(
  apiBase: string,
  session: UnlockedSession,
  scopeId: string,
  nodeId: string,
  meta: { totalChunks: number; mimeType: string; byteSize: number; takenAt: string },
): Promise<UploadSession> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/uploads`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify(meta),
  })
  if (!res.ok) throw new Error(`create upload failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** Seal one plaintext chunk into a frame and PUT it (idempotent — safe to retry/resume). */
export async function putChunk(apiBase: string, session: UnlockedSession, uploadId: string, index: number, total: number, contentKey: Uint8Array, plain: Uint8Array): Promise<void> {
  const frame = v1.sealFrame(contentKey, index, total, plain, v1.randomBytes(12))
  const res = await fetch(`${apiBase}/api/uploads/${uploadId}/chunks/${index}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({ frame: { suiteId: 'v1', bytes: b64url(frame) } }),
  })
  if (!res.ok) throw new Error(`put chunk ${index} failed: ${res.status} ${await res.text()}`)
}

/** Which chunk indices have landed (drives resume). */
export async function getProgress(apiBase: string, session: UnlockedSession, uploadId: string): Promise<UploadProgress> {
  const res = await fetch(`${apiBase}/api/uploads/${uploadId}`, { headers: auth(session) })
  if (!res.ok) throw new Error(`upload progress failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** Finalize the upload once every chunk has landed. */
export async function completeUpload(apiBase: string, session: UnlockedSession, uploadId: string): Promise<UploadSession> {
  const res = await fetch(`${apiBase}/api/uploads/${uploadId}/complete`, { method: 'POST', headers: auth(session) })
  if (!res.ok) throw new Error(`complete upload failed: ${res.status} ${await res.text()}`)
  return res.json()
}

/** Resume: send only the chunks the server is still missing, then finalize. Idempotent end to end. */
export async function resumeUpload(apiBase: string, session: UnlockedSession, uploadId: string, contentKey: Uint8Array, chunks: Uint8Array[]): Promise<UploadSession> {
  const have = new Set((await getProgress(apiBase, session, uploadId)).receivedChunks)
  for (let i = 0; i < chunks.length; i++) {
    if (!have.has(i)) await putChunk(apiBase, session, uploadId, i, chunks.length, contentKey, chunks[i])
  }
  return completeUpload(apiBase, session, uploadId)
}

/** Download a finalized media: pull each frame, open it (AAD checks position), concatenate the plaintext. */
export async function downloadResumable(apiBase: string, session: UnlockedSession, uploadId: string, contentKey: Uint8Array, totalChunks: number): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  for (let i = 0; i < totalChunks; i++) {
    const res = await fetch(`${apiBase}/api/uploads/${uploadId}/chunks/${i}`, { headers: auth(session) })
    if (!res.ok) throw new Error(`get chunk ${i} failed: ${res.status} ${await res.text()}`)
    const frame = fromB64url((await res.json()).frame.bytes)
    parts.push(v1.openFrame(contentKey, i, totalChunks, frame))
  }
  return v1.concat(...parts)
}
