// en.memory.media (web) — the encrypted media-blob data layer (cr-0028). Operator-blind, direct-to-store:
// a blob is sealed client-side as chunked AEAD under a fresh random K_blob (wrapped under the Memory's K_mem,
// ADR 0012), the ciphertext chunks travel straight to the object store via short-TTL presigned URLs
// (ADR 0013) — never through the backend — and the backend records only opaque metadata + the wrap + the
// media→node link. The control plane is batch-shaped; this slice sends length-1 lists. The caller opens the
// Memory first (features/memory/tree.openMemory → K_mem) and passes K_mem in.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { mediaApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import type { MediaSummary, PresignedUrl } from '../../api/generated/media'

const KBLOB_CTX = 'blob-key'

/** Plaintext bytes per chunk before sealing (each frame is this + a 12-byte nonce + a 16-byte tag). */
export const CHUNK_SIZE = 256 * 1024

/** A node's media for the UI/read-back: the blob id + the metadata needed to open it. */
export type MediaItem = {
  mediaId: string
  blobId: string
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
}

function summaryToItem(s: MediaSummary): MediaItem {
  return { mediaId: s.mediaId, blobId: s.blobId, chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey }
}

/** Split plaintext into ≥1 chunks of at most CHUNK_SIZE bytes (a 0-byte blob is a single empty chunk). */
function chunkify(data: Uint8Array): Uint8Array[] {
  if (data.length === 0) return [new Uint8Array(0)]
  const out: Uint8Array[] = []
  for (let off = 0; off < data.length; off += CHUNK_SIZE) out.push(data.subarray(off, off + CHUNK_SIZE))
  return out
}

/** Order presigned URLs by their declared chunk index (the server returns them in order, but don't assume). */
function byIndex(urls: PresignedUrl[]): string[] {
  return [...urls].sort((a, b) => a.index - b.index).map((u) => u.url)
}

/**
 * Seal `data` as a media blob under a fresh random `K_blob` (wrapped under `K_mem`), upload the ciphertext
 * chunks direct-to-store, and attach the result to `nodeId` (a MEMORY/CHAPTER). Returns the new ids.
 */
export async function addMedia(
  scopeId: string, nodeId: string, memoryKey: Uint8Array, data: Uint8Array,
): Promise<{ blobId: string; mediaId: string }> {
  const blobKey = v1.randomBytes(32)
  const wrappedBlobKey = b64std(v1.symWrap(memoryKey, blobKey, KBLOB_CTX, v1.randomBytes(12)))

  const plainChunks = chunkify(data)
  const total = plainChunks.length
  const frames = plainChunks.map((chunk, i) => v1.sealFrame(blobKey, i, total, chunk, v1.randomBytes(12)))
  const byteSize = frames.reduce((n, f) => n + f.length, 0)

  // start — reserve the upload session + presign one PUT per chunk
  const started = await withRefresh(() =>
    mediaApi().startBlobUploads({
      scopeId,
      blobUploadsBatchRequest: { items: [{ chunkCount: total, byteSize, wrappedBlobKey }] },
    }),
  )
  const up = started.results[0]
  if (!up || up.error) throw new Error(up?.error ?? 'upload_failed')
  if (!up.uploadId || !up.presignedPuts) throw new Error('store_unavailable')

  // PUT each sealed frame straight to the store (no base64, no JSON, no credentials)
  const putUrls = byIndex(up.presignedPuts)
  await Promise.all(putUrls.map((url, i) => putChunk(url, frames[i])))

  // complete — the backend stats the objects (declare-then-verify) and attaches the media
  const done = await withRefresh(() =>
    mediaApi().completeMedia({ scopeId, mediaBatchRequest: { items: [{ uploadId: up.uploadId!, nodeId }] } }),
  )
  const m = done.results[0]
  if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
  return { blobId: m.blobId, mediaId: m.mediaId }
}

/** List a node's media (opaque metadata + the K_blob wrap). */
export async function listMedia(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const rows = await withRefresh(() => mediaApi().listNodeMedia({ scopeId, nodeId }))
  return rows.map(summaryToItem)
}

/**
 * Open a media blob: unwrap `K_blob` under `K_mem`, presign the chunk GETs, fetch the ciphertext frames
 * direct-from-store, and `openFrame` them back to the original plaintext (the AAD position check still applies).
 */
export async function openMedia(scopeId: string, memoryKey: Uint8Array, media: MediaItem): Promise<Uint8Array> {
  const blobKey = v1.symUnwrap(memoryKey, fromB64std(media.wrappedBlobKey), KBLOB_CTX)

  const presigned = await withRefresh(() =>
    mediaApi().presignBlobDownloads({ scopeId, blobDownloadsBatchRequest: { items: [{ blobId: media.blobId }] } }),
  )
  const d = presigned.results[0]
  if (!d || d.error || !d.presignedGets) throw new Error(d?.error ?? 'store_unavailable')

  const getUrls = byIndex(d.presignedGets)
  const frames = await Promise.all(getUrls.map((url) => getChunk(url)))
  const chunks = frames.map((frame, i) => v1.openFrame(blobKey, i, frames.length, frame))
  return v1.concat(...chunks)
}

async function putChunk(url: string, frame: Uint8Array): Promise<void> {
  const res = await fetch(url, { method: 'PUT', body: frame as BodyInit })
  if (!res.ok) throw new Error(`chunk_put_failed:${res.status}`)
}

async function getChunk(url: string): Promise<Uint8Array> {
  const res = await fetch(url, { method: 'GET' })
  if (!res.ok) throw new Error(`chunk_get_failed:${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}
