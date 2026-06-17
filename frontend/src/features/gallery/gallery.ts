// Gallery tiles for a memory: download each media's thumbnail (small, 1-frame blob), decrypt to an object
// URL, and derive its clamped display aspect ratio from the decoded image — the input the justified layout
// needs. (The greenfield media model stores no dimensions, so the ratio comes from the bytes we load anyway.)
import type { UnlockedSession } from '../account/login/login'
import type { ActiveSpace } from '../home/home'
import { decodeMediaMeta, downloadBlob, listMedia, type BlobRef } from '../uploads/media/media'
import { mapPool } from '../../shared/pool'
import { galleryDisplayAspectRatio } from './justifiedLayout'

// `preview` (≈2048px) backs the lightbox; `original` (full-res) is only fetched on an explicit download.
// Both fall back to the original when a medium has no such derivative (older media, videos).
export type GalleryTile = { id: string; url: string; aspectRatio: number; mimeType: string; takenAt: string; lat?: number; lng?: number; highlighted: boolean; preview: BlobRef; original: BlobRef }

function imageAspectRatio(url: string): Promise<number> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = () => resolve(galleryDisplayAspectRatio(img.naturalWidth, img.naturalHeight))
    img.onerror = () => resolve(galleryDisplayAspectRatio())
    img.src = url
  })
}

/** Load a memory's media as gallery tiles (thumbnail URL + clamped aspect ratio), ordered by takenAt. */
export async function loadGalleryTiles(apiBase: string, session: UnlockedSession, active: ActiveSpace, memoryNodeId: string): Promise<GalleryTile[]> {
  const media = await listMedia(apiBase, session, active.scope.scopeId, memoryNodeId)
  // Sort by decrypted capture time (the server lists in upload order and is blind to takenAt).
  const withMeta = media.map((m) => ({ m, meta: decodeMediaMeta(active.contentKey, m) }))
  withMeta.sort((a, b) => a.meta.takenAt.localeCompare(b.meta.takenAt))
  // Download the (small) thumbnails bound-parallel rather than one-by-one; mapPool keeps the takenAt order.
  return mapPool(withMeta, 6, async ({ m, meta }) => {
    const ref = m.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob ?? m.original
    const preview = m.derivatives.find((d) => d.kind === 'PREVIEW')?.blob ?? m.original
    const bytes = await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, ref)
    const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: m.mimeType }))
    return { id: m.id, url, aspectRatio: await imageAspectRatio(url), mimeType: m.mimeType, takenAt: meta.takenAt, lat: meta.lat, lng: meta.lng, highlighted: m.highlighted, preview, original: m.original }
  })
}

/** Decrypt and open a media's PREVIEW (≈2048px) for the lightbox, as an object URL. */
export async function loadPreview(apiBase: string, session: UnlockedSession, active: ActiveSpace, tile: GalleryTile): Promise<string> {
  const bytes = await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, tile.preview)
  return URL.createObjectURL(new Blob([bytes as BlobPart], { type: tile.mimeType }))
}

/** Decrypt the FULL-resolution original (only on an explicit download), as an object URL. */
export async function loadOriginal(apiBase: string, session: UnlockedSession, active: ActiveSpace, tile: GalleryTile): Promise<string> {
  const bytes = await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, tile.original)
  return URL.createObjectURL(new Blob([bytes as BlobPart], { type: tile.mimeType }))
}
