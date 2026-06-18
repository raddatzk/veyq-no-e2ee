// uc.memory.share (web) — read-only public sharing, operator-blind. The owner wraps the scope content key
// under a random per-share key and stores the ciphertext; the share key rides the link FRAGMENT (/s/<id>#k=…)
// and never reaches the server. A guest resolves the share, unwraps the content key client-side, and reads
// the shared subtree's ciphertext (gated server-side to the subtree). The server stays blind throughout.
import { apiError } from '../../../shared/apiError'
import * as v1 from '../../../shared/crypto/v1'
import type { UnlockedSession } from '../../account/login/login'
import type { ActiveSpace } from '../../home/home'
import { decodeNode, type MemoryNode } from '../createTree/memoryTree'
import type { Track } from '../track/track'
import type { Playlist } from '../playlist/playlist'
import { chunkBytes, decodeMediaMeta, dedupTokenFor, sealMediaMeta, DEFAULT_CHUNK_SIZE, type BlobRef, type DerivativeKind, type MediaItem, type NewMedia } from '../../uploads/media/media'
import { galleryDisplayAspectRatio } from '../../gallery/justifiedLayout'
import type { GalleryTile } from '../../gallery/gallery'
import { mapPool } from '../../../shared/pool'
import { b64url, fromB64url } from '../../../shared/base64'

export type ShareRole = 'VIEWER' | 'CONTRIBUTOR' | 'UPLOADER'
export type ShareNotice = { scope: 'TENANT' | 'SHARE'; version: number; markdown: string }

const EMPTY = new Uint8Array(0)

export type ShareSummary = { shareId: string; nodeId: string; role: ShareRole; passwordProtected: boolean; expiresAt?: string; label: string; noticeMarkdown?: string; createdAt: string }
export type ShareEdit = { expiresAt?: string; label?: string; noticeMarkdown?: string }
export type CreateShareOptions = { role?: ShareRole; password?: string; expiresAt?: string; label?: string; noticeMarkdown?: string; origin?: string }

/** Thrown by resolvePublicShare when the share needs a password the caller didn't supply (or got wrong). */
export class SharePasswordRequired extends Error {
  constructor(public wrong: boolean) { super(wrong ? 'wrong share password' : 'share password required') }
}

// ---- owner side (authenticated) -------------------------------------------------------------------------

/** Create a share for a memory and return the public link (the share key lives in the fragment). */
export async function createShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, opts: CreateShareOptions = {}): Promise<{ shareId: string; link: string }> {
  const shareKey = v1.randomBytes(32)
  // A password folds into the unwrap key (Argon2id); only the salt goes to the server.
  let passwordSalt: { suiteId: string; bytes: string } | undefined
  let wrapKey = shareKey
  if (opts.password) {
    const salt = v1.randomBytes(16)
    wrapKey = v1.shareWrapKey(shareKey, opts.password, salt)
    passwordSalt = { suiteId: 'v1', bytes: b64url(salt) }
  }
  const nonce = v1.randomBytes(12)
  const wrapped = v1.concat(nonce, v1.aeadSeal(wrapKey, nonce, active.contentKey, EMPTY))
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrapped) }, role: opts.role ?? 'VIEWER', passwordSalt, expiresAt: opts.expiresAt, label: opts.label ?? '', noticeMarkdown: opts.noticeMarkdown }),
  })
  if (!res.ok) throw await apiError(res, 'create share')
  const { shareId } = await res.json()
  const base = opts.origin ?? (typeof window !== 'undefined' ? window.location.origin : '')
  return { shareId, link: `${base}/s/${shareId}#k=${b64url(shareKey)}` }
}

/** List a memory's shares. */
export async function listShares(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<ShareSummary[]> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'list shares')
  return (await res.json()).shares
}

/** Edit a link's editable fields (expiry/label/notice). Omit expiresAt to deactivate the expiry. */
export async function updateShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, shareId: string, edit: ShareEdit): Promise<ShareSummary> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares/${shareId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify(edit),
  })
  if (!res.ok) throw await apiError(res, 'update share')
  return res.json()
}

/** Revoke a share. */
export async function revokeShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, shareId: string): Promise<void> {
  const res = await fetch(`${apiBase}/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares/${shareId}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${session.sessionToken}` },
  })
  if (!res.ok) throw await apiError(res, 'revoke share')
}

// ---- guest side (no auth) -------------------------------------------------------------------------------

export type ShareContext = { apiBase: string; shareId: string; scopeId: string; contentKey: Uint8Array; rootNodeId: string; rootName: string; role: ShareRole; notices: ShareNotice[] }

// ---- tenant Terms of Service (owner) --------------------------------------------------------------------

export type TenantTerms = { tenantId: string; termsMarkdown?: string; version: number }

/** Read a tenant's ToS (Owner only). */
export async function getTenantTerms(apiBase: string, session: UnlockedSession, tenantId: string): Promise<TenantTerms> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/terms`, { headers: { authorization: `Bearer ${session.sessionToken}` } })
  if (!res.ok) throw await apiError(res, 'get terms')
  return res.json()
}

/** Set a tenant's ToS (Owner only); bumps the version so guests re-accept. */
export async function setTenantTerms(apiBase: string, session: UnlockedSession, tenantId: string, termsMarkdown: string): Promise<TenantTerms> {
  const res = await fetch(`${apiBase}/api/tenants/${tenantId}/terms`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${session.sessionToken}` },
    body: JSON.stringify({ termsMarkdown }),
  })
  if (!res.ok) throw await apiError(res, 'set terms')
  return res.json()
}

/** Resolve a public share: unwrap the content key with the fragment share key (+ password if protected). */
export async function resolvePublicShare(apiBase: string, shareId: string, shareKeyB64: string, password?: string): Promise<ShareContext> {
  const res = await fetch(`${apiBase}/api/public/shares/${shareId}`)
  if (!res.ok) throw await apiError(res, 'resolve share')
  const body = await res.json()
  const fragKey = fromB64url(shareKeyB64)
  // Password-protected: fold Argon2id(password, salt) into the unwrap key. No password → prompt for one.
  let wrapKey = fragKey
  if (body.passwordProtected) {
    if (!password) throw new SharePasswordRequired(false)
    wrapKey = v1.shareWrapKey(fragKey, password, fromB64url(body.passwordSalt.bytes))
  }
  const wrapped = fromB64url(body.wrappedContentKey.bytes)
  let contentKey: Uint8Array
  try {
    contentKey = v1.aeadOpen(wrapKey, wrapped.slice(0, 12), wrapped.slice(12), EMPTY)
  } catch {
    if (body.passwordProtected) throw new SharePasswordRequired(true) // AEAD failure = wrong password
    throw new Error('could not open share')
  }
  // A blind UPLOADER gets no root node (it must not learn metadata); only readers see the album name.
  const rootName = body.root ? decodeNode(contentKey, body.root as MemoryNode).name : ''
  return { apiBase, shareId, scopeId: body.scopeId, contentKey, rootNodeId: body.rootNodeId, rootName, role: body.role, notices: body.notices ?? [] }
}

/** The shared subtree's nodes (ciphertext). */
export async function listShareNodes(ctx: ShareContext): Promise<MemoryNode[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes`)
  if (!res.ok) throw await apiError(res, 'share nodes')
  return (await res.json()).nodes
}

/** A shared node's media (ciphertext). */
export async function listShareMedia(ctx: ShareContext, nodeId: string): Promise<MediaItem[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes/${nodeId}/media`)
  if (!res.ok) throw await apiError(res, 'share media')
  return (await res.json()).media
}

/** A shared node's GPX tracks (ciphertext), decrypted to {name, points} routes. Read-only over a share. */
export async function getShareTracks(ctx: ShareContext, nodeId: string): Promise<Track[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes/${nodeId}/tracks`)
  if (!res.ok) throw await apiError(res, 'share tracks')
  const { tracks } = await res.json()
  return tracks.map((t: { id: string; sealedContent: { bytes: string } }) => {
    const sealed = fromB64url(t.sealedContent.bytes)
    const route = JSON.parse(new TextDecoder().decode(v1.aeadOpen(ctx.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)))
    return { id: t.id, name: route.name, points: route.points }
  })
}

/** A shared node's playlist links (read-only over a share). */
export async function getSharePlaylists(ctx: ShareContext, nodeId: string): Promise<Playlist[]> {
  const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/nodes/${nodeId}/playlists`)
  if (!res.ok) throw await apiError(res, 'share playlists')
  return (await res.json()).playlists.map((p: { id: string; featured: boolean; sealedContent: { bytes: string } }) => {
    const sealed = fromB64url(p.sealedContent.bytes)
    const pl = JSON.parse(new TextDecoder().decode(v1.aeadOpen(ctx.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY)))
    return { id: p.id, featured: p.featured, provider: pl.provider, url: pl.url, title: pl.title, note: pl.note } as Playlist
  })
}

/** Download + open a shared blob through the public share endpoint (frame by frame, AAD checks position). */
export async function downloadShareBlob(ctx: ShareContext, ref: BlobRef): Promise<Uint8Array> {
  // Frames are AAD-position-bound, so fetch bound-parallel and reassemble in order (bandwidth- not RTT-bound).
  const indices = Array.from({ length: ref.totalChunks }, (_, i) => i)
  const parts = await mapPool(indices, 4, async (i) => {
    const res = await fetch(`${ctx.apiBase}/api/public/shares/${ctx.shareId}/blobs/${ref.blobId}/chunks/${i}`)
    if (!res.ok) throw await apiError(res, `share chunk ${i}`)
    const frame = fromB64url((await res.json()).frame.bytes)
    return v1.openFrame(ctx.contentKey, i, ref.totalChunks, frame)
  })
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
  // Download the (small) thumbnails bound-parallel rather than one-by-one; mapPool keeps the takenAt order.
  return mapPool(withMeta, 6, async ({ m, meta }) => {
    const ref = m.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob ?? m.original
    const preview = m.derivatives.find((d) => d.kind === 'PREVIEW')?.blob ?? m.original
    const bytes = await downloadShareBlob(ctx, ref)
    const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: m.mimeType }))
    return {
      id: m.id, url, aspectRatio: await imageAspectRatio(url), mimeType: m.mimeType,
      takenAt: meta.takenAt, lat: meta.lat, lng: meta.lng, highlighted: m.highlighted, preview, original: m.original,
      createdBy: m.createdBy, bucketId: m.bucketId ?? undefined,
      cameraMake: meta.cameraMake, cameraModel: meta.cameraModel, lens: meta.lens,
      width: meta.width, height: meta.height, fileSize: meta.fileSize, exif: meta.exif,
    }
  })
}

/** Decrypt + open a shared media's PREVIEW (≈2048px) for the lightbox, as an object URL. */
export async function loadSharePreview(ctx: ShareContext, tile: GalleryTile): Promise<string> {
  const bytes = await downloadShareBlob(ctx, tile.preview)
  return URL.createObjectURL(new Blob([bytes as BlobPart], { type: tile.mimeType }))
}

/** Decrypt the FULL-resolution original (only on an explicit download), as an object URL. */
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
  if (!start.ok) throw await apiError(start, 'share start blob')
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
  if (!done.ok) throw await apiError(done, 'share complete')
  return done.json()
}

/** Seal a guest's chosen display name under the share content key (operator-blind), like a bucket name. */
export function sealShareName(ctx: ShareContext, name: string): { suiteId: string; bytes: string } {
  const nonce = v1.randomBytes(12)
  return { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(ctx.contentKey, nonce, v1.utf8(name), EMPTY))) }
}

/** Contribute a media (original + optional thumbnail) to the shared memory, sealed under the content key. When
 *  the guest has named themselves, the uploads group under their own (named, operator-blind) contribution bucket. */
export async function uploadShareMedia(ctx: ShareContext, m: NewMedia, contributor?: { id: string; name: string }): Promise<void> {
  const original = await uploadShareBlob(ctx, m.bytes)
  const derivatives: { kind: DerivativeKind; blobId: string }[] = []
  if (m.thumb) derivatives.push({ kind: 'THUMBNAIL', blobId: (await uploadShareBlob(ctx, m.thumb)).blobId })
  if (m.preview) derivatives.push({ kind: 'PREVIEW', blobId: (await uploadShareBlob(ctx, m.preview)).blobId })
  const sealedMeta = sealMediaMeta(ctx.contentKey, {
    takenAt: m.takenAt, lat: m.lat, lng: m.lng,
    cameraMake: m.cameraMake, cameraModel: m.cameraModel, lens: m.lens, width: m.width, height: m.height, fileSize: m.fileSize, exif: m.exif,
  })
  const res = await fetch(`${sp(ctx)}/nodes/${ctx.rootNodeId}/contributed-media`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      originalBlobId: original.blobId, sealedMeta, mimeType: m.mimeType, derivatives,
      ...(contributor ? { contributorId: contributor.id, contributorName: sealShareName(ctx, contributor.name) } : {}),
    }),
  })
  if (!res.ok) throw await apiError(res, 'contribute media')
}

/** Read a shared node's journal Markdown ('' when none). Read-only over a share — only the owner edits. */
export async function getShareJournal(ctx: ShareContext, nodeId: string = ctx.rootNodeId): Promise<string> {
  const res = await fetch(`${sp(ctx)}/nodes/${nodeId}/journal`)
  if (!res.ok) throw await apiError(res, 'share journal')
  const body = await res.json()
  if (!body.sealedContent) return ''
  const sealed = fromB64url(body.sealedContent.bytes)
  return new TextDecoder().decode(v1.aeadOpen(ctx.contentKey, sealed.slice(0, 12), sealed.slice(12), EMPTY))
}

export { decodeMediaMeta }
