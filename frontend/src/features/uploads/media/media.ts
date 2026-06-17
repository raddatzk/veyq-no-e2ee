// uc.uploads (web) — ONE upload path, ONE download path for all bytes. Every file becomes a deduplicated
// "blob": a set of AEAD frames (v1.sealFrame, AAD binds index+total) in the server's BlobStore (never the
// DB). A small file is one frame; a large one is many — both resumable, both opened frame-by-frame on the
// way back. A "media" attaches a stored blob to a Memory/Chapter together with typed derivatives
// (thumbnail/preview), which are themselves ordinary blobs uploaded the same way. The server stays blind:
// it sees ciphertext frames, a per-scope dedup token, blob sizes and the mime type — but NOT capture time
// or GPS location, which are sealed per-media (sealedMeta) under the scope content key.
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import { b64url, fromB64url } from '../../../shared/base64'
import { mapPool } from '../../../shared/pool'

const EMPTY = new Uint8Array(0)

const auth = (s: UnlockedSession) => ({ authorization: `Bearer ${s.sessionToken}` })

// 4 MiB plaintext frames: small files stay one frame, large ones chunk transparently.
export const DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024

export type DerivativeKind = 'THUMBNAIL' | 'PREVIEW'
export type BlobRef = { blobId: string; totalChunks: number; byteSize: number }
export type BlobTicket = { status: 'READY' | 'UPLOADING'; blobId?: string; uploadId?: string; totalChunks: number }
export type UploadProgress = { uploadId: string; totalChunks: number; receivedChunks: number[]; status: 'OPEN' | 'COMPLETED' }
export type MediaItem = {
  id: string
  scopeId: string
  nodeId: string
  sealedMeta: { suiteId: string; bytes: string }
  mimeType: string
  original: BlobRef
  derivatives: { kind: DerivativeKind; blob: BlobRef }[]
  createdAt: string
}
/** Per-media sensitive metadata sealed under the content key: capture time + optional GPS location. */
export type MediaMeta = { takenAt: string; lat?: number; lng?: number }
export type NewMedia = { bytes: Uint8Array; thumb?: Uint8Array; preview?: Uint8Array; takenAt: string; mimeType: string; lat?: number; lng?: number }

/** Seal {takenAt, lat?, lng?} into a sealedMeta blob (nonce || AEAD) for the create request. */
export function sealMediaMeta(contentKey: Uint8Array, meta: MediaMeta): { suiteId: string; bytes: string } {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(contentKey, nonce, v1.utf8(JSON.stringify(meta)), EMPTY))
  return { suiteId: 'v1', bytes: b64url(sealed) }
}

/** Decrypt a media item's sealedMeta back to {takenAt, lat?, lng?}. */
export function decodeMediaMeta(contentKey: Uint8Array, item: MediaItem): MediaMeta {
  const sealed = fromB64url(item.sealedMeta.bytes)
  return JSON.parse(new TextDecoder().decode(v1.aeadOpen(contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)))
}

// ---- blob layer (the single upload/download path) -------------------------------------------------------

/** Split a plaintext into fixed-size chunks (always ≥1 chunk so an empty file still has one frame). */
export function chunkBytes(bytes: Uint8Array, chunkSize: number = DEFAULT_CHUNK_SIZE): Uint8Array[] {
  const out: Uint8Array[] = []
  for (let o = 0; o < bytes.length; o += chunkSize) out.push(bytes.slice(o, o + chunkSize))
  return out.length ? out : [new Uint8Array(0)]
}

/** The per-scope dedup token for some content (matches what the server keys on — a blind dedup token). */
export function dedupTokenFor(contentKey: Uint8Array, content: Uint8Array): string {
  return b64url(v1.dedupToken(contentKey, content))
}

/** Begin (or dedup) storing a blob. READY → already stored, attach blobId directly; UPLOADING → push frames. */
export async function startBlob(apiBase: string, session: UnlockedSession, scopeId: string, req: { dedupToken: string; totalChunks: number; byteSize: number }): Promise<BlobTicket> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blobs`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw await apiError(res, 'start blob')
  return res.json()
}

/** Seal one plaintext chunk into a frame and PUT it (idempotent — re-PUT is the basis of resume). */
export async function putChunk(apiBase: string, session: UnlockedSession, scopeId: string, uploadId: string, index: number, total: number, contentKey: Uint8Array, plain: Uint8Array): Promise<void> {
  const frame = v1.sealFrame(contentKey, index, total, plain, v1.randomBytes(12))
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blob-uploads/${uploadId}/chunks/${index}`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({ frame: { suiteId: 'v1', bytes: b64url(frame) } }),
  })
  if (!res.ok) throw await apiError(res, `put chunk ${index}`)
}

/** Which chunk indices have landed (drives resume). */
export async function getProgress(apiBase: string, session: UnlockedSession, scopeId: string, uploadId: string): Promise<UploadProgress> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blob-uploads/${uploadId}`, { headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'upload progress')
  return res.json()
}

/** Finalize the upload once every chunk has landed; promotes the frames to a content blob. */
export async function completeBlob(apiBase: string, session: UnlockedSession, scopeId: string, uploadId: string): Promise<BlobRef> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blob-uploads/${uploadId}/complete`, { method: 'POST', headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'complete blob')
  return res.json()
}

/**
 * Upload arbitrary content as a deduplicated blob and return its ref. Dedups first (skips the bytes entirely
 * if the scope already holds them); otherwise opens an upload and pushes only the still-missing frames, so a
 * retried call resumes a half-done upload rather than restarting it.
 */
export async function uploadBlob(apiBase: string, session: UnlockedSession, scopeId: string, contentKey: Uint8Array, bytes: Uint8Array, chunkSize: number = DEFAULT_CHUNK_SIZE): Promise<BlobRef> {
  const chunks = chunkBytes(bytes, chunkSize)
  const ticket = await startBlob(apiBase, session, scopeId, { dedupToken: dedupTokenFor(contentKey, bytes), totalChunks: chunks.length, byteSize: bytes.length })
  if (ticket.status === 'READY') return { blobId: ticket.blobId!, totalChunks: ticket.totalChunks, byteSize: bytes.length }
  const uploadId = ticket.uploadId!
  const have = new Set((await getProgress(apiBase, session, scopeId, uploadId)).receivedChunks)
  for (let i = 0; i < chunks.length; i++) {
    if (!have.has(i)) await putChunk(apiBase, session, scopeId, uploadId, i, chunks.length, contentKey, chunks[i])
  }
  return completeBlob(apiBase, session, scopeId, uploadId)
}

/** Download a finalized blob: pull each frame, open it (AAD checks position), concatenate the plaintext. */
export async function downloadBlob(apiBase: string, session: UnlockedSession, scopeId: string, contentKey: Uint8Array, ref: BlobRef): Promise<Uint8Array> {
  // Frames are independent and AAD-position-bound (openFrame checks index+total), so fetch them bound-parallel
  // and reassemble in order — a multi-chunk download is then bandwidth-bound, not totalChunks × round-trips.
  const indices = Array.from({ length: ref.totalChunks }, (_, i) => i)
  const parts = await mapPool(indices, 4, async (i) => {
    const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blobs/${ref.blobId}/chunks/${i}`, { headers: auth(session) })
    if (!res.ok) throw await apiError(res, `get chunk ${i}`)
    const frame = fromB64url((await res.json()).frame.bytes)
    return v1.openFrame(contentKey, i, ref.totalChunks, frame)
  })
  return v1.concat(...parts)
}

// ---- media layer (attach blobs to a Memory) -------------------------------------------------------------

/** Attach a stored blob (+ derivatives) to a Memory/Chapter node. The metadata is pre-sealed by the caller. */
export async function createMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, req: { originalBlobId: string; sealedMeta: { suiteId: string; bytes: string }; mimeType: string; derivatives?: { kind: DerivativeKind; blobId: string }[] }): Promise<MediaItem> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw await apiError(res, 'create media')
  return res.json()
}

/** Delete a single medium (and its derivatives) from a memory. */
export async function deleteMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, mediaId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media/${mediaId}`, { method: 'DELETE', headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'delete media')
}

/**
 * The full client flow: upload the original bytes (chunked + deduped transparently) and any client-generated
 * thumbnail through the SAME blob path, then attach them as a media. The thumbnail is a typed derivative —
 * no special API. Returns the created media (original + derivative blob refs).
 */
export async function uploadMedia(apiBase: string, session: UnlockedSession, scopeId: string, contentKey: Uint8Array, nodeId: string, m: NewMedia, chunkSize: number = DEFAULT_CHUNK_SIZE): Promise<MediaItem> {
  const original = await uploadBlob(apiBase, session, scopeId, contentKey, m.bytes, chunkSize)
  const derivatives: { kind: DerivativeKind; blobId: string }[] = []
  if (m.thumb) {
    const thumb = await uploadBlob(apiBase, session, scopeId, contentKey, m.thumb, chunkSize)
    derivatives.push({ kind: 'THUMBNAIL', blobId: thumb.blobId })
  }
  if (m.preview) {
    const preview = await uploadBlob(apiBase, session, scopeId, contentKey, m.preview, chunkSize)
    derivatives.push({ kind: 'PREVIEW', blobId: preview.blobId })
  }
  const sealedMeta = sealMediaMeta(contentKey, { takenAt: m.takenAt, lat: m.lat, lng: m.lng })
  return createMedia(apiBase, session, scopeId, nodeId, { originalBlobId: original.blobId, sealedMeta, mimeType: m.mimeType, derivatives })
}

/** List a Memory's media in upload order (the client sorts by decrypted takenAt for the gallery). */
export async function listMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media`, { headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'list media')
  return (await res.json()).media
}
