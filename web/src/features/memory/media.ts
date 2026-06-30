// en.memory.media + en.memory.derivatives (web) — the encrypted media-blob data layer (cr-0028, cr-0045).
// Operator-blind, direct-to-store: a blob is sealed client-side as chunked AEAD under a fresh random K_blob
// (wrapped under the Memory's K_mem, ADR 0012), the ciphertext chunks travel straight to the object store via
// short-TTL presigned URLs (ADR 0013) — never through the backend — and the backend records only opaque
// metadata + the wrap + the media→node link.
//
// On add-media (cr-0045) the client also generates a **thumbnail** and a **preview** derivative (image: two
// downscales; video: a first-frame poster used for both), seals **each as its own K_blob blob**, and uploads
// the original + both derivatives in **one batch**. A media then carries them role-tagged, so a gallery fetches
// grid = thumbnail / lightbox = preview / download = original without ever pulling the full original for a grid.
// The pixel stage (decode → downscale → JPEG re-encode) is hidden behind an injectable MediaDeriver so the
// wiring is unit-testable; the real Canvas/<video> decode is exercised by a live browser round-trip.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { mediaApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import type { MediaSummary, PresignedUrl, MediaDerivativeKind } from '../../api/generated/media'

const KBLOB_CTX = 'blob-key'
/** AAD context binding the sealed `{w,h}` dimensions to their purpose (cr-0059, separate from the K_blob wrap). */
const MEDIA_DIMS_CTX = 'media-dims'

/** Plaintext bytes per chunk before sealing (each frame is this + a 12-byte nonce + a 16-byte tag). */
export const CHUNK_SIZE = 256 * 1024

/** Derivative tier specs, pinned from the legacy E2EE client (en.memory.derivatives → *The three tiers*). */
export const TIER_SPECS: Record<MediaDerivativeKind, { maxEdge: number; quality: number }> = {
  THUMBNAIL: { maxEdge: 400, quality: 0.8 }, // gallery grid
  PREVIEW: { maxEdge: 2048, quality: 0.85 }, // lightbox
}

// --- read-back model -------------------------------------------------------

/** Everything needed to open one blob: its id + the metadata + the K_blob wrap (under K_mem). */
export type BlobRef = {
  blobId: string
  chunkCount: number
  byteSize: number
  wrappedBlobKey: string
}

/** One role-tagged derivative tier of a media. */
export type MediaDerivative = { kind: MediaDerivativeKind; ref: BlobRef }

/** A node's media for the UI/read-back: the original blob ref + its role-tagged derivative tiers. */
/** A media's pristine pixel dimensions, used to lay out the justified gallery grid (cr-0059). */
export type Dimensions = { w: number; h: number }

export type MediaItem = {
  mediaId: string
  original: BlobRef
  derivatives: MediaDerivative[]
  /** The original's `{w,h}` sealed under `K_mem` (opaque base64); unwrap with [unsealDimensions]. Absent when none. */
  wrappedDimensions?: string
}

function summaryToItem(s: MediaSummary): MediaItem {
  return {
    mediaId: s.mediaId,
    original: { blobId: s.blobId, chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey },
    derivatives: (s.derivatives ?? []).map((d) => ({
      kind: d.kind,
      ref: { blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey },
    })),
    wrappedDimensions: s.wrappedDimensions ?? undefined,
  }
}

/**
 * Unwrap a media's sealed dimensions under `K_mem` (operator-blind — the server only ever holds the ciphertext).
 * Best-effort: returns null when none were sealed, the wrap doesn't open, or the payload isn't sane `{w,h}` — the
 * gallery then falls back to a default aspect rather than break the layout.
 */
export function unsealDimensions(memoryKey: Uint8Array, item: MediaItem): Dimensions | null {
  if (!item.wrappedDimensions) return null
  try {
    const json = new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(item.wrappedDimensions), MEDIA_DIMS_CTX))
    const { w, h } = JSON.parse(json) as Dimensions
    if (Number.isFinite(w) && Number.isFinite(h) && w > 0 && h > 0) return { w, h }
  } catch {
    /* not openable / malformed → fall back to a default aspect */
  }
  return null
}

// --- the pixel stage (injectable; jsdom cannot decode real Canvas/Image/Video) ---------------------------

/** The source to attach: the pristine original bytes + their MIME type (decides image/video/other). */
export type AddMediaSource = { bytes: Uint8Array; mediaType: string }

/**
 * The platform-native pixel stage behind an interface: the real one uses Canvas + `<video>`; tests inject a
 * stub. A derivative need only be a valid JPEG at the target longest edge — parity here is by **property**, not
 * bytes (en.memory.derivatives → *Quality notes*).
 */
export interface MediaDeriver {
  /** Downscale an image Blob to a JPEG with longest edge ≤ `maxEdge` at `quality`. */
  deriveImage(source: Blob, opts: { maxEdge: number; quality: number }): Promise<Uint8Array>
  /** Decode a video's first frame to a JPEG poster (used for both the thumbnail and the preview tier). */
  deriveVideoPoster(source: Blob): Promise<Uint8Array>
  /** Probe the source's intrinsic pixel `{w,h}` for the justified grid (cr-0059). Optional + best-effort. */
  probeDimensions?(source: Blob, mediaType: string): Promise<Dimensions | null>
}

/**
 * Longest-edge target math (pure): scale `width`×`height` so the longest edge is ≤ `maxEdge`, preserving the
 * aspect ratio and **never upscaling**. Each side is at least 1px.
 */
export function scaleToMaxEdge(width: number, height: number, maxEdge: number): { width: number; height: number } {
  const longest = Math.max(width, height)
  if (longest <= maxEdge || longest === 0) return { width, height }
  const scale = maxEdge / longest
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

/** The real browser deriver — Canvas decode/downscale + `<video>` first-frame poster. DOM-only (used live). */
export function createBrowserDeriver(): MediaDeriver {
  return {
    async deriveImage(source, { maxEdge, quality }) {
      const bitmap = await createImageBitmap(source)
      try {
        return drawToJpeg(bitmap, bitmap.width, bitmap.height, maxEdge, quality)
      } finally {
        bitmap.close()
      }
    },
    async deriveVideoPoster(source) {
      const url = URL.createObjectURL(source)
      try {
        const video = document.createElement('video')
        video.muted = true
        video.preload = 'auto'
        video.src = url
        await onceEvent(video, 'loadeddata') // first frame decoded
        if (video.currentTime !== 0) {
          video.currentTime = 0
          await onceEvent(video, 'seeked')
        }
        // the poster is encoded at the preview tier and reused for the (smaller) thumbnail tier as-is
        return drawToJpeg(video, video.videoWidth, video.videoHeight, TIER_SPECS.PREVIEW.maxEdge, TIER_SPECS.PREVIEW.quality)
      } finally {
        URL.revokeObjectURL(url)
      }
    },
    async probeDimensions(source, mediaType) {
      if (mediaType.startsWith('image/')) {
        const bitmap = await createImageBitmap(source)
        try {
          return { w: bitmap.width, h: bitmap.height }
        } finally {
          bitmap.close()
        }
      }
      if (mediaType.startsWith('video/')) {
        const url = URL.createObjectURL(source)
        try {
          const video = document.createElement('video')
          video.muted = true
          video.preload = 'metadata'
          video.src = url
          await onceEvent(video, 'loadeddata')
          return { w: video.videoWidth, h: video.videoHeight }
        } finally {
          URL.revokeObjectURL(url)
        }
      }
      return null
    },
  }
}

async function drawToJpeg(src: CanvasImageSource, w: number, h: number, maxEdge: number, quality: number): Promise<Uint8Array> {
  const { width, height } = scaleToMaxEdge(w, h, maxEdge)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas_unavailable')
  ctx.drawImage(src, 0, 0, width, height)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
  if (!blob) throw new Error('encode_failed')
  return new Uint8Array(await blob.arrayBuffer())
}

function onceEvent(el: HTMLMediaElement, ev: 'loadeddata' | 'seeked'): Promise<void> {
  return new Promise((resolve, reject) => {
    const ok = () => { cleanup(); resolve() }
    const bad = () => { cleanup(); reject(new Error(`video_${ev}_failed`)) }
    const cleanup = () => { el.removeEventListener(ev, ok); el.removeEventListener('error', bad) }
    el.addEventListener(ev, ok, { once: true })
    el.addEventListener('error', bad, { once: true })
  })
}

/** Generate the role-tagged derivatives for a source (image: two downscales; video: one poster for both). */
async function deriveTiers(source: AddMediaSource, deriver: MediaDeriver): Promise<{ kind: MediaDerivativeKind; bytes: Uint8Array }[]> {
  const blob = new Blob([source.bytes as BlobPart], { type: source.mediaType })
  // Best-effort: a codec the browser can't decode (e.g. HEIC off Safari), an empty MIME type, or corrupt bytes
  // must NOT fail the upload — fall back to original-only (the gallery shows an icon, uc.memory.upload E4).
  try {
    if (source.mediaType.startsWith('image/')) {
      const thumbnail = await deriver.deriveImage(blob, TIER_SPECS.THUMBNAIL)
      const preview = await deriver.deriveImage(blob, TIER_SPECS.PREVIEW)
      return [{ kind: 'THUMBNAIL', bytes: thumbnail }, { kind: 'PREVIEW', bytes: preview }]
    }
    if (source.mediaType.startsWith('video/')) {
      const poster = await deriver.deriveVideoPoster(blob) // one poster serves both tiers
      return [{ kind: 'THUMBNAIL', bytes: poster }, { kind: 'PREVIEW', bytes: poster }]
    }
  } catch {
    /* underivable → original only */
  }
  return [] // no derivable frame → original only (the gallery falls back to an icon)
}

// --- add-media -------------------------------------------------------------

/** A blob sealed and ready to upload: the wrap + the ciphertext frames + the declared shape. */
type SealedBlob = { wrappedBlobKey: string; frames: Uint8Array[]; chunkCount: number; byteSize: number }

/** Seal `data` as a media blob under a fresh random `K_blob` (wrapped under `K_mem`). */
function sealBlob(memoryKey: Uint8Array, data: Uint8Array): SealedBlob {
  const blobKey = v1.randomBytes(32)
  const wrappedBlobKey = b64std(v1.symWrap(memoryKey, blobKey, KBLOB_CTX, v1.randomBytes(12)))
  const plainChunks = chunkify(data)
  const total = plainChunks.length
  const frames = plainChunks.map((chunk, i) => v1.sealFrame(blobKey, i, total, chunk, v1.randomBytes(12)))
  const byteSize = frames.reduce((n, f) => n + f.length, 0)
  return { wrappedBlobKey, frames, chunkCount: total, byteSize }
}

/** Seal `{w,h}` under `K_mem` (a small AEAD payload, like a wrapped key) → opaque base64 for `wrappedDimensions`. */
function sealDimensions(memoryKey: Uint8Array, dims: Dimensions): string {
  const bytes = new TextEncoder().encode(JSON.stringify({ w: dims.w, h: dims.h }))
  return b64std(v1.symWrap(memoryKey, bytes, MEDIA_DIMS_CTX, v1.randomBytes(12)))
}

/** Probe the original's `{w,h}` and seal it (best-effort — undecodable/unsupported → null, the grid uses a default). */
async function sealOriginalDimensions(source: AddMediaSource, deriver: MediaDeriver, memoryKey: Uint8Array): Promise<string | null> {
  if (!deriver.probeDimensions) return null
  try {
    const blob = new Blob([source.bytes as BlobPart], { type: source.mediaType })
    const dims = await deriver.probeDimensions(blob, source.mediaType)
    return dims ? sealDimensions(memoryKey, dims) : null
  } catch {
    return null
  }
}

/** Upload progress (0..1) + cancellation hooks for [addMedia], used by the upload queue (uc.memory.upload). */
export type AddMediaOptions = { onProgress?: (fraction: number) => void; signal?: AbortSignal }

/** Throw an AbortError if the caller cancelled — the queue maps it to a cancelled (not failed) upload. */
function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
}

/**
 * Seal the original + its derivatives, each under its **own** `K_blob`, upload all ciphertext chunks
 * direct-to-store in one batch, and attach the result to `nodeId` (a MEMORY/CHAPTER) as one role-tagged media.
 * Returns the new ids (the original's `blobId` + the `mediaId`). `deriver` is injectable for tests; `opts`
 * carries an `onProgress` (0..1, over the ciphertext chunks) + an `AbortSignal` for the upload queue.
 */
export async function addMedia(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  const { onProgress, signal } = opts
  throwIfAborted(signal)
  const tiers = await deriveTiers(source, deriver)
  const wrappedDimensions = await sealOriginalDimensions(source, deriver, memoryKey)
  // index 0 = original; the rest are the role-tagged derivatives, in order
  const sealedOriginal = sealBlob(memoryKey, source.bytes)
  const sealedTiers = tiers.map((t) => ({ kind: t.kind, sealed: sealBlob(memoryKey, t.bytes) }))
  const sealedAll = [sealedOriginal, ...sealedTiers.map((t) => t.sealed)]

  // start — reserve one upload session + presign one PUT per chunk, per blob
  throwIfAborted(signal)
  const started = await withRefresh(() =>
    mediaApi().startBlobUploads({
      scopeId,
      blobUploadsBatchRequest: {
        items: sealedAll.map((s) => ({ chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey })),
      },
    }),
  )
  if (started.results.length !== sealedAll.length) throw new Error('upload_failed')
  const uploads = started.results.map((r, i) => {
    if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
    if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
    return { uploadId: r.uploadId, putUrls: byIndex(r.presignedPuts), frames: sealedAll[i].frames }
  })

  // PUT every chunk of every blob straight to the store (no base64, no JSON, no credentials), reporting
  // progress as chunks land and passing the abort signal so a cancel stops the in-flight PUTs.
  const totalChunks = sealedAll.reduce((n, s) => n + s.chunkCount, 0)
  let doneChunks = 0
  onProgress?.(0)
  await Promise.all(
    uploads.flatMap((u) =>
      u.putUrls.map((url, i) =>
        putChunk(url, u.frames[i], signal).then(() => {
          doneChunks += 1
          onProgress?.(totalChunks === 0 ? 1 : doneChunks / totalChunks)
        }),
      ),
    ),
  )

  // complete — one logical media: the original + its role-tagged derivative uploads
  throwIfAborted(signal)
  const done = await withRefresh(() =>
    mediaApi().completeMedia({
      scopeId,
      mediaBatchRequest: {
        items: [
          {
            nodeId,
            originalUploadId: uploads[0].uploadId,
            derivatives: sealedTiers.map((t, i) => ({ kind: t.kind, uploadId: uploads[i + 1].uploadId })),
            wrappedDimensions,
          },
        ],
      },
    }),
  )
  const m = done.results[0]
  if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
  return { blobId: m.blobId, mediaId: m.mediaId }
}

// --- read-back -------------------------------------------------------------

/** List a node's media (opaque metadata + the K_blob wraps for the original + every derivative tier). */
export async function listMedia(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const rows = await withRefresh(() => mediaApi().listNodeMedia({ scopeId, nodeId }))
  return rows.map(summaryToItem)
}

function tierRef(item: MediaItem, kind: MediaDerivativeKind): BlobRef | undefined {
  return item.derivatives.find((d) => d.kind === kind)?.ref
}

/**
 * Whether a media carries derivative tiers — i.e. it had a derivable frame (an image or video, cr-0045). A
 * gallery renders these as a thumbnail/preview; one **without** derivatives is a non-image/video (E1) shown as
 * an icon tile. (No media type is stored — operator-blind — so presence of a tier is the only signal.)
 */
export function hasDerivatives(item: MediaItem): boolean {
  return item.derivatives.length > 0
}

/** Grid tier (small): the thumbnail derivative, or the original when a media has no derivatives. */
export function gridRef(item: MediaItem): BlobRef {
  return tierRef(item, 'THUMBNAIL') ?? item.original
}

/** Lightbox tier (medium): the preview derivative, or the original when a media has no derivatives. */
export function lightboxRef(item: MediaItem): BlobRef {
  return tierRef(item, 'PREVIEW') ?? item.original
}

/** Download tier: always the pristine original. */
export function downloadRef(item: MediaItem): BlobRef {
  return item.original
}

/**
 * Open a blob: unwrap its `K_blob` under `K_mem`, presign the chunk GETs, fetch the ciphertext frames
 * direct-from-store, and `openFrame` them back to plaintext (the AAD position check still applies).
 */
export async function openBlob(scopeId: string, memoryKey: Uint8Array, ref: BlobRef): Promise<Uint8Array> {
  const blobKey = v1.symUnwrap(memoryKey, fromB64std(ref.wrappedBlobKey), KBLOB_CTX)

  const presigned = await withRefresh(() =>
    mediaApi().presignBlobDownloads({ scopeId, blobDownloadsBatchRequest: { items: [{ blobId: ref.blobId }] } }),
  )
  const d = presigned.results[0]
  if (!d || d.error || !d.presignedGets) throw new Error(d?.error ?? 'store_unavailable')

  const getUrls = byIndex(d.presignedGets)
  const frames = await Promise.all(getUrls.map((url) => getChunk(url)))
  const chunks = frames.map((frame, i) => v1.openFrame(blobKey, i, frames.length, frame))
  return v1.concat(...chunks)
}

/** Open the grid tier (thumbnail) of a media. */
export const openGrid = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) => openBlob(scopeId, memoryKey, gridRef(item))
/** Open the lightbox tier (preview) of a media. */
export const openLightbox = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) => openBlob(scopeId, memoryKey, lightboxRef(item))
/** Open the pristine original of a media (download). */
export const openOriginal = (scopeId: string, memoryKey: Uint8Array, item: MediaItem) => openBlob(scopeId, memoryKey, downloadRef(item))

// --- transport helpers -----------------------------------------------------

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

async function putChunk(url: string, frame: Uint8Array, signal?: AbortSignal): Promise<void> {
  const res = await fetch(url, { method: 'PUT', body: frame as BodyInit, signal })
  if (!res.ok) throw new Error(`chunk_put_failed:${res.status}`)
}

async function getChunk(url: string): Promise<Uint8Array> {
  const res = await fetch(url, { method: 'GET' })
  if (!res.ok) throw new Error(`chunk_get_failed:${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}
