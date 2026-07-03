---
id: cr-0073
title: Cover from a new upload — a standalone cover blob (no media row), reusing the cr-0072 display + crop
status: planned
satisfies: [uc.memory.cover]
adrs: ["0012", "0013", "0010"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a Memory's **cover** be a **brand-new uploaded image** that is **not** one of the Memory's media — the second
image source of [uc.memory.cover](../docs/features/memory/use-cases/uc.memory.cover.md) (acceptance criterion
`cover-from-standalone-upload`). The image is committed as a **standalone cover blob**: sealed under a fresh
`K_blob` wrapped by `K_mem` (ADR 0012), uploaded + served over the **existing** presigned blob transport (ADR
0013), and **attached to the node with no `veyq_media` row** so it never appears in the gallery. Everything the
user *sees* — the picker's second source, the 16:9 crop, and the cover on the **card · detail · map pin** — is the
cr-0072 UI **reused unchanged**; this CR only adds the upload source + its byte path.

Operator-blind by construction: the server holds the cover blob **ciphertext** + an **opaque blob id attached to
the node** (id + size, for retention + download authz **only**) — never the image, and never the crop or the fact
that this blob is "the cover" (that stays in the sealed `wrappedMeta.cover`). The blob attachment is the same
opaque-metadata the server already holds for media (ADR 0013), so blindness is unchanged.

## Context & constraints

- **The existing-media source already carries the feature (cr-0072).** This slice is the **smaller, niche
  addition**: a cover that is *not* one of the Memory's own photos (e.g. a title card, a collage made elsewhere).
  Keep it lean — reuse the transport + the entire display/crop layer; add only the upload source and the
  node-cover attachment.
- **Reuse the blob transport wholesale (ADR 0013).** `startBlobUploads` (declare shape + presign PUT per chunk)
  and `presignBlobDownloads` (presign GET per chunk) are used **as-is**; the client seal/chunk/verify path from
  `media.ts` (`sealBlob`) is reused. What's new is a **commit that attaches the blob to the node WITHOUT a
  `veyq_media` row** — no gallery entry, no derivative tiers.
- **One blob, no derivatives — downscale on device.** A cover is only ever shown small-to-medium (a card tile, a
  ~180 px detail preview, a 34 px pin). So the client **downscales the upload to a cover-sized max dimension**
  (≈2048 px long edge) and seals **one** blob — no THUMBNAIL/PREVIEW tiers, no `completeMedia`. `loadNodeCover`
  fetches that single blob. This keeps the standalone path far simpler than the media path.
- **Server-visible attachment = a single column on `veyq_node` (not a new table).** A nullable
  **`veyq_node.cover_blob_id`** (+ `cover_byte_size` for the budget) links the node to its one cover blob — an
  opaque id + size, mirroring how V17 added `wrapped_meta` with a plain `ALTER TABLE`. A node has exactly one
  cover, so a join table would be over-built. The server thereby knows "this node has a cover blob of N bytes"
  (minimal metadata — the same class it already holds for media) but **not** the image, the crop, or that
  `wrappedMeta.cover` points at it (all sealed under `K_mem`). This column is what makes the blob **reclaimable**
  without a reference-count over ciphertext: **download-presign authz is already satisfied by scope membership +
  the committed `veyq_blob` row** (no per-node attachment needed for authz — confirm on build), so the column
  exists purely for **retention + node-delete cascade**.
- **Lifecycle = client-driven detach-delete + node-delete cascade; the general sweep is the backstop.** Replacing
  or removing an uploaded cover **deletes the prior cover blob** (a `DELETE …/cover-blob` the client calls, or an
  atomic replace on commit that reclaims the superseded `cover_blob_id`); `deleteNode` reclaims the node's cover
  blob via the column. The deferred unreferenced-blob sweep (ADR 0013 §3) is the crash-safety backstop for an
  orphan left mid-replace — **not built here**, just widened in scope to "a committed blob that is neither a
  `veyq_media` original/derivative **nor** any node's `cover_blob_id`". No grace-period token in `wrappedMeta`
  (that would leak structure to the operator).
- **Model generalization (the cr-0072 seam).** `MemoryCover` widens from cr-0072's `{ mediaId, crop }` to a
  **discriminated source** `{ source: { kind: 'media', mediaId } | { kind: 'upload', ref }, crop }`, with a
  **backward-compatible** `normalizeMemoryCover` so every already-sealed `{ mediaId, crop }` opens as a `media`
  source. `coverImageStyle` + `MemoryCoverImage` + the crop editor math are **untouched**.
- **Scope.** Web + its contract + backend. **Deferred:** native cover parity (web-first, ADR 0010 — the data
  layer carries `wrappedMeta.cover` + the cover-blob endpoints after regen); per-surface distinct crops.

## Phases

- [ ] 1 contracts — extend `media.openapi.yaml`: **`commitCoverBlob`** (`POST
  /api/scopes/{scopeId}/nodes/{nodeId}/cover-blob`, body `{ uploadId }` → the committed `BlobRef`
  `{ blobId, chunkCount, byteSize, wrappedBlobKey, manifest? }`) — promotes one `startBlobUploads` session into a
  node-attached cover blob (no media row), **replacing + reclaiming** any prior cover blob atomically; and
  **`deleteCoverBlob`** (`DELETE …/cover-blob`) — detach + reclaim. Serving reuses `presignBlobDownloads`. No new
  crypto vectors (K_blob-under-K_mem is ADR 0012, already pinned). · Done when: the two operations + DTOs are in
  the spec, `npm run generate:api` regenerates the web + backend clients clean, and the `http/` skeleton compiles.
- [ ] 2 backend — migration `Vxx__node_cover.sql`: `ALTER TABLE veyq_node ADD COLUMN cover_blob_id … NULL` (+
  `cover_byte_size`), mirroring V17's `wrapped_meta` add. `MediaHandler`/`MediaStore` (or a sibling
  `CoverHandler`): `commitCoverBlob` (resolve node + session, declare-then-verify the stored chunks against the
  budget, persist `veyq_blob` + set the node's `cover_blob_id`, purge any prior cover blob, delete the session)
  and `deleteCoverBlob` (clear `cover_blob_id` + delete `veyq_blob` + store objects). Download-presign authz is
  already covered by scope membership + the `veyq_blob` row (verify — no per-node attachment lookup needed).
  `deleteNode` reclaims the node's cover blob (bytes) via `cover_blob_id`. · Done when: `NodeCoverSliceTest`
  (green) covers commit→download round-trip, replace reclaims the old blob, `deleteCoverBlob` + `deleteNode` purge
  the object, a non-member is 403 and a cross-scope blob 404; operator-blindness asserted (the node row holds only
  `cover_blob_id` + size, never the crop/key).
- [ ] 3 http — `http/` `.http` files with response-handler assertions: upload (reuse the blob-upload batch) →
  `commitCoverBlob` → `presignBlobDownloads` round-trips the ciphertext; replace reclaims; `deleteCoverBlob`
  removes; authz negatives (non-member 403, wrong-scope 404). · Done when: the `.http` assertions pass against a
  seeded local instance.
- [ ] 4 web — generalize `memoryCover.ts` `MemoryCover` to the discriminated `source` (media | upload) with a
  **backward-compatible** `normalizeMemoryCover` + `sameMemoryCover`; branch `loadNodeCover` (media →
  `listMedia`+`openGrid`; upload → `presignBlobDownloads`+decrypt the single cover blob). Add the **upload path**
  (`commitCoverBlob.ts` or fold into `memoryCover.ts`): downscale → `sealBlob` → `startBlobUploads` → PUT →
  `commitCoverBlob` → the `BlobRef`. Re-add the **FileUpload source** to `MemoryCoverDialog` (the cr-0072 picker +
  crop are reused; a new upload just becomes the crop source); on replace/remove of an *upload* cover, call
  `deleteCoverBlob` for the superseded blob. `MemoryDetail`/`MemoryCard`/`MemoryBrowse`/`SpaceMap` consume
  `loadNodeCover`'s `{ url, crop }` **unchanged**. i18n EN+DE for the upload affordance. Close the
  `cover-from-standalone-upload` coverage; drop `uc.memory.cover` `status: planned`. · Done when: `npm run check`
  + the trust-chain guards green; unit tests cover the model generalization (old `{mediaId}` seal still opens) +
  the upload commit + the `loadNodeCover` branch; a live smoke (re-seeded instance): upload a NEW image → crop →
  it shows on card · detail · pin, replace/remove reclaims the blob, and an existing-media cover still works.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | A standalone cover blob (no media row) committed·served·reclaimed, reusing the cr-0072 display + crop | Status |
|---|---|---|
| backend / contracts | `commitCoverBlob` + `deleteCoverBlob` + a `cover_blob_id` column on `veyq_node`; serving via the existing presign path | ◻ planned |
| web | `MemoryCover` source generalization + the upload path + the dialog's second source; display reused | ◻ planned |
| apple / android | native upload-cover picker/crop is their own later CR (web-first, ADR 0010); the data layer + endpoints carry it after regen | ◻ **separate (native)** |

## Definition of done

A Memory's cover can be a **brand-new uploaded image** that is not one of its media: sealed under a fresh
`K_blob` wrapped by `K_mem`, committed as a **standalone cover blob** attached to the node with **no `veyq_media`
row**, served over the existing presigned transport, and shown — via the **cr-0072** display + one shared crop
formula — identically on the browse card, the detail header, and the map pin. Replacing or removing the cover
**reclaims** the blob (client detach-delete + node-delete cascade; the general sweep is the deferred backstop).
The **existing-media** source (cr-0072) still works, and every already-sealed `{ mediaId, crop }` cover opens
under the generalized model. Operator-blind throughout — the server holds only ciphertext + an opaque
node-attached blob id + size. `npm run check` + the trust-chain guards green; the http assertions + the live
smoke confirmed.

## Retrospective

_(to be written at close.)_
