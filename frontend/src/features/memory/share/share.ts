// uc.memory.share (web) — read-only public sharing, operator-blind. The owner wraps the scope content key
// under a random per-share key and stores the ciphertext; the share key rides the link FRAGMENT (/s/<id>#k=…)
// and never reaches the server. A guest resolves the share, unwraps the content key client-side, and reads
// the shared subtree's ciphertext (gated server-side to the subtree). The server stays blind throughout.
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { decodeNode, type MemoryNode } from '../createTree/memoryTree'
import { chunkBytes, decodeMediaMeta, dedupTokenFor, sealMediaMeta, DEFAULT_CHUNK_SIZE, type BlobRef, type DerivativeKind, type MediaItem, type NewMedia } from '../../uploads/media/media'
import { galleryDisplayAspectRatio } from '../../gallery/justifiedLayout'
import type { GalleryTile } from '../../gallery/gallery'

export type ShareRole = 'VIEWER' | 'CONTRIBUTOR'

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

export type ShareSummary = { shareId: string; nodeId: string; role: ShareRole; createdAt: string }

// ---- owner side (authenticated) -------------------------------------------------------------------------

/** Create a share for a memory and return the public link (the share key lives in the fragment). */
export async function createShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, role: ShareRole = 'VIEWER', origin?: string): Promise<{ shareId: string; link: string }> {
  const shareKey = v1.randomBytes(32)
  const nonce = v1.randomBytes(12)
  const wrapped = v1.concat(nonce, v1.aeadSeal(shareKey, nonce, active.contentKey, EMPTY))
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrapped) }, role }),
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

export type ShareContext = { apiBase: string; shareId: string; contentKey: Uint8Array; rootNodeId: string; rootName: string; role: ShareRole }

/** Resolve a public share: unwrap the content key with the fragment share key and decode the root title. */
export async function resolvePublicShare(apiBase: string, shareId: string, shareKeyB64: string): Promise<ShareContext> {
  const res = await fetch(`${apiBase}/api/public/shares/${shareId}`)
  if (!res.ok) throw new Error(`resolve share failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  const shareKey = fromB64url(shareKeyB64)
  const wrapped = fromB64url(body.wrappedContentKey.bytes)
  const contentKey = v1.aeadOpen(shareKey, wrapped.slice(0, 12), wrapped.slice(12), EMPTY)
  const rootName = decodeNode(contentKey, body.root as MemoryNode).name
  return { apiBase, shareId, contentKey, rootNodeId: body.rootNodeId, rootName, role: body.role }
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

// ---- guest contribution (CONTRIBUTOR shares) ------------------------------------------------------------

const sp = (ctx: ShareContext) => `${ctx.apiBase}/api/public/shares/${ctx.shareId}`

/** Upload arbitrary content as a deduplicated blob via the share's guest endpoints (dedup + resume). */
async function uploadShareBlob(ctx: ShareContext, bytes: Uint8Array): Promise<BlobRef> {
  const chunks = chunkBytes(bytes, DEFAULT_CHUNK_SIZE)
  const start = await fetch(`${sp(ctx)}/blobs`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ dedupToken: dedupTokenFor(ctx.contentKey, bytes), totalChunks: chunks.length, byteSize: bytes.length }),
  })
  if (!start.ok) throw new Error(`share start blob failed: ${start.status} ${await start.text()}`)
  const ticket = await start.json()
  if (ticket.status === 'READY') return { blobId: ticket.blobId, totalChunks: ticket.totalChunks, byteSize: bytes.length }
  const uploadId = ticket.uploadId
  const have = new Set<number>((await (await fetch(`${sp(ctx)}/blob-uploads/${uploadId}`)).json()).receivedChunks)
  for (let i = 0; i < chunks.length; i++) {
    if (have.has(i)) continue
    const frame = v1.sealFrame(ctx.contentKey, i, chunks.length, chunks[i], v1.randomBytes(12))
    const put = await fetch(`${sp(ctx)}/blob-uploads/${uploadId}/chunks/${i}`, {
      method: 'PUT', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ frame: { suiteId: 'v1', bytes: b64url(frame) } }),
    })
    if (!put.ok) throw new Error(`share put chunk ${i} failed: ${put.status}`)
  }
  const done = await fetch(`${sp(ctx)}/blob-uploads/${uploadId}/complete`, { method: 'POST' })
  if (!done.ok) throw new Error(`share complete failed: ${done.status} ${await done.text()}`)
  return done.json()
}

/** Contribute a media (original + optional thumbnail) to the shared memory, sealed under the content key. */
export async function uploadShareMedia(ctx: ShareContext, m: NewMedia): Promise<void> {
  const original = await uploadShareBlob(ctx, m.bytes)
  const derivatives: { kind: DerivativeKind; blobId: string }[] = []
  if (m.thumb) derivatives.push({ kind: 'THUMBNAIL', blobId: (await uploadShareBlob(ctx, m.thumb)).blobId })
  const sealedMeta = sealMediaMeta(ctx.contentKey, { takenAt: m.takenAt, lat: m.lat, lng: m.lng })
  const res = await fetch(`${sp(ctx)}/nodes/${ctx.rootNodeId}/contributed-media`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ originalBlobId: original.blobId, sealedMeta, mimeType: m.mimeType, derivatives }),
  })
  if (!res.ok) throw new Error(`contribute media failed: ${res.status} ${await res.text()}`)
}

/** Read the shared memory's journal Markdown ('' when none). */
export async function getShareJournal(ctx: ShareContext): Promise<string> {
  const res = await fetch(`${sp(ctx)}/nodes/${ctx.rootNodeId}/journal`)
  if (!res.ok) throw new Error(`share journal failed: ${res.status} ${await res.text()}`)
  const body = await res.json()
  if (!body.sealedContent) return ''
  const sealed = fromB64url(body.sealedContent.bytes)
  return new TextDecoder().decode(v1.aeadOpen(ctx.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
}

/** Write the shared memory's journal Markdown (CONTRIBUTOR shares only). */
export async function updateShareJournal(ctx: ShareContext, markdown: string): Promise<void> {
  const nonce = v1.randomBytes(12)
  const sealed = v1.concat(nonce, v1.aeadSeal(ctx.contentKey, nonce, v1.utf8(markdown), EMPTY))
  const res = await fetch(`${sp(ctx)}/nodes/${ctx.rootNodeId}/journal`, {
    method: 'PUT', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ sealedContent: { suiteId: 'v1', bytes: b64url(sealed) } }),
  })
  if (!res.ok) throw new Error(`update share journal failed: ${res.status} ${await res.text()}`)
}

export { decodeMediaMeta }
