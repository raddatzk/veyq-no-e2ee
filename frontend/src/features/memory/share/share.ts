// uc.sharing.share-public-viewer (web) — read-only public sharing, operator-blind. The owner wraps the scope content key
// under a random per-share key and stores the ciphertext; the share key rides the link FRAGMENT (/s/<id>#k=…)
// and never reaches the server. A guest resolves the share, unwraps the content key client-side, and reads
// the shared subtree's ciphertext (gated server-side to the subtree). The server stays blind throughout.
import { apiError } from '../../../shared/apiError'
import { authFetch } from '../../../shared/api/sessionFetch'
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
  const uploadToken = v1.shareUploadToken(wrapKey)
  const nonce = v1.randomBytes(12)
  const wrapped = v1.concat(nonce, v1.aeadSeal(wrapKey, nonce, active.contentKey, EMPTY))
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      wrappedContentKey: { suiteId: 'v1', bytes: b64url(wrapped) },
      uploadCapabilityHash: { suiteId: 'v1', bytes: b64url(v1.sha256Digest(uploadToken)) },
      role: opts.role ?? 'VIEWER',
      passwordSalt,
      expiresAt: opts.expiresAt,
      label: opts.label ?? '',
      noticeMarkdown: opts.noticeMarkdown,
    }),
  })
  if (!res.ok) throw await apiError(res, 'create share')
  const { shareId } = await res.json()
  const base = opts.origin ?? (typeof window !== 'undefined' ? window.location.origin : '')
  return { shareId, link: `${base}/s/${shareId}#k=${b64url(shareKey)}` }
}

/** List a memory's shares. */
export async function listShares(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string): Promise<ShareSummary[]> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares`)
  if (!res.ok) throw await apiError(res, 'list shares')
  return (await res.json()).shares
}

/** Edit a link's editable fields (expiry/label/notice). Omit expiresAt to deactivate the expiry. */
export async function updateShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, shareId: string, edit: ShareEdit): Promise<ShareSummary> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares/${shareId}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(edit),
  })
  if (!res.ok) throw await apiError(res, 'update share')
  return res.json()
}

/** Revoke a share. */
export async function revokeShare(apiBase: string, session: UnlockedSession, active: ActiveSpace, nodeId: string, shareId: string): Promise<void> {
  const res = await authFetch(apiBase, session, `/api/scopes/${active.scope.scopeId}/nodes/${nodeId}/shares/${shareId}`, { method: 'DELETE' })
  if (!res.ok) throw await apiError(res, 'revoke share')
}

// ---- guest side (no auth) -------------------------------------------------------------------------------

export type ShareContext = { apiBase: string; shareId: string; scopeId: string; contentKey: Uint8Array; uploadToken: string; rootNodeId: string; rootName: string; role: ShareRole; notices: ShareNotice[] }

// ---- tenant Terms of Service (owner) --------------------------------------------------------------------

export type TenantTerms = { tenantId: string; termsMarkdown?: string; version: number }

/** Read a tenant's ToS (Owner only). */
export async function getTenantTerms(apiBase: string, session: UnlockedSession, tenantId: string): Promise<TenantTerms> {
  const res = await authFetch(apiBase, session, `/api/tenants/${tenantId}/terms`)
  if (!res.ok) throw await apiError(res, 'get terms')
  return res.json()
}

/** Set a tenant's ToS (Owner only); bumps the version so guests re-accept. */
export async function setTenantTerms(apiBase: string, session: UnlockedSession, tenantId: string, termsMarkdown: string): Promise<TenantTerms> {
  const res = await authFetch(apiBase, session, `/api/tenants/${tenantId}/terms`, {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
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
  // A blind UPLOADER gets no root node (it must not learn metadata); only readers see the Memory name.
  const rootName = body.root ? decodeNode(contentKey, body.root as MemoryNode).name : ''
  return { apiBase, shareId, scopeId: body.scopeId, contentKey, uploadToken: b64url(v1.shareUploadToken(wrapKey)), rootNodeId: body.rootNodeId, rootName, role: body.role, notices: body.notices ?? [] }
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

// Guest transport is direct-to-store too (ADR 0021): the share endpoints only presign; the frames travel
// guest↔store. The store is blind, so a presigned URL over a share's subtree-gated blob discloses nothing.
type SharePresignedChunks = { blobId: string; suiteId: string; chunks: { index: number; url: string }[] }

/** ONE round-trip to presign GET URLs for many shared blobs (subtree-gated); keyed by blobId, missing absent. */
async function shareBatchPresignGet(ctx: ShareContext, blobIds: string[]): Promise<Map<string, SharePresignedChunks>> {
  const res = await fetch(`${sp(ctx)}/blob-downloads-batch`, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ blobIds }),
  })
  if (!res.ok) throw await apiError(res, 'share batch presign')
  return new Map(((await res.json()).results as SharePresignedChunks[]).map((r) => [r.blobId, r]))
}

/** Fetch one presigned shared blob directly from the store, open each frame (AAD checks position), reassemble. */
async function fetchShareBlobFrames(ctx: ShareContext, ref: BlobRef, presigned: SharePresignedChunks): Promise<Uint8Array> {
  if (presigned.chunks.length !== ref.totalChunks) throw new Error(`share download unavailable for ${ref.blobId}`)
  const urls = new Map(presigned.chunks.map((c) => [c.index, c.url]))
  const indices = Array.from({ length: ref.totalChunks }, (_, i) => i)
  const parts = await mapPool(indices, 4, async (i) => {
    const url = urls.get(i)
    if (!url) throw new Error(`missing presigned url for chunk ${i}`)
    const r = await fetch(url)
    if (!r.ok) throw new Error(`share presigned get chunk ${i} failed: ${r.status}`)
    return v1.openFrame(ctx.contentKey, i, ref.totalChunks, new Uint8Array(await r.arrayBuffer()))
  })
  return v1.concat(...parts)
}

/** Download + open a shared blob directly from the store (presign + parallel GET). */
export async function downloadShareBlob(ctx: ShareContext, ref: BlobRef): Promise<Uint8Array> {
  const p = (await shareBatchPresignGet(ctx, [ref.blobId])).get(ref.blobId)
  if (!p) throw new Error(`blob ${ref.blobId} not available`)
  return fetchShareBlobFrames(ctx, ref, p)
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
  // Resolve each tile's thumbnail + preview refs, then presign the whole page's thumbnails in ONE round-trip
  // and fetch them direct + parallel from the store (no per-thumbnail backend hop / egress).
  const tiles = withMeta.map(({ m, meta }) => ({
    m, meta,
    ref: m.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob ?? m.original,
    preview: m.derivatives.find((d) => d.kind === 'PREVIEW')?.blob ?? m.original,
  }))
  const presigned = await shareBatchPresignGet(ctx, tiles.map((t) => t.ref.blobId))
  // mapPool keeps the takenAt order.
  return mapPool(tiles, 6, async ({ m, meta, ref, preview }) => {
    const p = presigned.get(ref.blobId)
    if (!p) throw new Error(`thumbnail ${ref.blobId} not available`)
    const bytes = await fetchShareBlobFrames(ctx, ref, p)
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
const uploadProofHeaders = (ctx: ShareContext): Record<string, string> => ({ 'x-veyq-share-upload-token': ctx.uploadToken })
const uploadJsonHeaders = (ctx: ShareContext): Record<string, string> => ({ 'content-type': 'application/json', ...uploadProofHeaders(ctx) })

type ShareBlobStart = { status: 'READY' | 'UPLOADING' | 'ERROR'; blobId?: string; uploadId?: string; receivedChunks?: number[]; presignedPuts?: { index: number; url: string }[]; errorCode?: string }
type ShareBlobRefInput = { uploadId?: string; blobId?: string }

/** ONE round-trip to dedup/open + presign many guest blobs (proof-gated). */
async function shareBatchStart(ctx: ShareContext, items: { dedupToken: string; totalChunks: number; byteSize: number; suiteId?: string }[]): Promise<ShareBlobStart[]> {
  const res = await fetch(`${sp(ctx)}/blob-uploads-batch`, {
    method: 'POST', headers: uploadJsonHeaders(ctx),
    body: JSON.stringify({ items }),
  })
  if (!res.ok) throw await apiError(res, 'share batch start')
  return (await res.json()).results
}

/** Seal a guest's chosen display name under the share content key (operator-blind), like a bucket name. */
export function sealShareName(ctx: ShareContext, name: string): { suiteId: string; bytes: string } {
  const nonce = v1.randomBytes(12)
  return { suiteId: 'v1', bytes: b64url(v1.concat(nonce, v1.aeadSeal(ctx.contentKey, nonce, v1.utf8(name), EMPTY))) }
}

/**
 * Contribute a media (original + optional thumbnail/preview) to the shared memory, batched + direct-to-store:
 * ONE batch-start presigns every blob, the guest PUTs each sealed frame straight to the store, and ONE
 * batch-finalize completes the uploads + attaches the medium. When the guest has named themselves, their
 * uploads group under their own (named, operator-blind) contribution bucket.
 */
export async function uploadShareMedia(ctx: ShareContext, m: NewMedia, contributor?: { id: string; name: string }): Promise<void> {
  const plan: { kind: 'original' | DerivativeKind; chunks: Uint8Array[]; bytes: Uint8Array }[] = [{ kind: 'original', chunks: chunkBytes(m.bytes, DEFAULT_CHUNK_SIZE), bytes: m.bytes }]
  if (m.thumb) plan.push({ kind: 'THUMBNAIL', chunks: chunkBytes(m.thumb, DEFAULT_CHUNK_SIZE), bytes: m.thumb })
  if (m.preview) plan.push({ kind: 'PREVIEW', chunks: chunkBytes(m.preview, DEFAULT_CHUNK_SIZE), bytes: m.preview })

  const starts = await shareBatchStart(ctx, plan.map((p) => ({ dedupToken: dedupTokenFor(ctx.contentKey, p.bytes), totalChunks: p.chunks.length, byteSize: p.bytes.length, suiteId: 'v1' })))

  const puts: { url: string; frame: Uint8Array }[] = []
  starts.forEach((s, bi) => {
    if (s.status === 'ERROR') throw new Error(`share upload start failed (${plan[bi].kind}): ${s.errorCode}`)
    if (s.status !== 'UPLOADING') return
    const have = new Set(s.receivedChunks ?? [])
    const presigned = new Map((s.presignedPuts ?? []).map((p) => [p.index, p.url]))
    const chunks = plan[bi].chunks
    for (let i = 0; i < chunks.length; i++) {
      if (have.has(i)) continue
      const url = presigned.get(i)
      if (!url) throw new Error(`direct-to-store unavailable: no upload URL for chunk ${i}`)
      puts.push({ url, frame: v1.sealFrame(ctx.contentKey, i, chunks.length, chunks[i], v1.randomBytes(12)) })
    }
  })
  await mapPool(puts, 6, async (p) => {
    const res = await fetch(p.url, { method: 'PUT', body: p.frame as BodyInit })
    if (!res.ok) throw new Error(`share presigned put failed: ${res.status}`)
  })

  const refFor = (s: ShareBlobStart): ShareBlobRefInput => (s.status === 'READY' ? { blobId: s.blobId } : { uploadId: s.uploadId })
  const derivatives = plan.slice(1).map((p, i) => ({ kind: p.kind as DerivativeKind, blob: refFor(starts[i + 1]) }))
  const sealedMeta = sealMediaMeta(ctx.contentKey, {
    takenAt: m.takenAt, lat: m.lat, lng: m.lng,
    cameraMake: m.cameraMake, cameraModel: m.cameraModel, lens: m.lens, width: m.width, height: m.height, fileSize: m.fileSize, exif: m.exif,
  })
  const res = await fetch(`${sp(ctx)}/nodes/${ctx.rootNodeId}/contributed-media-batch`, {
    method: 'POST', headers: uploadJsonHeaders(ctx),
    body: JSON.stringify({ items: [{
      original: refFor(starts[0]), derivatives, sealedMeta, mimeType: m.mimeType,
      ...(contributor ? { contributorId: contributor.id, contributorName: sealShareName(ctx, contributor.name) } : {}),
    }] }),
  })
  if (!res.ok) throw await apiError(res, 'share contribute batch')
  const r = (await res.json()).results[0]
  if (!r?.ok) throw new Error(`share contribute failed: ${r?.errorCode ?? 'unknown'}`)
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
