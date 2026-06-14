// Gallery tiles for a memory: download each media's thumbnail (small, 1-frame blob), decrypt to an object
// URL, and derive its clamped display aspect ratio from the decoded image — the input the justified layout
// needs. (The greenfield media model stores no dimensions, so the ratio comes from the bytes we load anyway.)
import type { UnlockedSession } from '../account/login/login'
import type { ActiveSpace } from '../home/home'
import { downloadBlob, listMedia, type BlobRef } from '../uploads/media/media'
import { galleryDisplayAspectRatio } from './justifiedLayout'

export type GalleryTile = { id: string; url: string; aspectRatio: number; mimeType: string; original: BlobRef }

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
  const tiles: GalleryTile[] = []
  for (const m of media) {
    const ref = m.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob ?? m.original
    const bytes = await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, ref)
    const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: m.mimeType }))
    tiles.push({ id: m.id, url, aspectRatio: await imageAspectRatio(url), mimeType: m.mimeType, original: m.original })
  }
  return tiles
}

/** Decrypt and open a media's full-resolution original (lightbox), as an object URL. */
export async function loadOriginal(apiBase: string, session: UnlockedSession, active: ActiveSpace, tile: GalleryTile): Promise<string> {
  const bytes = await downloadBlob(apiBase, session, active.scope.scopeId, active.contentKey, tile.original)
  return URL.createObjectURL(new Blob([bytes as BlobPart], { type: tile.mimeType }))
}
