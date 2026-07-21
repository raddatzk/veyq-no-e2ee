// en.memory.derivatives (web) — the on-device pixel stage split out of `media.ts` (cr-0143).
// A media's role-tagged derivatives (thumbnail/preview; a video's first-frame poster) are generated here:
// decode → progressive-halving downscale → JPEG re-encode, all client-side (operator-blind — the server never
// sees a pixel). The stage sits behind an injectable `MediaDeriver` so `media.ts`'s add-media wiring is
// unit-testable against a stub; the real Canvas/`<video>` path is exercised by a live browser round-trip.
import { type MediaDerivativeKind } from '../../api/generated/media'

/**
 * Derivative tier specs (en.memory.derivatives → *The three tiers*). `preview` is pinned from the legacy E2EE
 * client; `thumbnail` was raised 400→512 px (cr-0070) so hi-DPI grid tiles aren't upscaled into mush. Parity is
 * by-property (a valid JPEG at the target edge), so apple/android carry the same 512.
 */
export const TIER_SPECS: Record<MediaDerivativeKind, { maxEdge: number; quality: number }> = {
  THUMBNAIL: { maxEdge: 512, quality: 0.8 }, // gallery grid (512 for retina headroom, cr-0070)
  PREVIEW: { maxEdge: 2048, quality: 0.85 }, // lightbox
}

/** A media's pristine pixel dimensions, used to lay out the justified gallery grid (cr-0059). */
export type Dimensions = { w: number; h: number }

/** The source to attach: the pristine original bytes + their MIME type, plus the file's creation date (cr-0066). */
export type AddMediaSource = { bytes: Uint8Array; mediaType: string; lastModified?: number }

/**
 * The platform-native pixel stage behind an interface: the real one uses Canvas + `<video>`; tests inject a
 * stub. A derivative need only be a valid JPEG at the target longest edge — parity here is by **property**, not
 * bytes (en.memory.derivatives → *Quality notes*).
 */
export interface MediaDeriver {
  /**
   * Downscale an image Blob to `maxEdge` at `quality`. Encodes JPEG by default; pass `mimeType: 'image/webp'`
   * for an alpha-preserving encode (a space icon, uc.spaces.set-space-icon). `background` fills the canvas before
   * drawing (a hex/CSS colour) so a transparent source does not flatten onto the canvas default (transparent
   * black → black once JPEG-flattened); omit it to keep alpha (only meaningful with an alpha-capable `mimeType`).
   */
  deriveImage(source: Blob, opts: { maxEdge: number; quality: number; mimeType?: string; background?: string }): Promise<Uint8Array>
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
    async deriveImage(source, { maxEdge, quality, mimeType = 'image/jpeg', background }) {
      const bitmap = await createImageBitmap(source)
      try {
        return drawEncoded(bitmap, bitmap.width, bitmap.height, maxEdge, quality, mimeType, background)
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
        return drawEncoded(video, video.videoWidth, video.videoHeight, TIER_SPECS.PREVIEW.maxEdge, TIER_SPECS.PREVIEW.quality)
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

async function drawEncoded(
  src: CanvasImageSource, w: number, h: number, maxEdge: number, quality: number,
  mimeType = 'image/jpeg', background?: string,
): Promise<Uint8Array> {
  const { width, height } = scaleToMaxEdge(w, h, maxEdge)
  // Progressive-halving downscale. A single large drawImage (e.g. 4032→512 ≈ 8×) stays soft even at
  // imageSmoothingQuality:'high' — the browser applies ONE bilinear pass, not an iterative filter, so it drops
  // high-frequency detail. Halving in ≤2× steps (each with high smoothing) preserves that detail, approximating
  // the multi-tap resample the legacy server did with libvips (frontend-noe2ee derived server-side; operator-blind
  // forces it on-device here). scaleToMaxEdge never upscales, so a small source just skips the loop.
  // The intermediate steps keep alpha (no fill); the background is applied only on the FINAL canvas that is encoded.
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
  const canvas = drawStep(curSrc, width, height, background)
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, mimeType, quality))
  if (!blob) throw new Error('encode_failed')
  return new Uint8Array(await blob.arrayBuffer())
}

/** One high-quality `drawImage` onto a fresh canvas at `width`×`height` (each downscale step of drawEncoded).
 *  `background` (a CSS colour) fills the canvas before drawing, so a transparent source lands on it rather than
 *  the transparent-black default; omit it to preserve alpha (only meaningful with an alpha-capable encode). */
function drawStep(src: CanvasImageSource, width: number, height: number, background?: string): HTMLCanvasElement {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('canvas_unavailable')
  if (background) {
    ctx.fillStyle = background
    ctx.fillRect(0, 0, width, height)
  }
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
export async function deriveTiers(source: AddMediaSource, deriver: MediaDeriver): Promise<{ kind: MediaDerivativeKind; bytes: Uint8Array }[]> {
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
