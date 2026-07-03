// en.memory.node-meta (web) — a Memory's curated **cover**, the `cover` sub-object of the sealed node-meta blob
// (uc.memory.cover, cr-0072). One deliberately chosen image plus a crop: an existing Memory media referenced by
// `mediaId`, framed by a **normalized `0..1` crop** against that source image. Sealed on the NODE under its
// `K_mem` beside the `date`/`location` (operator-blind, ADR 0012) — the server only ever holds ciphertext, never
// which image or the crop.
//
// The seal is **read-modify-write** (shared `nodeMeta.ts`): `setMemoryCover` unseals the current
// `{ v, date?, location?, cover? }`, swaps in (or drops) only the `cover`, and re-seals — so it never clobbers a
// sibling `date`/`location`. `null` removes the cover while preserving them.
//
// A cover is **never auto-derived** — it is a deliberate per-Memory "set cover" choice; unset ⇒ the placeholder.
// The `crop` is normalized `0..1` so **one stored crop renders identically at any size** — the browse card, the
// detail header, and the map pin — via the pure `coverImageStyle` display formula. This module owns the model +
// seal + display math + the on-device cover-thumbnail load (`loadNodeCover`); the media picker and the 16:9
// pan/zoom crop editor live in the UI layer (cr-0072 phase 3).
import { contentApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { hasDerivatives, listMedia, openGrid } from './media'
import { sealNodeMeta, unsealNodeMeta } from './nodeMeta'

/**
 * A crop rectangle in the source image's **normalized `0..1`** coordinates (the pre-E2EE design source's
 * `CoverCrop`): `x,y` is the top-left corner, `width,height` the extent, all fractions of the source dimensions.
 * A crop of `{ x: 0, y: 0, width: 1, height: 1 }` is the full frame. Normalizing to `0..1` means one stored crop
 * renders identically at any container size, with no per-surface crop and no cropped-image upload.
 */
export type CoverCrop = { x: number; y: number; width: number; height: number }

/** A Memory's chosen cover: one of its own media (`mediaId`) framed by a normalized `crop`. */
export type MemoryCover = { mediaId: string; crop: CoverCrop }

/** A pick-list entry for the cover editor: a Memory media by id + its already-decrypted thumbnail object URL. */
export type CoverPickItem = { mediaId: string; url: string; name?: string }

/** The full-frame crop — the fallback when a crop is absent or malformed (never a throw, E4). */
export const FULL_FRAME: CoverCrop = { x: 0, y: 0, width: 1, height: 1 }

const clamp = (v: number, min: number, max: number): number => Math.min(max, Math.max(min, v))
const finiteNum = (v: unknown): number | undefined => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)

/**
 * Normalize a loosely-typed crop payload to a valid [CoverCrop] kept **on-image**: `width`/`height` are clamped to
 * `(0..1]` (a non-positive/absent extent falls back to the full frame), then `x`/`y` are clamped so the rectangle
 * never runs off the image (`x ∈ [0, 1-width]`). Best-effort (E4) — a malformed crop yields the full frame, never
 * a throw.
 */
export function normalizeCoverCrop(raw: unknown): CoverCrop {
  if (!raw || typeof raw !== 'object') return { ...FULL_FRAME }
  const c = raw as { x?: unknown; y?: unknown; width?: unknown; height?: unknown }
  const w = finiteNum(c.width)
  const h = finiteNum(c.height)
  const width = w !== undefined && w > 0 ? clamp(w, 0, 1) : 1
  const height = h !== undefined && h > 0 ? clamp(h, 0, 1) : 1
  const x = clamp(finiteNum(c.x) ?? 0, 0, 1 - width)
  const y = clamp(finiteNum(c.y) ?? 0, 0, 1 - height)
  return { x, y, width, height }
}

/**
 * Normalize a loosely-typed `cover` payload to a valid [MemoryCover], or **null** for the "no cover" state. A
 * `mediaId` is mandatory (a cover always points at an image); the crop is normalized on-image (defaulting to the
 * full frame). Best-effort (E4): any other shape ⇒ null ⇒ the placeholder.
 */
export function normalizeMemoryCover(raw: unknown): MemoryCover | null {
  if (!raw || typeof raw !== 'object') return null
  const c = raw as { mediaId?: unknown; crop?: unknown }
  if (typeof c.mediaId !== 'string' || !c.mediaId) return null
  return { mediaId: c.mediaId, crop: normalizeCoverCrop(c.crop) }
}

/**
 * Unseal a Memory's cover from a node's `wrappedMeta` under `nodeKey` (`K_mem`). Best-effort (E3/E4): null when
 * the node carries no meta/cover, the wrap doesn't open, or the payload is malformed — every surface then shows
 * the placeholder rather than break.
 */
export function unsealMemoryCover(nodeKey: Uint8Array, node: { wrappedMeta: string | null }): MemoryCover | null {
  return normalizeMemoryCover(unsealNodeMeta(nodeKey, node.wrappedMeta).cover)
}

/**
 * **Read-modify-write** the node-meta blob: unseal the current `{ v, date?, location?, cover? }` under `nodeKey`
 * (`K_mem`), set (or, for `null`, **remove**) only the `cover`, re-seal, and PATCH via the partial `updateNode`
 * (cr-0072). **Preserves the sibling `date`/`location`** in every case. `currentMeta` is the node's live
 * `wrappedMeta` (or null). Returns the sealed blob so the caller can update its local `node.wrappedMeta`
 * (and re-render the card/header) without a re-list.
 */
export async function setMemoryCover(
  scopeId: string,
  nodeId: string,
  nodeKey: Uint8Array,
  cover: MemoryCover | null,
  currentMeta: string | null,
): Promise<string> {
  const meta = unsealNodeMeta(nodeKey, currentMeta)
  if (cover) meta.cover = cover
  else delete meta.cover
  const wrappedMeta = sealNodeMeta(nodeKey, meta)
  await withRefresh(() => contentApi().updateNode({ scopeId, nodeId, updateNodeRequest: { wrappedMeta } }))
  return wrappedMeta
}

/** Whether two covers are equal (mediaId + all four crop fields) — the editor re-seals only when this is false. */
export function sameMemoryCover(a: MemoryCover | null, b: MemoryCover | null): boolean {
  if (a === b) return true
  if (!a || !b) return false
  return (
    a.mediaId === b.mediaId &&
    a.crop.x === b.crop.x &&
    a.crop.y === b.crop.y &&
    a.crop.width === b.crop.width &&
    a.crop.height === b.crop.height
  )
}

/** A resolved cover ready to display: a decrypted thumbnail object URL for the chosen media + its crop. */
export type LoadedCover = { url: string; crop: CoverCrop }

/**
 * Resolve a node's chosen cover for the browse card + map pin: unseal `cover` under `nodeKey` (`K_mem`), then
 * decrypt the chosen media's **THUMBNAIL** tier on device (`listMedia` → `openGrid`, the same operator-blind path
 * the gallery uses — ciphertext + K_blob wraps, no plaintext crosses). Returns the object URL + crop, or **null**
 * when the node has no cover, the chosen media is gone (E2 — a dangling `mediaId` ⇒ placeholder), or any decrypt
 * fails. **The caller owns the URL and must `revokeObjectURL` it.** (Retires the cr-0071 `pinCover` nearest-media
 * stopgap — the pin now shows the same explicit cover as the card.)
 */
export async function loadNodeCover(
  scopeId: string,
  nodeKey: Uint8Array,
  node: { id: string; wrappedMeta: string | null },
): Promise<LoadedCover | null> {
  const cover = unsealMemoryCover(nodeKey, node)
  if (!cover) return null
  try {
    const media = await listMedia(scopeId, node.id)
    const pick = media.find((m) => m.mediaId === cover.mediaId && hasDerivatives(m))
    if (!pick) return null // E2: the chosen media was deleted ⇒ fall back to the placeholder, never a broken tile
    const bytes = await openGrid(scopeId, nodeKey, pick)
    return { url: URL.createObjectURL(new Blob([bytes as BlobPart])), crop: cover.crop }
  } catch {
    return null // best-effort: locked blob, network hiccup, malformed tier ⇒ placeholder
  }
}

/** The `<img>` styles produced by [coverImageStyle] — mirrors the design source's inline style object. */
export type CoverImageStyle = {
  width: string
  height: string
  left: string
  top: string
  objectFit: 'cover' | 'fill'
}

/**
 * The pure display formula (ported from the design source's `MemoryCoverImage`): given a normalized crop, return
 * the `<img>` styles that show exactly that crop inside an `overflow:hidden` box **at any container size** — the
 * image is scaled to `1/width × 1/height` of the box and offset so the crop's top-left aligns with the box.
 * `objectFit: 'fill'` preserves the exact crop (the box is a matching 16:9). No crop ⇒ a plain `object-fit: cover`
 * full-frame fill. The same helper drives the card, the detail header, and the map pin, so a crop looks identical
 * everywhere.
 */
export function coverImageStyle(crop: CoverCrop | null | undefined): CoverImageStyle {
  if (!crop) return { width: '100%', height: '100%', left: '0', top: '0', objectFit: 'cover' }
  return {
    width: `${100 / crop.width}%`,
    height: `${100 / crop.height}%`,
    left: `${(-crop.x / crop.width) * 100}%`,
    top: `${(-crop.y / crop.height) * 100}%`,
    objectFit: 'fill',
  }
}
