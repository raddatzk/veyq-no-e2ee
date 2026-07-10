// uc.sharing.share-public-viewer (web) — the public viewer share data layer (cr-0100, ADR 0029). Operator-blind:
// a curator turns ONE personal-scope Memory into a read-only, anonymous, revocable link `/s/{shareId}#k=<K_share>`.
// The share adds an INDEPENDENT second wrap of that Memory's `K_mem` — `symWrap(K_share, K_mem)` (ADR 0012) —
// whose secret `K_share` rides the URL **fragment** (ADR 0015) and never reaches the server. The Memory name is
// re-sealed under `K_share` so a viewer with only the fragment key can show the title without `K_SC`.
//
// Two sides live here:
//  - **curator** (authed): mint / list / revoke a share.
//  - **viewer** (anonymous, no session): resolve a share, unwrap `K_mem` from the fragment key, derive each
//    chapter's `K_mem` down the subtree, and open blobs via the gated share-scoped presign endpoint.
import * as v1 from '../../shared/crypto/v1'
import { b64std, fromB64std, b64url, fromB64url } from '../../shared/base64'
import { sharesApi, publicSharesApi } from '../../api/client'
import { withRefresh } from '../account/login/login'
import { openName, sealName } from './tree'
import {
  gridRef,
  lightboxRef,
  downloadRef,
  openBlobFromUrls,
  uploadMediaVia,
  createBrowserDeriver,
  type BlobRef,
  type MediaItem,
  type MediaUploadTransport,
  type AddMediaSource,
  type AddMediaOptions,
  type MediaDeriver,
} from './media'
import { ShareRole, type PublicShare, type ShareMedia, type ShareNode } from '../../api/generated/sharing'
import type { MediaDerivativeKind } from '../../api/generated/media'

// The AAD context binding `symWrap(K_share, K_mem)` — a dedicated domain tag for the share second-wrap, distinct
// from the tree's `content-key` (parent → K_mem) wrap. Sealed by the curator, opened by the viewer; both here.
const SHARE_CTX = 'share-key'
// The AAD context for a contributor's display name sealed under K_mem (cr-0101) — the operator never sees the name.
const SHARE_IDENTITY_NAME_CTX = 'share-identity-name'
// cr-0115 (ADR 0036): the AAD contexts for the editable curator note. `share-note` binds the note text under
// `K_share`; `share-key-recovery` binds the recoverable copy of `K_share` under `K_mem`. Distinct tags, never reused.
const SHARE_NOTE_CTX = 'share-note'
const SHARE_KEY_RECOVERY_CTX = 'share-key-recovery'
const noteDecoder = new TextDecoder()

/** Seal a curator note under `K_share` (opaque base64) — the share page opens it with the fragment key. */
function sealNote(kShare: Uint8Array, note: string): string {
  return b64std(v1.symWrap(kShare, v1.utf8(note), SHARE_NOTE_CTX, v1.randomBytes(12)))
}
/** Open a note sealed under `K_share` (the counterpart to {@link sealNote}). */
function openNote(kShare: Uint8Array, sealed: string): string {
  return noteDecoder.decode(v1.symUnwrap(kShare, fromB64std(sealed), SHARE_NOTE_CTX))
}
/** Recover `K_share` from its `K_mem`-sealed copy `symWrap(K_mem, K_share)` — the curator always holds `K_mem`. */
export function recoverShareKey(memoryKey: Uint8Array, sealedShareKey: string): Uint8Array {
  return v1.symUnwrap(memoryKey, fromB64std(sealedShareKey), SHARE_KEY_RECOVERY_CTX)
}
/** A managed share's current note, decrypted (recover `K_share` via `K_mem`, then open the note). '' when there is
 *  none or the ciphertext can't be opened (a share minted before cr-0115 carries no recoverable key). */
export function readShareNote(memoryKey: Uint8Array, sealedShareKey: string | null, sealedNote: string | null): string {
  if (!sealedShareKey || !sealedNote) return ''
  try {
    return openNote(recoverShareKey(memoryKey, sealedShareKey), sealedNote)
  } catch {
    return ''
  }
}

// --- curator (authenticated) -----------------------------------------------

/** A newly minted share: the opaque id + the full copyable link (with the fragment key, assembled client-side). */
export type MintedShare = { shareId: string; link: string }

/** Build the copyable link `/s/{shareId}#k=<base64url(K_share)>` — the fragment carries `K_share`, never sent. */
export function buildShareLink(shareId: string, kShare: Uint8Array): string {
  return `${window.location.origin}/s/${shareId}#k=${b64url(kShare)}`
}

/**
 * Mint a public viewer share of one Memory (curator side). Generates a fresh 32-byte `K_share`, wraps the
 * Memory's `K_mem` under it, re-seals the Memory name under `K_share`, and POSTs the opaque record. Returns the
 * `shareId` + the assembled link. The server never sees `K_share` (it lives only in the returned link's fragment).
 */
export async function createShare(
  nodeId: string,
  memoryKey: Uint8Array,
  memoryName: string,
  role: ShareRole = ShareRole.Viewer,
  expiresAt: Date | null = null,
  note = '',
): Promise<MintedShare> {
  const kShare = v1.randomBytes(32)
  const wrap = b64std(v1.symWrap(kShare, memoryKey, SHARE_CTX, v1.randomBytes(12)))
  const sealedName = sealName(kShare, memoryName) // the Memory name re-sealed under K_share (same node-name construction)
  // cr-0115 (ADR 0036): store a recoverable copy of K_share under K_mem (so the curator can edit the note later)
  // and seal the optional note under K_share for the share page. sealedShareKey is stored on EVERY share, so a
  // note can be added later even to one minted without one.
  const sealedShareKey = b64std(v1.symWrap(memoryKey, kShare, SHARE_KEY_RECOVERY_CTX, v1.randomBytes(12)))
  const sealedNote = note.trim() ? sealNote(kShare, note) : undefined
  const { shareId } = await withRefresh(() =>
    sharesApi().createShare({
      createShareRequest: { nodeId, role, wrap, sealedName, expiresAt: expiresAt ?? undefined, sealedNote, sealedShareKey },
    }),
  )
  return { shareId, link: buildShareLink(shareId, kShare) }
}

/**
 * Edit a share's curator note in place (cr-0115, ADR 0036): recover `K_share` from its `K_mem`-sealed copy, re-seal
 * the new note (or clear it when blank), and PATCH — the same link keeps working. Owner-gated server-side.
 */
export async function updateShareNote(shareId: string, memoryKey: Uint8Array, sealedShareKey: string, note: string): Promise<void> {
  const kShare = recoverShareKey(memoryKey, sealedShareKey)
  const sealedNote = note.trim() ? sealNote(kShare, note) : null
  await withRefresh(() => sharesApi().updateShare({ shareId, updateShareRequest: { sealedNote } }))
}

/** A curator's management row for one of their shares (opaque; the sealed name isn't shown — the Memory is known). */
export type ManagedShare = {
  shareId: string
  nodeId: string
  role: ShareRole
  enabled: boolean
  /** True when the caller owns this share (so may revoke it). A disabled share by any member is adoptable (cr-0104). */
  mine: boolean
  createdAt: Date
  expiresAt: Date | null
  /** cr-0115: the note ciphertext (under `K_share`) + the recoverable `K_share` (under `K_mem`). The dialog reads
   *  the current note via `readShareNote` and edits it via `updateShareNote`. Null on a pre-cr-0115 share. */
  sealedNote: string | null
  sealedShareKey: string | null
}

/** List every share on one Memory visible to a scope member (cr-0104) — the manage-shares panel. Scoped by
 *  `nodeId` server-side, so it includes co-members' shares (each tagged `mine`), not only the caller's own. */
export async function listSharesFor(nodeId: string): Promise<ManagedShare[]> {
  const rows = await withRefresh(() => sharesApi().listShares({ nodeId }))
  return rows
    .map((r) => ({
      shareId: r.shareId,
      nodeId: r.nodeId,
      role: r.role,
      enabled: r.enabled,
      mine: r.mine,
      createdAt: new Date(r.createdAt),
      expiresAt: r.expiresAt ? new Date(r.expiresAt) : null,
      sealedNote: r.sealedNote ?? null,
      sealedShareKey: r.sealedShareKey ?? null,
    }))
    .sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime())
}

/** Revoke a share (soft — the next guest request 404s). */
export async function revokeShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().revokeShare({ shareId }))
}

/** Re-activate a soft-revoked share and adopt its ownership (cr-0104) — any current member may take over an
 *  orphaned (disabled) share on a shared Memory; the guest link then resolves again, now owned by the caller. */
export async function reactivateShare(shareId: string): Promise<void> {
  await withRefresh(() => sharesApi().reactivateShare({ shareId }))
}

// --- contributor (anonymous, no session; cr-0101) --------------------------

/** A named contributor session on a CONTRIBUTOR share: the anonymous identity id + its capability token. */
export type ContributorSession = { identityId: string; contributorToken: string }

/**
 * Name yourself on a CONTRIBUTOR share (operator-blind): seal the display name under `K_mem` and create an
 * anonymous share identity. Returns the identity id + a capability token that authorizes this session's uploads.
 * The server never sees the name (only the ciphertext) or the person.
 */
export async function createContributorIdentity(
  shareId: string,
  memoryKey: Uint8Array,
  displayName: string,
): Promise<ContributorSession> {
  const sealedDisplayName = b64std(v1.symWrap(memoryKey, v1.utf8(displayName), SHARE_IDENTITY_NAME_CTX, v1.randomBytes(12)))
  const { identityId, contributorToken } = await publicSharesApi().createShareIdentity({
    shareId,
    createShareIdentityRequest: { sealedDisplayName },
  })
  return { identityId, contributorToken }
}

/** The share-scoped upload transport (no auth) — reserve via `startShareBlobUploads`, complete via `completeShareMedia`. */
function shareUploadTransport(shareId: string, contributorToken: string): MediaUploadTransport {
  return {
    async reserve(declares) {
      const started = await publicSharesApi().startShareBlobUploads({
        shareId,
        shareBlobUploadsRequest: { contributorToken, items: declares },
      })
      if (started.results.length !== declares.length) throw new Error('upload_failed')
      return started.results.map((r) => {
        if (!r || r.error) throw new Error(r?.error ?? 'upload_failed')
        if (!r.uploadId || !r.presignedPuts) throw new Error('store_unavailable')
        return { uploadId: r.uploadId, putUrls: [...r.presignedPuts].sort((a, b) => a.index - b.index).map((u) => u.url) }
      })
    },
    async complete(media) {
      const done = await publicSharesApi().completeShareMedia({
        shareId,
        shareMediaBatchRequest: {
          contributorToken,
          items: [{
            originalUploadId: media.originalUploadId,
            derivatives: media.derivatives.map((d) => ({ kind: d.kind, uploadId: d.uploadId })),
            wrappedMeta: media.wrappedMeta,
            wrappedExif: media.wrappedExif,
          }],
        },
      })
      const m = done.results[0]
      if (!m || m.error || !m.blobId || !m.mediaId) throw new Error(m?.error ?? 'attach_failed')
      return { blobId: m.blobId, mediaId: m.mediaId }
    },
  }
}

/**
 * Add one photo to a CONTRIBUTOR share (anonymous): seal it under `K_mem` (which the contributor holds) with its
 * derivatives + metadata and upload it via the share-scoped endpoints — the exact same operator-blind pipeline as
 * the curator's `addMedia`, only the transport is share-gated. Returns the new blob + media ids.
 */
export async function addSharePhoto(
  session: ContributorSession & { shareId: string; memoryKey: Uint8Array },
  source: AddMediaSource,
  deriver: MediaDeriver = createBrowserDeriver(),
  opts: AddMediaOptions = {},
): Promise<{ blobId: string; mediaId: string }> {
  return uploadMediaVia(session.memoryKey, source, deriver, shareUploadTransport(session.shareId, session.contributorToken), opts)
}

/**
 * Delete one of your own contributions on a CONTRIBUTOR/UPLOADER share (anonymous, token-authorized). The server
 * authorizes it by a single-row ownership check (the media's `share_identity_id` equals the token's identity) and
 * reclaims its blobs — a foreign/unknown media is a 404. Used by the uploader drop-box's session-only delete-own.
 */
export async function deleteSharePhoto(shareId: string, contributorToken: string, mediaId: string): Promise<void> {
  await publicSharesApi().deleteShareMedia({ shareId, mediaId, xContributorToken: contributorToken })
}

// --- viewer (anonymous, no session) ----------------------------------------

/** One shared media resolved for the read-only viewer: the media + the exact `K_mem` its blobs are sealed under. */
export type ShareItem = { item: MediaItem; memoryKey: Uint8Array }

/**
 * The decrypted shared Memory, ready to render: its name, the grant `role` (VIEWER / CONTRIBUTOR), the **root**
 * `K_mem` (for a contributor to seal their name + uploads under), and every media (each with its own `K_mem`).
 */
export type ResolvedShare = { memoryName: string; note: string; role: ShareRole; rootMemoryKey: Uint8Array; items: ShareItem[] }

/** The fragment key is missing/malformed, or the share is unknown/revoked/expired (a non-enumerable 404). */
export class ShareUnavailableError extends Error {}

function shareMediaToItem(m: ShareMedia): MediaItem {
  return {
    mediaId: m.mediaId,
    original: { blobId: m.blobId, chunkCount: m.chunkCount, byteSize: m.byteSize, wrappedBlobKey: m.wrappedBlobKey },
    derivatives: (m.derivatives ?? []).map((d) => ({
      kind: d.kind as MediaDerivativeKind,
      ref: { blobId: d.blobId, chunkCount: d.chunkCount, byteSize: d.byteSize, wrappedBlobKey: d.wrappedBlobKey },
    })),
    wrappedMeta: m.wrappedMeta ?? undefined,
  }
}

/**
 * Build the per-node `K_mem` map for a shared subtree: the root Memory's `K_mem` comes from the share `wrap`
 * (unwrapped with the fragment key), each chapter's from its `wrappedContentKey` under its parent's `K_mem`
 * (recursive `K_mem`, ADR 0012). Nodes whose chain can't be opened are dropped.
 */
function deriveSubtreeKeys(nodes: ShareNode[], rootKmem: Uint8Array): Map<string, Uint8Array> {
  const keys = new Map<string, Uint8Array>()
  const byParent = new Map<string, ShareNode[]>()
  let root: ShareNode | undefined
  for (const n of nodes) {
    if (n.parentId == null) root = n
    else byParent.set(n.parentId, [...(byParent.get(n.parentId) ?? []), n])
  }
  if (!root) return keys
  keys.set(root.id, rootKmem)
  // BFS from the root, deriving each child's K_mem under its parent's (already-known) K_mem.
  const queue: string[] = [root.id]
  while (queue.length) {
    const parentId = queue.shift()!
    const parentKey = keys.get(parentId)
    if (!parentKey) continue
    for (const child of byParent.get(parentId) ?? []) {
      if (!child.wrappedContentKey) continue
      try {
        keys.set(child.id, v1.symUnwrap(parentKey, fromB64std(child.wrappedContentKey), 'content-key'))
        queue.push(child.id)
      } catch {
        /* a chapter whose K_mem won't open is skipped (its media then simply won't render) */
      }
    }
  }
  return keys
}

/**
 * Resolve + decrypt a public share for the anonymous viewer. Fetches the sealed subtree (no auth), unwraps the
 * Memory's `K_mem` with the fragment key, decrypts the Memory name, derives every chapter's `K_mem`, and returns
 * a flat, read-only list of media (each tagged with the exact `K_mem` its blobs open under). Throws
 * [ShareUnavailableError] on a missing/bad fragment key or a 404 (unknown/revoked/expired — indistinguishable).
 */
export async function resolveShare(shareId: string, fragmentKey: string): Promise<ResolvedShare> {
  let kShare: Uint8Array
  try {
    kShare = fromB64url(fragmentKey)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key')
  }
  if (kShare.length !== 32) throw new ShareUnavailableError('bad_fragment_key')

  let share: PublicShare
  try {
    share = await publicSharesApi().resolveShare({ shareId })
  } catch {
    throw new ShareUnavailableError('not_found') // 404 (revoked/expired/unknown) or a network error
  }

  let rootKmem: Uint8Array
  let memoryName: string
  try {
    rootKmem = v1.symUnwrap(kShare, fromB64std(share.wrap), SHARE_CTX)
    memoryName = openName(kShare, share.sealedName)
  } catch {
    throw new ShareUnavailableError('bad_fragment_key') // the id was valid but the fragment key doesn't match
  }
  // cr-0115: the optional curator note, opened with the same fragment key (a bad/absent one just leaves it empty).
  let note = ''
  if (share.sealedNote) {
    try {
      note = openNote(kShare, share.sealedNote)
    } catch {
      /* an unreadable note is non-fatal — the share still renders without it */
    }
  }

  const keys = deriveSubtreeKeys(share.nodes, rootKmem)
  const items: ShareItem[] = []
  for (const m of share.media) {
    const memoryKey = keys.get(m.nodeId)
    if (!memoryKey) continue // a media under a chapter whose K_mem didn't open — skip
    items.push({ item: shareMediaToItem(m), memoryKey })
  }
  return { memoryName, note, role: share.role, rootMemoryKey: rootKmem, items }
}

/** Open a shared blob via the gated, share-scoped presign endpoint (no auth), then decrypt it under its `K_mem`. */
async function openShareBlob(shareId: string, memoryKey: Uint8Array, ref: BlobRef): Promise<Uint8Array> {
  const dl = await publicSharesApi().resolveShareBlob({ shareId, blobId: ref.blobId })
  const getUrls = [...dl.presignedGets].sort((a, b) => a.index - b.index).map((u) => u.url)
  return openBlobFromUrls(memoryKey, ref, getUrls)
}

/** Open a shared media's grid (thumbnail) tier. */
export const openShareGrid = (shareId: string, si: ShareItem) => openShareBlob(shareId, si.memoryKey, gridRef(si.item))
/** Open a shared media's lightbox (preview) tier. */
export const openShareLightbox = (shareId: string, si: ShareItem) => openShareBlob(shareId, si.memoryKey, lightboxRef(si.item))
/** Open a shared media's pristine original (download). */
export const openShareOriginal = (shareId: string, si: ShareItem) => openShareBlob(shareId, si.memoryKey, downloadRef(si.item))
