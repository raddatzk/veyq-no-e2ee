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
export type UploadProgress = { uploadId: string; totalChunks: number; receivedChunks: number[]; status: 'OPEN' | 'COMPLETED'; presignedPuts?: { index: number; url: string }[] }
export type MediaItem = {
  id: string
  scopeId: string
  nodeId: string
  sealedMeta: { suiteId: string; bytes: string }
  mimeType: string
  original: BlobRef
  derivatives: { kind: DerivativeKind; blob: BlobRef }[]
  highlighted: boolean
  createdBy?: string
  bucketId?: string | null
  createdAt: string
}
/** Per-media sensitive metadata sealed under the content key: capture time, optional GPS location, and a
 *  best-effort set of EXIF camera details (operator-blind — sealed with everything else). */
export type MediaMeta = {
  takenAt: string
  lat?: number
  lng?: number
  cameraMake?: string
  cameraModel?: string
  lens?: string
  width?: number
  height?: number
  fileSize?: number
  exif?: Record<string, string>
}
export type NewMedia = {
  bytes: Uint8Array
  thumb?: Uint8Array
  preview?: Uint8Array
  takenAt: string
  mimeType: string
  lat?: number
  lng?: number
  cameraMake?: string
  cameraModel?: string
  lens?: string
  width?: number
  height?: number
  fileSize?: number
  exif?: Record<string, string>
}

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

// Direct-to-store only (ADR 0021): every byte travels client↔object-store via short-lived presigned URLs; the
// backend handles only the control plane (start/finalize/presign), never the frames. The store is blind (AEAD
// ciphertext), so a presigned URL discloses nothing. There is no proxied fallback — the store MUST be able to
// presign (an external S3, or the bundled MinIO behind a reverse proxy); a misconfigured store fails loudly.

type BatchBlobStart = { status: 'READY' | 'UPLOADING' | 'ERROR'; blobId?: string; uploadId?: string; totalChunks?: number; receivedChunks?: number[]; presignedPuts?: { index: number; url: string }[]; errorCode?: string }
type BlobRefInput = { uploadId?: string; blobId?: string }
type FinalizeItem = { original: BlobRefInput; derivatives?: { kind: DerivativeKind; blob: BlobRefInput }[]; sealedMeta: { suiteId: string; bytes: string }; mimeType: string; bucketId?: string; contributorId?: string; contributorName?: { suiteId: string; bytes: string } }
type FinalizeResult = { ok: boolean; media?: MediaItem; errorCode?: string }
type PresignedChunks = { blobId: string; suiteId: string; chunks: { index: number; url: string }[] }

/** ONE round-trip to dedup/open (and presign) many blobs. Each item maps to a READY (dedup hit), an UPLOADING
 *  result with presigned PUT URLs for the missing chunks, or an ERROR. Pass an item's uploadId to resume. */
export async function batchStartBlobs(apiBase: string, session: UnlockedSession, scopeId: string, items: { dedupToken: string; totalChunks: number; byteSize: number; suiteId?: string; uploadId?: string }[]): Promise<BatchBlobStart[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blob-uploads-batch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({ items }),
  })
  if (!res.ok) throw await apiError(res, 'batch start blobs')
  return (await res.json()).results
}

/** ONE round-trip to finalize many items (complete each item's uploads + attach as media). Per-item best-effort. */
export async function batchFinalizeMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, items: FinalizeItem[]): Promise<FinalizeResult[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media-batch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({ items }),
  })
  if (!res.ok) throw await apiError(res, 'batch finalize media')
  return (await res.json()).results
}

/** ONE round-trip to presign GET URLs for many blobs (a whole gallery page); keyed by blobId. Inaccessible or
 *  missing blobs are simply absent from the map. */
export async function batchPresignGet(apiBase: string, session: UnlockedSession, scopeId: string, blobIds: string[]): Promise<Map<string, PresignedChunks>> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/blob-downloads-batch`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify({ blobIds }),
  })
  if (!res.ok) throw await apiError(res, 'batch presign get')
  return new Map(((await res.json()).results as PresignedChunks[]).map((r) => [r.blobId, r]))
}

/** Fetch one presigned blob: pull each frame DIRECTLY from the store, open it (AAD checks position), reassemble. */
async function fetchBlobFrames(contentKey: Uint8Array, ref: BlobRef, presigned: PresignedChunks): Promise<Uint8Array> {
  if (presigned.chunks.length !== ref.totalChunks) throw new Error(`direct-to-store download unavailable for ${ref.blobId}`)
  const urls = new Map(presigned.chunks.map((c) => [c.index, c.url]))
  const indices = Array.from({ length: ref.totalChunks }, (_, i) => i)
  const parts = await mapPool(indices, 4, async (i) => {
    const url = urls.get(i)
    if (!url) throw new Error(`missing presigned url for chunk ${i}`)
    const r = await fetch(url)
    if (!r.ok) throw new Error(`presigned get chunk ${i} failed: ${r.status}`)
    return v1.openFrame(contentKey, i, ref.totalChunks, new Uint8Array(await r.arrayBuffer()))
  })
  return v1.concat(...parts)
}

/** Download a finalized blob directly from the store (presign + parallel GET), then open + concatenate frames. */
export async function downloadBlob(apiBase: string, session: UnlockedSession, scopeId: string, contentKey: Uint8Array, ref: BlobRef): Promise<Uint8Array> {
  const presigned = (await batchPresignGet(apiBase, session, scopeId, [ref.blobId])).get(ref.blobId)
  if (!presigned) throw new Error(`blob ${ref.blobId} not available`)
  return fetchBlobFrames(contentKey, ref, presigned)
}

/** Download many blobs for a gallery page: ONE batch-presign-get, then every frame fetched direct + parallel. */
export async function downloadBlobsBatch(apiBase: string, session: UnlockedSession, scopeId: string, contentKey: Uint8Array, refs: BlobRef[]): Promise<Map<string, Uint8Array>> {
  const presigned = await batchPresignGet(apiBase, session, scopeId, refs.map((r) => r.blobId))
  const out = new Map<string, Uint8Array>()
  await mapPool(refs, 6, async (ref) => {
    const p = presigned.get(ref.blobId)
    if (!p) throw new Error(`blob ${ref.blobId} not available`)
    out.set(ref.blobId, await fetchBlobFrames(contentKey, ref, p))
  })
  return out
}

// ---- media layer (attach blobs to a Memory) -------------------------------------------------------------

/** Toggle a medium's highlight (favourite) and/or replace its re-sealed metadata (e.g. an edited capture date). */
export async function updateMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, mediaId: string, req: { highlighted?: boolean; sealedMeta?: { suiteId: string; bytes: string }; bucketId?: string }): Promise<MediaItem> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media/${mediaId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json', ...auth(session) },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw await apiError(res, 'update media')
  return res.json()
}

/** Delete a single medium (and its derivatives) from a memory. */
export async function deleteMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string, mediaId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media/${mediaId}`, { method: 'DELETE', headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'delete media')
}

/**
 * The full client flow for ONE photo, batched + direct-to-store: the original and any client-generated
 * thumbnail/preview are all blobs uploaded the SAME way. ONE batch-start dedups/opens every blob and presigns
 * its missing chunks; the client seals each frame and PUTs it DIRECTLY to the store, bound-parallel across all
 * three blobs; ONE batch-finalize completes the uploads + attaches the medium. Returns the created media.
 */
export async function uploadMedia(apiBase: string, session: UnlockedSession, scopeId: string, contentKey: Uint8Array, nodeId: string, m: NewMedia, chunkSize: number = DEFAULT_CHUNK_SIZE): Promise<MediaItem> {
  // The blob plan: original first, then optional derivatives — each split into frames.
  const plan: { kind: 'original' | DerivativeKind; chunks: Uint8Array[]; bytes: Uint8Array }[] = [{ kind: 'original', chunks: chunkBytes(m.bytes, chunkSize), bytes: m.bytes }]
  if (m.thumb) plan.push({ kind: 'THUMBNAIL', chunks: chunkBytes(m.thumb, chunkSize), bytes: m.thumb })
  if (m.preview) plan.push({ kind: 'PREVIEW', chunks: chunkBytes(m.preview, chunkSize), bytes: m.preview })

  // 1. ONE batch-start: dedup or open each blob, presigning every missing chunk (suite declared up front so a
  //    direct-to-store upload — whose raw frames carry no per-frame suite — can be finalized).
  const starts = await batchStartBlobs(apiBase, session, scopeId, plan.map((p) => ({ dedupToken: dedupTokenFor(contentKey, p.bytes), totalChunks: p.chunks.length, byteSize: p.bytes.length, suiteId: 'v1' })))

  // 2. Seal + PUT every still-missing frame DIRECTLY to the store, bound-parallel across all blobs.
  const puts: { url: string; frame: Uint8Array }[] = []
  starts.forEach((s, bi) => {
    if (s.status === 'ERROR') throw new Error(`upload start failed (${plan[bi].kind}): ${s.errorCode}`)
    if (s.status !== 'UPLOADING') return // READY → already stored, nothing to push
    const have = new Set(s.receivedChunks ?? [])
    const presigned = new Map((s.presignedPuts ?? []).map((p) => [p.index, p.url]))
    const chunks = plan[bi].chunks
    for (let i = 0; i < chunks.length; i++) {
      if (have.has(i)) continue
      const url = presigned.get(i)
      if (!url) throw new Error(`direct-to-store unavailable: the server returned no upload URL for chunk ${i}`)
      puts.push({ url, frame: v1.sealFrame(contentKey, i, chunks.length, chunks[i], v1.randomBytes(12)) })
    }
  })
  await mapPool(puts, 6, async (p) => {
    const res = await fetch(p.url, { method: 'PUT', body: p.frame as BodyInit })
    if (!res.ok) throw new Error(`presigned put failed: ${res.status}`)
  })

  // 3. ONE batch-finalize: complete each blob's upload + attach the medium with its derivatives.
  const refFor = (s: BatchBlobStart): BlobRefInput => (s.status === 'READY' ? { blobId: s.blobId! } : { uploadId: s.uploadId! })
  const derivatives = plan.slice(1).map((p, i) => ({ kind: p.kind as DerivativeKind, blob: refFor(starts[i + 1]) }))
  const sealedMeta = sealMediaMeta(contentKey, {
    takenAt: m.takenAt, lat: m.lat, lng: m.lng,
    cameraMake: m.cameraMake, cameraModel: m.cameraModel, lens: m.lens,
    width: m.width, height: m.height, fileSize: m.fileSize, exif: m.exif,
  })
  const results = await batchFinalizeMedia(apiBase, session, scopeId, nodeId, [{ original: refFor(starts[0]), derivatives, sealedMeta, mimeType: m.mimeType }])
  const r = results[0]
  if (!r?.ok || !r.media) throw new Error(`media finalize failed: ${r?.errorCode ?? 'unknown'}`)
  return r.media
}

/** List a Memory's media in upload order (the client sorts by decrypted takenAt for the gallery). */
export async function listMedia(apiBase: string, session: UnlockedSession, scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const res = await fetch(`${apiBase}/api/scopes/${scopeId}/nodes/${nodeId}/media`, { headers: auth(session) })
  if (!res.ok) throw await apiError(res, 'list media')
  return (await res.json()).media
}
