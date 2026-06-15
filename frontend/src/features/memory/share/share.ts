// uc.memory.share (web) — read-only public sharing, operator-blind. The owner wraps the scope content key
// under a random per-share key and stores the ciphertext; the share key rides the link FRAGMENT (/s/<id>#k=…)
// and never reaches the server. A guest resolves the share, unwraps the content key client-side, and reads
// the shared subtree's ciphertext (gated server-side to the subtree). The server stays blind throughout.
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { decodeNode, type MemoryNode } from '../createTree/memoryTree'
import { decodeMediaMeta, type BlobRef, type MediaItem } from '../../uploads/media/media'
import { galleryDisplayAspectRatio } from '../../gallery/justifiedLayout'
import type { GalleryTile } from '../../gallery/gallery'

const EMPTY = new Uint8Array(0)

function b64url(b: Uint8Array): string {
  let s = ''
  for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i])
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(b.length)
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i)
  return out
}

export type ShareSummary = { shareId: string; nodeId: string; createdAt: string }

// ---- owner side (authenticated) -------------------------------------------------------------------------

/** Create a read-only share for a memory and return the public link (the share key lives in the fragment). */
export async function createShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, origin?: string): Promise<{ shareId: string; link: string }> {
  const shareKey = v1.randomBytes(32)
  const nonce = v1.randomBytes(12)
  const wrapped = v1.concat(nonce, v1.aeadSeal(shareKey, nonce, active.contentKey, EMPTY))
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrapped) } }),
  })
  if (!res.ok) throw new Error(`create share failed: ${res.status} ${await res.text()}`)
  const { shareId } = await res.json()
  const base = origin ?? (typeof window !== 'undefined' ? window.location.origin : '')
  return { shareId, link: `${base}/s/${shareId}#k=${b64url(shareKey)}` }
}

/** List a memory's shares. */
export async function listShares(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<ShareSummary[]> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw new Error(`list shares failed: ${res.status} ${await res.text()}`)
  return (await res.json()).shares
}

/** Revoke a share. */
export async function revokeShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, shareId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares/${shareId}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${session.sessionToken}` },
  })
  if (!res.ok) throw new Error(`revoke share failed: ${res.status} ${await res.text()}`)
}

// ---- guest side (no auth) -------------------------------------------------------------------------------

export type ShareContext = { apiBase: string; shareId: string; contentKey: Uint8Array; rootNodeId: string; rootName: string }

/** Resolve a public share: unwrap the content key with the fragment share key and decode the root title. */
export async function resolvePublicShare(apiBase: string, shareId: string, shareKeyB64: string): Promise<ShareContext> {
  const res = await fetch(`${apiBase}/api/public/shares/${shareId}`)
  if (!res.ok) throw new Error(`resolve share failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  const shareKey = fromB64url(shareKeyB64)
  const wrapped = fromB64url(body.wrappedContentKey.bytes)
  const contentKey = v1.aeadOpen(shareKey, wrapped.slice(0, 12), wrapped.slice(12), EMPTY)
  const rootName = decodeNode(contentKey, body.root as MemoryNode).name
  return { apiBase, shareId, contentKey, rootNodeId: body.rootNodeId, rootName }
}

/** The shared subtree's nodes (ciphertext). */
export async function listShareNodes(ctx: ShareContext): Promise<MemoryNode[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes`)
  if (!res.ok) throw new Error(`share nodes failed: ${res.status} ${await res.text()}`)
  return (await res.json()).nodes
}

/** A shared node's media (ciphertext). */
export async function listShareMedia(ctx: ShareContext, nodeId: string): Promise<MediaItem[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes/${nodeId}/media`)
  if (!res.ok) throw new Error(`share media failed: ${res.status} ${await res.text()}`)
  return (await res.json()).media
}

/** Download + open a shared blob through the public share endpoint (frame by frame, AAD checks position). */
export async function downloadShareBlob(ctx: ShareContext, ref: BlobRef): Promise<Uint8Array> {
  const parts: Uint8Array[] = []
  for (let i = 0; i < ref.totalChunks; i++) {
    const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/blobs/${ref.blobId}/chunks/${i}`)
    if (!res.ok) throw new Error(`share chunk ${i} failed: ${res.status} ${await res.text()}`)
    const frame = fromB64url((await res.json()).frame.bytes)
    parts.push(v1.openFrame(ctx.contentKey, i, ref.totalChunks, frame))
  }
  return v1.concat(...parts)
}

function imageAspectRatio(url: string): Promise<number> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = () => resolve(galleryDisplayAspectRatio(img.naturalWidth, img.naturalHeight))
    img.onerror = () => resolve(galleryDisplayAspectRatio())
    img.src = url
  })
}

/** Load a shared node's media as gallery tiles (thumbnail URL + ratio + decrypted takenAt/lat/lng), sorted. */
export async function loadShareTiles(ctx: ShareContext, nodeId: string): Promise<GalleryTile[]> {
  const media = await listShareMedia(ctx, nodeId)
  const withMeta = media.map((m) => ({ m, meta: decodeMediaMeta(ctx.contentKey, m) }))
  withMeta.sort((a, b) => a.meta.takenAt.localeCompare(b.meta.takenAt))
  const tiles: GalleryTile[] = []
  for (const { m, meta } of withMeta) {
    const ref = m.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob ?? m.original
    const bytes = await downloadShareBlob(ctx, ref)
    const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: m.mimeType }))
    tiles.push({ id: m.id, url, aspectRatio: await imageAspectRatio(url), mimeType: m.mimeType, takenAt: meta.takenAt, lat: meta.lat, lng: meta.lng, original: m.original })
  }
  return tiles
}

/** Decrypt + open a shared media's full-resolution original (lightbox), as an object URL. */
export async function loadShareOriginal(ctx: ShareContext, tile: GalleryTile): Promise<string> {
  const bytes = await downloadShareBlob(ctx, tile.original)
  return URL.createObjectURL(new Blob([bytes as BlobPart], { type: tile.mimeType }))
}

export { decodeMediaMeta }
