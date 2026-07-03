---
id: cr-0073
title: Cover from a new upload — a standalone cover blob (no media row), reusing the cr-0072 display + crop
status: done
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

- [x] 1 contracts — extend `media.openapi.yaml`: **`commitCoverBlob`** (`POST
  /api/scopes/{scopeId}/nodes/{nodeId}/cover-blob`, body `{ uploadId }` → the committed `BlobRef`
  `{ blobId, chunkCount, byteSize, wrappedBlobKey, manifest? }`) — promotes one `startBlobUploads` session into a
  node-attached cover blob (no media row), **replacing + reclaiming** any prior cover blob atomically; and
  **`deleteCoverBlob`** (`DELETE …/cover-blob`) — detach + reclaim. Serving reuses `presignBlobDownloads`. No new
  crypto vectors (K_blob-under-K_mem is ADR 0012, already pinned). · Done when: the two operations + DTOs are in
  the spec, `npm run generate:api` regenerates the web + backend clients clean, and the `http/` skeleton compiles.
- [x] 2 backend — migration `Vxx__node_cover.sql`: `ALTER TABLE veyq_node ADD COLUMN cover_blob_id … NULL` (+
  `cover_byte_size`), mirroring V17's `wrapped_meta` add. `MediaHandler`/`MediaStore` (or a sibling
  `CoverHandler`): `commitCoverBlob` (resolve node + session, declare-then-verify the stored chunks against the
  budget, persist `veyq_blob` + set the node's `cover_blob_id`, purge any prior cover blob, delete the session)
  and `deleteCoverBlob` (clear `cover_blob_id` + delete `veyq_blob` + store objects). Download-presign authz is
  already covered by scope membership + the `veyq_blob` row (verify — no per-node attachment lookup needed).
  `deleteNode` reclaims the node's cover blob (bytes) via `cover_blob_id`. · Done when: `NodeCoverSliceTest`
  (green) covers commit→download round-trip, replace reclaims the old blob, `deleteCoverBlob` + `deleteNode` purge
  the object, a non-member is 403 and a cross-scope blob 404; operator-blindness asserted (the node row holds only
  `cover_blob_id` + size, never the crop/key).
- [x] 3 http — **re-scoped to a boundary file, consistent with the media-transport precedent.** The full
  authenticated round-trip (upload → `commitCoverBlob` → `presignBlobDownloads`, replace/delete reclaim, authz
  negatives) is **not reproducible from a bare `.http`** — it needs client-side AEAD sealing + presigned
  direct-to-store PUT + the operator-blind scope-grant flow, exactly why the media blob transport
  (en.memory.media) has no `.http` and `content.http` covers only its 401 boundary. So `http/media/cover.http`
  asserts the **401 unauthenticated boundary** of both cover-blob endpoints; the authenticated round-trip +
  reclaim + authz negatives live in `NodeCoverSliceTest` (Phase 2). · Done when: the boundary `.http` mirrors the
  proven `content.http` shape (the identical unauth-401 assertion is green in `NodeCoverSliceTest.unauthenticatedIs401`);
  the ijhttp run itself needs a freshly-restarted instance (a running `quarkusDev` must re-run `openApiGenerateSpecs` after the contract change).
- [x] 4 web — generalize `memoryCover.ts` `MemoryCover` to the discriminated `source` (media | upload) with a
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
| backend / contracts | `commitCoverBlob` + `deleteCoverBlob` + a `cover_blob_id`/`cover_byte_size` column on `veyq_node` (V19); serving via the existing presign path; `NodeCoverSliceTest` green | ✅ done |
| web | `MemoryCover` source generalization (back-compat) + `uploadCoverBlob`/`deleteCoverBlob` + the dialog's upload source; display reused; `loadNodeCover` branched | ✅ done |
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

Built as planned across contracts → backend → web, with **one deliberate re-scope on Phase 3 (http)**. What
landed:

- **Contracts.** `commitCoverBlob` (`POST …/nodes/{nodeId}/cover-blob`, `{ uploadId }` → `BlobRef`) and
  `deleteCoverBlob` (`DELETE …/cover-blob`) on `media.openapi.yaml`, plus a reusable `BlobRef` schema (the same
  opaque read-back shape a gallery tier carries). `illegal_target` on a FOLDER is a **422** (matching
  `content.openapi.yaml`'s `illegal_structure`), not a per-item error — cover commit is a single op, not a batch.
- **Backend.** V19 added `cover_blob_id` + `cover_byte_size` to `veyq_node` (mirroring V17's `wrapped_meta`).
  `MediaStore.commitCover`/`clearCover` + `MediaHandler.commitCover`/`deleteCover` reuse the existing
  declare-then-verify + `persistBlob` + budget/purge machinery; commit **atomically reclaims** any prior cover
  blob (returning its chunk keys for a best-effort store purge). Node-delete reclaim lives in
  `NodesStore.deleteSubtree` (it already computes the doomed subtree) — a **deliberate one-way content→media
  coupling** (the reverse of media→content's existing `NodeEntity` import), since the `cover_blob_id` column that
  makes a cover reclaimable sits on the node. Download-presign authz needed **no** change (scope membership + the
  `veyq_blob` row already cover it — confirmed on build). `NodeCoverSliceTest` (10 cases) asserts commit→download,
  replace/delete/node-delete reclaim, FOLDER 422, cross-scope/non-member 404, unauth 401, and operator-blindness
  (the node row holds only the blob id + size).
- **Phase 3 re-scoped.** The CR's original Phase 3 (a full upload→commit→download `.http` round-trip) **contradicts
  the repo's own convention**: the media blob transport has no `.http` at all, and `content.http` covers only its
  401 boundary because the authenticated flow "needs the operator-blind scope-grant flow, not reproducible from a
  bare `.http`." A cover commit additionally needs client-side AEAD sealing + a presigned direct-to-store PUT —
  none of which ijhttp can do. So Phase 3 became a **boundary file** (`http/media/cover.http`, the two 401
  assertions), and the authenticated round-trip + reclaim + authz negatives are carried by `NodeCoverSliceTest`,
  exactly as media/content split those responsibilities. Sparring-partner call, flagged rather than forced.
- **Web.** `MemoryCover` widened from `{ mediaId, crop }` to `{ source: media | upload, crop }` with a
  **backward-compatible** `normalizeMemoryCover` (every already-sealed cr-0072 `{ mediaId }` cover still opens).
  `media.ts` grew `uploadCoverBlob` (downscale to one ~2048 px JPEG → `sealBlob` → `startBlobUploads` → PUT →
  `commitCoverBlob`) + `deleteCoverBlob`, reusing the private seal/PUT primitives. `loadNodeCover` branches
  media→`openGrid` / upload→`openBlob`; `coverImageStyle` + `MemoryCoverImage` + the crop math are **untouched**.
  The dialog gained an upload source and now emits a `CoverSaveIntent` (`media` | `upload` | `keep` | `remove`) so
  it stays crypto/network-free — `MemoryDetail` orchestrates the upload + seal, decrypts an upload cover for its
  header on demand, and **reclaims a superseded upload blob** when switching away (a fresh upload commit reclaims
  the prior automatically; switching to a media cover or removing calls `deleteCoverBlob`).

**Verified:** `./gradlew check` green (incl. `NodeCoverSliceTest`); web `npm run typecheck` + `npm run test`
(345 passing) + `npm run build` green; the four trust-chain validators green. **Live web smoke DONE** (re-seeded
`127.0.0.1` instance, Garage `--profile s3`, browse origin `127.0.0.1:8089` = the store public endpoint): created
a Memory with **no media**, opened the cover editor, **uploaded a new 1200×800 image**, cropped 16:9, saved — the
network showed the full path `blob-uploads-batch → PUT /veyq/… → commitCoverBlob → blob-downloads-batch → GET
/veyq/…`, and the cropped cover rendered on **both the detail header and the browse card** (screenshots). DB
assertion of operator-blindness: the node row carried `cover_blob_id` + `cover_byte_size=22219` (the on-device
**downscale** of the 1 MB original) with `wrapped_meta` ciphertext (0 rows leaking `COVER`/`crop`/`upload`/
`mediaId`), **0 `veyq_media` rows** for the node, 1 `veyq_blob`. **Remove** reclaimed it — `cover_blob_id` → NULL,
`veyq_blob` → 0, header back to the placeholder. **Deferred:** the ijhttp boundary run (needs a freshly-restarted
`quarkusDev` — a live-reload instance doesn't re-run `openApiGenerateSpecs` after a contract change; the identical
unauth-401 assertion is already green in the slice test); native cover parity (Apple/Android, ADR 0010). The map
pin + existing-media source weren't separately driven — the pin reuses the same `loadNodeCover` path just proven
on the card, and the media source is the unchanged cr-0072 display (backward-compat unit-tested).
