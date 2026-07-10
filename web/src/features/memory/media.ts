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
import exifr from 'exifr'
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std } from '../../shared/base64'
import { mediaApi, bucketsApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { ResponseError, type MediaSummary, type BucketSummary, type PresignedUrl, type MediaDerivativeKind } from '../../api/generated/media'

const KBLOB_CTX = 'blob-key'
/**
 * AAD context binding the sealed **listed-metadata** tier to its purpose (cr-0068). This one blob carries the
 * geometry (`w,h`), the capture time (`capturedAt*`), and the GPS (`gpsLat,gpsLng`) — everything the grid, the
 * date sort, and the place medoid need at list-level. Consolidates the former `media-layout` + `media-capturedat`.
 */
const MEDIA_META_CTX = 'media-meta'
/** AAD context binding the sealed heavy EXIF tier to its purpose (cr-0064) — distinct from the listed tier's. */
const MEDIA_EXIF_CTX = 'media-exif'
// AAD contexts for bucket names sealed under K_mem (cr-0113): a contributor primary's name is the identity's
// sealed display name (cr-0101, copied server-side onto the bucket); a user-created sub-bucket seals with its own.
const SHARE_IDENTITY_NAME_CTX = 'share-identity-name'
const BUCKET_NAME_CTX = 'bucket-name'

/** Plaintext bytes per chunk before sealing (each frame is this + a 12-byte nonce + a 16-byte tag). */
export const CHUNK_SIZE = 256 * 1024

/**
 * Derivative tier specs (en.memory.derivatives → *The three tiers*). `preview` is pinned from the legacy E2EE
 * client; `thumbnail` was raised 400→512 px (cr-0070) so hi-DPI grid tiles aren't upscaled into mush. Parity is
 * by-property (a valid JPEG at the target edge), so apple/android carry the same 512.
 */
export const TIER_SPECS: Record<MediaDerivativeKind, { maxEdge: number; quality: number }> = {
  THUMBNAIL: { maxEdge: 512, quality: 0.8 }, // gallery grid (512 for retina headroom, cr-0070)
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

/** A media's pristine pixel dimensions, used to lay out the justified gallery grid (cr-0059). */
export type Dimensions = { w: number; h: number }

/**
 * The **listed-metadata tier** sealed under `K_mem` (cr-0068) — one always-listed blob (returned with every
 * `listMedia`) carrying everything the gallery + place need at list-level, kept small:
 * - **geometry** `w,h` — the justified grid's aspect (cr-0059);
 * - **capture time** `capturedAt` (EXIF date or the file's creation date) + any user `capturedAtOverride` — date
 *   sort/display/edit (cr-0066). Effective = `capturedAtOverride ?? capturedAt`. `capturedAtSource` records
 *   where the derived `capturedAt` came from: `'exif'` (a real `DateTimeOriginal`, trustworthy) or `'file'` (the
 *   file's modification date, a fallback that must NOT feed a Memory's `auto` date range — an edited photo's
 *   file date would wrongly stretch it);
 * - **GPS** `gpsLat,gpsLng` (decimal degrees) — the source of a Memory's place medoid (cr-0068, uc.memory.place).
 *
 * Every field is optional (best-effort). Versioned `v`, opaque — grows without a contract change.
 */
export type MediaMeta = {
  v: number
  w?: number
  h?: number
  capturedAt?: number
  capturedAtOverride?: number
  capturedAtSource?: 'exif' | 'file'
  gpsLat?: number
  gpsLng?: number
}

/**
 * The heavy **EXIF bundle** sealed under `K_mem`, lazy-loaded per item for the lightbox (cr-0064). A client-only,
 * versioned shape (never a contract field), so it can grow without the operator ever learning a field. Every
 * field is optional (best-effort extraction). The listed essentials — capture time (cr-0066) and GPS (cr-0068) —
 * live in the [MediaMeta] tier, not here.
 */
export type MediaExif = {
  v: number
  make?: string
  model?: string
  lens?: string
  focalLength?: number
  fNumber?: number
  exposureTime?: number
  iso?: number
  orientation?: number
  w?: number
  h?: number
}

/** A node's media for the UI/read-back: the original blob ref + its role-tagged derivative tiers. */
export type MediaItem = {
  mediaId: string
  original: BlobRef
  derivatives: MediaDerivative[]
  /** The original's listed-metadata tier sealed under `K_mem` (opaque base64); unwrap with [unsealMeta]. Absent when none. */
  wrappedMeta?: string
  /** When this media last changed (cr-0107) — the `(updatedAt, mediaId)` pair is the live-updates keyset cursor.
   *  Present for the authenticated gallery (`listMedia`/`listMediaPage`); absent for the public-share snapshot
   *  viewer, which doesn't poll (the sharing resolve carries no per-media timestamp). */
  updatedAt?: Date
  /** When this media was trashed + who trashed it (cr-0110, the deletion audit) — present only in the Trash tab
   *  listing ([listTrash]); undefined for a live media. */
  deletedAt?: Date
  deletedBy?: string
  /** cr-0113: the uploader's opaque owner_key (`user:{id}` | `share:{id}`) — the buckets attribution the Beiträge
   *  tab groups by; undefined for a pre-buckets member row or the public-share viewer (no attribution). */
  ownerKey?: string
  /** cr-0113: the bucket this media lives in, or undefined = the uploader's primary. */
  bucketId?: string
}

function summaryToItem(s: MediaSummary): MediaItem {
  return {
    mediaId: s.mediaId,
    original: { blobId: s.blobId, chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey },
    derivatives: (s.derivatives ?? []).map((d) => ({
      kind: d.kind,
      ref: { blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey },
    })),
    wrappedMeta: s.wrappedMeta ?? undefined,
    updatedAt: s.updatedAt,
    deletedAt: s.deletedAt ?? undefined,
    deletedBy: s.deletedBy ?? undefined,
    ownerKey: s.ownerKey ?? undefined,
    bucketId: s.bucketId ?? undefined,
  }
}

/**
 * Unwrap a media's sealed listed-metadata tier under `K_mem` (cr-0068; operator-blind — the server only ever
 * holds the ciphertext). Best-effort: null when none was sealed or the wrap doesn't open. Geometry `w,h` is
 * validated (positive, finite) so a bad payload falls back to a default aspect rather than break the grid; the
 * other fields are coerced to finite numbers. Use [effectiveCapturedAt] for the sort/display date.
 */
export function unsealMeta(memoryKey: Uint8Array, item: MediaItem): MediaMeta | null {
  if (!item.wrappedMeta) return null
  try {
    const json = new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(item.wrappedMeta), MEDIA_META_CTX))
    const parsed = JSON.parse(json) as unknown
    if (parsed && typeof parsed === 'object') {
      const m = parsed as MediaMeta
      const w = finiteNum(m.w)
      const h = finiteNum(m.h)
      const geo = w !== undefined && h !== undefined && w > 0 && h > 0
      const source = m.capturedAtSource === 'exif' || m.capturedAtSource === 'file' ? m.capturedAtSource : undefined
      return {
        v: finiteNum(m.v) ?? 1,
        w: geo ? w : undefined,
        h: geo ? h : undefined,
        capturedAt: finiteNum(m.capturedAt),
        capturedAtOverride: finiteNum(m.capturedAtOverride),
        capturedAtSource: source,
        gpsLat: finiteNum(m.gpsLat),
        gpsLng: finiteNum(m.gpsLng),
      }
    }
  } catch {
    /* not openable / malformed → treated as no metadata */
  }
  return null
}

/** The effective capture time of a media's metadata tier: the user's correction if set, else the derived original. */
export function effectiveCapturedAt(meta: MediaMeta | null): number | undefined {
  return finiteNum(meta?.capturedAtOverride) ?? finiteNum(meta?.capturedAt)
}

// --- the pixel stage (injectable; jsdom cannot decode real Canvas/Image/Video) ---------------------------

/** The source to attach: the pristine original bytes + their MIME type, plus the file's creation date (cr-0066). */
export type AddMediaSource = { bytes: Uint8Array; mediaType: string; lastModified?: number }

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
  // Progressive-halving downscale. A single large drawImage (e.g. 4032→512 ≈ 8×) stays soft even at
  // imageSmoothingQuality:'high' — the browser applies ONE bilinear pass, not an iterative filter, so it drops
  // high-frequency detail. Halving in ≤2× steps (each with high smoothing) preserves that detail, approximating
  // the multi-tap resample the legacy server did with libvips (frontend-noe2ee derived server-side; operator-blind
  // forces it on-device here). scaleToMaxEdge never upscales, so a small source just skips the loop.
  let curSrc: CanvasImageSource = src
  let curW = w
  let curH = h
  while (curW > width * 2 || curH > height * 2) {
    const nextW = Math.max(Math.round(curW / 2), width)
    const nextH = Math.max(Math.round(curH / 2), height)
    curSrc = drawStep(curSrc, nextW, nextH)
    curW = nextW
    curH = nextH
  }
  const canvas = drawStep(curSrc, width, height)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
  if (!blob) throw new Error('encode_failed')
  return new Uint8Array(await blob.arrayBuffer())
}

/** One high-quality `drawImage` onto a fresh canvas at `width`×`height` (each downscale step of drawToJpeg). */
function drawStep(src: CanvasImageSource, width: number, height: number): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas_unavailable')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(src, 0, 0, width, height)
  return canvas
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

/** Seal a small JSON payload under `K_mem` (a short AEAD blob, like a wrapped key) → opaque base64. */
function sealJson(memoryKey: Uint8Array, value: unknown, context: string): string {
  const bytes = new TextEncoder().encode(JSON.stringify(value))
  return b64std(v1.symWrap(memoryKey, bytes, context, v1.randomBytes(12)))
}

function finiteNum(n: unknown): number | undefined {
  return typeof n === 'number' && Number.isFinite(n) ? n : undefined
}

function cleanStr(s: unknown): string | undefined {
  const t = typeof s === 'string' ? s.trim() : ''
  return t.length ? t : undefined
}

/** Coerce an EXIF date (exifr yields a `Date`; some sources a number) to epoch-ms, or undefined when unusable. */
function toEpochMs(d: unknown): number | undefined {
  if (d instanceof Date) return finiteNum(d.getTime())
  return finiteNum(d)
}

/** Whether an EXIF bundle carries anything beyond the version tag (else there's nothing worth sealing). */
function hasExifPayload(e: MediaExif): boolean {
  return Object.entries(e).some(([k, val]) => k !== 'v' && val !== undefined)
}

/** Probe the original's intrinsic `{w,h}` (best-effort — undecodable/unsupported/no prober → null). */
async function probeDims(source: AddMediaSource, deriver: MediaDeriver): Promise<Dimensions | null> {
  if (!deriver.probeDimensions) return null
  try {
    const blob = new Blob([source.bytes as BlobPart], { type: source.mediaType })
    return await deriver.probeDimensions(blob, source.mediaType)
  } catch {
    return null
  }
}

/**
 * Read an image's original bytes with `exifr` (HEIC/JPEG/TIFF/PNG; GPS auto-decoded DMS→decimal) and split the
 * result into the **capture time** (epoch-ms, cr-0066) and the **EXIF bundle** (camera/lens/gps/dims, date-free).
 * Best-effort: non-images, unreadable/absent EXIF, or a decode error → `{ capturedAt: undefined, exif: null }`
 * (the item still uploads). Never leaves the device in the clear — the caller seals both.
 */
async function readExif(source: AddMediaSource): Promise<{ capturedAt?: number; gpsLat?: number; gpsLng?: number; exif: MediaExif | null }> {
  if (!source.mediaType.startsWith('image/')) return { exif: null }
  try {
    const blob = new Blob([source.bytes as BlobPart], { type: source.mediaType })
    const raw = await exifr.parse(blob, { tiff: true, exif: true, gps: true })
    if (!raw) return { exif: null }
    const exif: MediaExif = {
      v: 1,
      make: cleanStr(raw.Make),
      model: cleanStr(raw.Model),
      lens: cleanStr(raw.LensModel ?? raw.LensMake),
      focalLength: finiteNum(raw.FocalLength),
      fNumber: finiteNum(raw.FNumber),
      exposureTime: finiteNum(raw.ExposureTime),
      iso: finiteNum(raw.ISO),
      orientation: finiteNum(raw.Orientation),
      w: finiteNum(raw.ExifImageWidth ?? raw.ImageWidth),
      h: finiteNum(raw.ExifImageHeight ?? raw.ImageHeight),
    }
    return {
      capturedAt: toEpochMs(raw.DateTimeOriginal ?? raw.CreateDate ?? raw.ModifyDate),
      gpsLat: finiteNum(raw.latitude), // GPS rides the listed tier now (cr-0068), not the lazy EXIF
      gpsLng: finiteNum(raw.longitude),
      exif: hasExifPayload(exif) ? exif : null,
    }
  } catch {
    return { exif: null }
  }
}

/**
 * Seal the two metadata tiers under `K_mem` (best-effort, operator-blind): the always-listed **metadata** tier
 * (`{v,w,h,capturedAt,capturedAtSource,gpsLat,gpsLng}` — geometry + the EXIF date, else the file's creation date,
 * + GPS) and the lazy **EXIF** tier (camera/lens/dims). Each tier is null when its source is absent.
 */
async function sealMetadata(
  source: AddMediaSource,
  deriver: MediaDeriver,
  memoryKey: Uint8Array,
): Promise<{ wrappedMeta: string | null; wrappedExif: string | null }> {
  const [dims, meta] = await Promise.all([probeDims(source, deriver), readExif(source)])
  const capturedAt = meta.capturedAt ?? finiteNum(source.lastModified) // EXIF date, else the file's creation date
  // the source distinguishes a trustworthy EXIF date from a file-modification-date fallback (cr-0067): only
  // the former (or a user override) feeds a Memory's `auto` range — a file date must not stretch it.
  const capturedAtSource: 'exif' | 'file' | undefined =
    meta.capturedAt !== undefined ? 'exif' : capturedAt !== undefined ? 'file' : undefined
  const metaTier: MediaMeta = {
    v: 1,
    w: dims?.w,
    h: dims?.h,
    capturedAt,
    capturedAtSource: capturedAt !== undefined ? capturedAtSource : undefined,
    gpsLat: meta.gpsLat,
    gpsLng: meta.gpsLng,
  }
  const hasMeta = metaTier.w !== undefined || metaTier.capturedAt !== undefined || metaTier.gpsLat !== undefined || metaTier.gpsLng !== undefined
  const wrappedMeta = hasMeta ? sealJson(memoryKey, prune(metaTier), MEDIA_META_CTX) : null
  const wrappedExif = meta.exif ? sealJson(memoryKey, meta.exif, MEDIA_EXIF_CTX) : null
  return { wrappedMeta, wrappedExif }
}

/** Drop `undefined` fields so a sealed blob carries only what's present (keeps it small + JSON tidy). */
function prune<T extends object>(obj: T): Partial<T> {
  return Object.fromEntries(Object.entries(obj).filter(([, v]) => v !== undefined)) as Partial<T>
}

/** Upload progress (0..1) + cancellation hooks for [addMedia], used by the upload queue (uc.memory.upload). */
export type AddMediaOptions = { onProgress?: (fraction: number) => void; signal?: AbortSignal }

/** Throw an AbortError if the caller cancelled — the queue maps it to a cancelled (not failed) upload. */
function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) throw new DOMException('Upload cancelled', 'AbortError')
}

/** One blob to reserve: its declared shape + the K_blob wrap (under K_mem). */
export type BlobDeclare = { chunkCount: number; byteSize: number; wrappedBlobKey: string }
/** One reserved upload: the session id + the ordered per-chunk PUT URLs. */
export type ReservedUpload = { uploadId: string; putUrls: string[] }
/** One logical media to complete: the original session + its role-tagged derivative sessions + the sealed metadata. */
export type CompleteDecl = {
  originalUploadId: string
  derivatives: { kind: MediaDerivativeKind; uploadId: string }[]
  wrappedMeta: string | null
  wrappedExif: string | null
}
/**
 * The upload control plane behind [uploadMediaVia] — reserve sessions + presign PUTs, then complete + attach.
 * The **authed** transport ([mediaApiTransport]) drives the internal Memory upload; the **share-scoped**
 * transport (share.ts) drives the anonymous contributor upload (cr-0101). The sealing + direct-to-store PUTs
 * are identical either way — only who authorizes the reserve/complete differs.
 */
export type MediaUploadTransport = {
  reserve(declares: BlobDeclare[]): Promise<ReservedUpload[]>
  complete(media: CompleteDecl): Promise<{ blobId: string; mediaId: string }>
}

/**
 * Seal the original + its derivatives (each under its own `K_blob`), upload all ciphertext chunks direct-to-store,
 * and complete + attach — via an injected [MediaUploadTransport]. Shared by the authed [addMedia] and the
 * anonymous contributor upload. `deriver` is injectable for tests; `opts` carries `onProgress` (0..1) + an
 * `AbortSignal`.
 */
export async function uploadMediaVia(
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver,
  transport: MediaUploadTransport,
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  const { onProgress, signal } = opts
  throwIfAborted(signal)
  const tiers = await deriveTiers(source, deriver)
  const { wrappedMeta, wrappedExif } = await sealMetadata(source, deriver, memoryKey)
  // index 0 = original; the rest are the role-tagged derivatives, in order
  const sealedOriginal = sealBlob(memoryKey, source.bytes)
  const sealedTiers = tiers.map((t) => ({ kind: t.kind, sealed: sealBlob(memoryKey, t.bytes) }))
  const sealedAll = [sealedOriginal, ...sealedTiers.map((t) => t.sealed)]

  // reserve — one upload session + presign one PUT per chunk, per blob
  throwIfAborted(signal)
  const reserved = await transport.reserve(
    sealedAll.map((s) => ({ chunkCount: s.chunkCount, byteSize: s.byteSize, wrappedBlobKey: s.wrappedBlobKey })),
  )
  if (reserved.length !== sealedAll.length) throw new Error('upload_failed')
  const uploads = reserved.map((r, i) => ({ uploadId: r.uploadId, putUrls: r.putUrls, frames: sealedAll[i].frames }))

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
  return transport.complete({
    originalUploadId: uploads[0].uploadId,
    derivatives: sealedTiers.map((t, i) => ({ kind: t.kind, uploadId: uploads[i + 1].uploadId })),
    wrappedMeta,
    wrappedExif,
  })
}

/** The authed upload transport — the internal Memory control plane (`startBlobUploads` + `completeMedia`). */
function mediaApiTransport(scopeId: string, nodeId: string): MediaUploadTransport {
  return {
    async reserve(declares) {
      const started = await withRefresh(() =>
        mediaApi().startBlobUploads({ scopeId, blobUploadsBatchRequest: { items: declares } }),
      )
      if (started.results.length !== declares.length) throw new Error('upload_failed')
      return started.results.map((r) => {
        if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
        if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
        return { uploadId: r.uploadId, putUrls: byIndex(r.presignedPuts) }
      })
    },
    async complete(media) {
      const done = await withRefresh(() =>
        mediaApi().completeMedia({
          scopeId,
          mediaBatchRequest: {
            items: [{ nodeId, originalUploadId: media.originalUploadId, derivatives: media.derivatives, wrappedMeta: media.wrappedMeta, wrappedExif: media.wrappedExif }],
          },
        }),
      )
      const m = done.results[0]
      if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
      return { blobId: m.blobId, mediaId: m.mediaId }
    },
  }
}

/**
 * Seal the original + its derivatives, upload direct-to-store, and attach to `nodeId` (a MEMORY/CHAPTER) as one
 * role-tagged media. Returns the new ids. `deriver` is injectable for tests; `opts` carries `onProgress` + an
 * `AbortSignal` for the upload queue.
 */
export async function addMedia(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  return uploadMediaVia(memoryKey, source, deriver, mediaApiTransport(scopeId, nodeId), opts)
}

// --- standalone cover blob (cr-0073) ---------------------------------------

/**
 * Cover downscale spec: a Memory's cover is only ever shown small-to-medium (a card tile, a ~180 px detail
 * preview, a 34 px pin), so an uploaded cover is downscaled to ONE cover-sized JPEG — no THUMBNAIL/PREVIEW
 * tiers. Matches the PREVIEW long edge; the normalized crop then renders it identically at any size.
 */
export const COVER_SPEC = { maxEdge: 2048, quality: 0.85 }

/**
 * Upload a brand-new image as a Memory's **standalone cover blob** (cr-0073, uc.memory.cover second source):
 * downscale on device to one cover-sized JPEG, seal it under a fresh `K_blob` (wrapped under `K_mem`), upload
 * the ciphertext chunks direct-to-store, and commit it to the node via `commitCoverBlob` — attached with **no
 * `veyq_media` row** (never in the gallery, no derivative tiers). Commit **atomically reclaims** any prior
 * cover blob on the node. Returns the committed [BlobRef] the display layer opens with [openBlob]. Best-effort
 * downscale: an undecodable source seals the original bytes rather than fail (uc.memory.upload E4).
 */
export async function uploadCoverBlob(
  scopeId: string,
  nodeId: string,
  memoryKey: Uint8Array,
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
): Promise<BlobRef> {
  let bytes = source.bytes
  if (source.mediaType.startsWith('image/')) {
    try {
      bytes = await deriver.deriveImage(new Blob([source.bytes as BlobPart], { type: source.mediaType }), COVER_SPEC)
    } catch {
      /* undecodable codec → seal the pristine bytes (still a valid, if larger, cover) */
    }
  }
  const sealed = sealBlob(memoryKey, bytes)

  const started = await withRefresh(() =>
    mediaApi().startBlobUploads({
      scopeId,
      blobUploadsBatchRequest: {
        items: [{ chunkCount: sealed.chunkCount, byteSize: sealed.byteSize, wrappedBlobKey: sealed.wrappedBlobKey }],
      },
    }),
  )
  const r = started.results[0]
  if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
  if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
  const putUrls = byIndex(r.presignedPuts)
  await Promise.all(putUrls.map((url, i) => putChunk(url, sealed.frames[i])))

  const committed = await withRefresh(() =>
    mediaApi().commitCoverBlob({ scopeId, nodeId, coverBlobCommitRequest: { uploadId: r.uploadId! } }),
  )
  return { blobId: committed.blobId, chunkCount: committed.chunkCount, byteSize: committed.byteSize, wrappedBlobKey: committed.wrappedBlobKey }
}

/**
 * Detach + reclaim a node's standalone cover blob (cr-0073) — the client-driven counterpart to `deleteNode`'s
 * cascade. Called when an **uploaded** cover is superseded by a media cover or removed (a fresh upload commit
 * already reclaims the prior blob atomically, so a replace-upload-with-upload needs no explicit delete).
 */
export async function deleteCoverBlob(scopeId: string, nodeId: string): Promise<void> {
  await withRefresh(() => mediaApi().deleteCoverBlob({ scopeId, nodeId }))
}

// --- delete / restore / trash (uc.memory.delete-media, cr-0110) -------------

/**
 * Trash a media — soft-delete it into the Memory's recoverable per-Memory trash. Any scope member may do it; the
 * server flips `deleted_at`/`deleted_by` (the audit) and bumps `delete_revision` so every open gallery drops the
 * tile on its next poll (cr-0107). The row + its blobs survive until the retention purge, so [restoreMedia] brings
 * it back. Operator-blind — a flag flip, no content, no key.
 */
export async function deleteMedia(scopeId: string, mediaId: string): Promise<void> {
  await withRefresh(() => mediaApi().deleteMedia({ scopeId, mediaId }))
}

/** Restore a trashed media back into the gallery (cr-0110) — clears the trash flags; the tile re-surfaces via the live poll. */
export async function restoreMedia(scopeId: string, mediaId: string): Promise<void> {
  await withRefresh(() => mediaApi().restoreMedia({ scopeId, mediaId }))
}

/**
 * List a node's TRASHED media for the Trash tab (cr-0110): the soft-deleted items with their deletion audit
 * (`deletedAt`/`deletedBy`), newest-trashed first — a plain full list (not the live conditional-GET). Each item
 * still carries its original + derivative tiers, so the tab renders thumbnails the same way the gallery does.
 */
export async function listTrash(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const page = await withRefresh(() => mediaApi().listNodeMedia({ scopeId, nodeId, state: 'trashed' }))
  return page.media.map(summaryToItem)
}

/**
 * Permanently delete a **trashed** media now (cr-0111) — the member-expedited purge (hard-delete + blob reclaim).
 * **Irreversible.** Only a media already in the trash can be purged (a live one is a 404). Operator-blind.
 */
export async function purgeMedia(scopeId: string, mediaId: string): Promise<void> {
  await withRefresh(() => mediaApi().purgeMedia({ scopeId, mediaId }))
}

// --- buckets (en.memory.buckets, ADR 0034, cr-0113) — the per-uploader "Beiträge" grouping -------------------

/** One uploader bucket in a node: the opaque owner_key + structure + the (sealed) name + a live media count. */
export type BucketItem = {
  bucketId: string
  ownerKey: string
  parentBucketId?: string
  isPrimary: boolean
  /** The K_mem-sealed name (opaque base64): a contributor primary + every sub-bucket carry one; a member's primary
   *  is undefined (the client shows the operator-visible username). Unwrap with [openBucketName]. */
  sealedName?: string
  mediaCount: number
}

/**
 * Unwrap a bucket name under K_mem — best-effort (null when absent/unopenable, so the picker falls back to a
 * neutral label). A **contributor** primary's name is the identity's sealed display name (cr-0101, copied
 * server-side); a **sub-bucket** seals with its own tag (cr-0113). Operator-blind — the server relays ciphertext.
 */
export function openBucketName(memoryKey: Uint8Array, sealedName: string, kind: 'contributor' | 'sub'): string | null {
  const ctx = kind === 'contributor' ? SHARE_IDENTITY_NAME_CTX : BUCKET_NAME_CTX
  try {
    return new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(sealedName), ctx))
  } catch {
    return null // not openable → the picker uses a neutral fallback label
  }
}

function toBucket(b: BucketSummary): BucketItem {
  return {
    bucketId: b.bucketId,
    ownerKey: b.ownerKey,
    parentBucketId: b.parentBucketId ?? undefined,
    isPrimary: b.isPrimary,
    sealedName: b.sealedName ?? undefined,
    mediaCount: b.mediaCount,
  }
}

/** List a node's uploader buckets for the "Beiträge" tab (primaries + sub-buckets) with per-bucket counts. */
export async function listBuckets(scopeId: string, nodeId: string): Promise<BucketItem[]> {
  const res = await withRefresh(() => bucketsApi().listNodeBuckets({ scopeId, nodeId }))
  return res.buckets.map(toBucket)
}

/** Create a sub-bucket under a primary root — the client seals the folder name under K_mem. `parentBucketId` nests
 *  it under that root (a contributor's, to curate contributions — cr-0123); omitted ⇒ the caller's own primary. */
export async function createBucket(scopeId: string, nodeId: string, memoryKey: Uint8Array, name: string, parentBucketId?: string): Promise<BucketItem> {
  const sealedName = b64std(v1.symWrap(memoryKey, v1.utf8(name), BUCKET_NAME_CTX, v1.randomBytes(12)))
  return toBucket(await withRefresh(() => bucketsApi().createBucket({ scopeId, nodeId, createBucketRequest: { sealedName, parentBucketId } })))
}

/** Rename one of the caller's own sub-buckets — re-seal the name under K_mem (a later slice's UI). */
export async function renameBucket(scopeId: string, nodeId: string, memoryKey: Uint8Array, bucketId: string, name: string): Promise<BucketItem> {
  const sealedName = b64std(v1.symWrap(memoryKey, v1.utf8(name), BUCKET_NAME_CTX, v1.randomBytes(12)))
  return toBucket(await withRefresh(() => bucketsApi().renameBucket({ scopeId, nodeId, bucketId, renameBucketRequest: { sealedName } })))
}

/** Delete one of the caller's own sub-buckets — its media reparent to the primary (a later slice's UI). */
export async function deleteBucket(scopeId: string, nodeId: string, bucketId: string): Promise<void> {
  await withRefresh(() => bucketsApi().deleteBucket({ scopeId, nodeId, bucketId }))
}

/** Move the caller's own media into a target bucket (null = the caller's primary) — a later slice's UI. */
export async function moveBucketMedia(scopeId: string, nodeId: string, mediaIds: string[], targetBucketId: string | null): Promise<void> {
  await withRefresh(() => bucketsApi().moveBucketMedia({ scopeId, nodeId, moveMediaRequest: { mediaIds, targetBucketId } }))
}

// --- read-back -------------------------------------------------------------

/**
 * One conditional-GET page of a node's media (uc.memory.live-updates, cr-0107, ADR 0033). A poll echoes the
 * cached `mediaRevision` as `ifNoneMatch`; unchanged ⇒ `{ notModified: true }` (the server ran no media query).
 * On a change ⇒ a **keyset delta** — only the media past the `since` cursor (`"updatedAt,mediaId"`) — plus both
 * revisions and, **only when a delete advanced `deleteRevision`** past the echoed `sinceDelete`, the node's
 * current `mediaIds` set for delete-reconcile (else null). First load: omit `ifNoneMatch`/`since` for the full
 * list. Operator-blind: opaque counters + ids + wraps only.
 */
export type MediaPage =
  | { notModified: true }
  | { notModified: false; items: MediaItem[]; mediaRevision: number; deleteRevision: number; mediaIds: string[] | null }

export async function listMediaPage(
  scopeId: string,
  nodeId: string,
  opts: { ifNoneMatch?: string; since?: string; sinceDelete?: number } = {},
): Promise<MediaPage> {
  try {
    const page = await withRefresh(() =>
      mediaApi().listNodeMedia({ scopeId, nodeId, ifNoneMatch: opts.ifNoneMatch, since: opts.since, sinceDelete: opts.sinceDelete }),
    )
    return {
      notModified: false,
      items: page.media.map(summaryToItem),
      mediaRevision: page.mediaRevision,
      deleteRevision: page.deleteRevision,
      mediaIds: page.mediaIds ?? null,
    }
  } catch (e) {
    if (e instanceof ResponseError && e.response.status === 304) return { notModified: true } // idle fast path — nothing changed
    throw e
  }
}

/**
 * List a node's media in full (opaque metadata + the K_blob wraps for the original + every derivative tier). A
 * thin wrapper over [listMediaPage] with no cursor/ETag, so the server always returns the whole list — used
 * where a snapshot suffices (cover picker, tests); the live gallery uses [listMediaPage] directly to poll deltas.
 */
export async function listMedia(scopeId: string, nodeId: string): Promise<MediaItem[]> {
  const page = await listMediaPage(scopeId, nodeId)
  return page.notModified ? [] : page.items // no ifNoneMatch was sent, so a 304 is impossible — the guard is just exhaustiveness
}

/** The newest `(updatedAt, mediaId)` keyset cursor across [items] — what a live poll echoes as `since`; undefined
 *  when none carry an `updatedAt` (e.g. an empty grid). Millisecond-precision by construction (a JS `Date`), which
 *  round-trips losslessly against the server's `TIMESTAMP(3)` so the same rows are never re-fetched. */
export function newestCursor(items: readonly MediaItem[]): string | undefined {
  let bestT = -Infinity
  let bestId = ''
  let found = false
  for (const it of items) {
    if (!it.updatedAt) continue
    const t = it.updatedAt.getTime()
    if (t > bestT || (t === bestT && it.mediaId > bestId)) {
      bestT = t
      bestId = it.mediaId
      found = true
    }
  }
  return found ? `${new Date(bestT).toISOString()},${bestId}` : undefined
}

/**
 * Lazily fetch + unseal a media's heavy **EXIF** bundle via the exif endpoint (cr-0064/0068) — the lightbox calls
 * this only when an item opens, so `listMedia` stays constant-small. Operator-blind: the server returns opaque
 * ciphertext, unsealed here under `K_mem`. Best-effort: null when none was sealed, the wrap doesn't open, or the
 * payload isn't a sane object (the lightbox then shows no detail rather than break).
 */
export async function loadMediaExif(scopeId: string, mediaId: string, memoryKey: Uint8Array): Promise<MediaExif | null> {
  const exif = await withRefresh(() => mediaApi().getMediaExif({ scopeId, mediaId }))
  if (!exif.wrappedExif) return null
  try {
    const json = new TextDecoder().decode(v1.symUnwrap(memoryKey, fromB64std(exif.wrappedExif), MEDIA_EXIF_CTX))
    const parsed = JSON.parse(json) as unknown
    return parsed && typeof parsed === 'object' ? (parsed as MediaExif) : null
  } catch {
    return null
  }
}

/**
 * Set (or clear, with `null`) a media's **capturedAt override** (cr-0065/0066/0068) — a corrected capture time.
 * Operator-blind: the whole listed-metadata tier is re-sealed under `K_mem` with the override added/removed and
 * PUT verbatim (geometry + GPS ride along unchanged — only the date is user-editable). `meta` is the tier already
 * unsealed by the lightbox (or null for a media with none — a fresh tier is sealed). Clearing the last field
 * drops the tier to null. Returns the new tier so the caller can update local state (and the grid can re-sort).
 */
export async function setMediaMeta(
  scopeId: string,
  mediaId: string,
  memoryKey: Uint8Array,
  meta: MediaMeta | null,
  overrideMs: number | null,
): Promise<{ tier: MediaMeta | null; wrappedMeta: string | null }> {
  const next: MediaMeta = { ...(meta ?? { v: 1 }), capturedAtOverride: overrideMs ?? undefined }
  const hasContent =
    next.w !== undefined || next.capturedAt !== undefined || next.capturedAtOverride !== undefined ||
    next.gpsLat !== undefined || next.gpsLng !== undefined
  const wrappedMeta = hasContent ? sealJson(memoryKey, prune(next), MEDIA_META_CTX) : null
  await withRefresh(() => mediaApi().updateMediaMeta({ scopeId, mediaId, mediaMetaUpdate: { wrappedMeta } }))
  // return both the unsealed tier (for local state) and the sealed blob (so the grid item stays consistent)
  return { tier: wrappedMeta ? prune(next) as MediaMeta : null, wrappedMeta }
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
 * Open a blob from a set of **already-presigned** per-chunk GET URLs: unwrap its `K_blob` under `K_mem`, fetch
 * the ciphertext frames direct-from-store, and `openFrame` them back to plaintext (the AAD position check still
 * applies). Shared by the authed [openBlob] (presign via the media endpoint) and the anonymous share viewer
 * (presign via the gated share endpoint, cr-0100) — the decrypt is identical; only who presigns differs.
 */
export async function openBlobFromUrls(memoryKey: Uint8Array, ref: BlobRef, getUrls: string[]): Promise<Uint8Array> {
  const blobKey = v1.symUnwrap(memoryKey, fromB64std(ref.wrappedBlobKey), KBLOB_CTX)
  const frames = await Promise.all(getUrls.map((url) => getChunk(url)))
  const chunks = frames.map((frame, i) => v1.openFrame(blobKey, i, frames.length, frame))
  return v1.concat(...chunks)
}

/**
 * Open a blob: unwrap its `K_blob` under `K_mem`, presign the chunk GETs, fetch the ciphertext frames
 * direct-from-store, and `openFrame` them back to plaintext (the AAD position check still applies).
 */
export async function openBlob(scopeId: string, memoryKey: Uint8Array, ref: BlobRef): Promise<Uint8Array> {
  const presigned = await withRefresh(() =>
    mediaApi().presignBlobDownloads({ scopeId, blobDownloadsBatchRequest: { items: [{ blobId: ref.blobId }] } }),
  )
  const d = presigned.results[0]
  if (!d || d.error || !d.presignedGets) throw new Error(d?.error ?? 'store_unavailable')
  return openBlobFromUrls(memoryKey, ref, byIndex(d.presignedGets))
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
