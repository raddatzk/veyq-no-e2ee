---
id: cr-0102
title: Uploader share — a write-only drop-box (client-local, session-only own-view) (web vertical)
status: done
satisfies: [uc.sharing.share-uploader]
adrs: ["0031", "0030", "0029", "0012", "0013", "0015", "0026"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the third **sharing** slice ([uc.sharing.share-uploader](../docs/features/sharing/use-cases/uc.sharing.share-uploader.md),
[ADR 0031](../docs/decisions/0031-uploader-public-share.md)): a curator turns **one of their own (personal-scope)
Memories** into a **write-only drop-box** link `/s/{shareId}#k=<K_share>` — anyone can **add photos but cannot see**
the Memory's existing content (nor other contributors'), operator-blind. It extends the **CONTRIBUTOR** share
(cr-0101, [ADR 0030](../docs/decisions/0030-contributor-public-share.md)): the sealed identity + the share-scoped
upload path + the shared optimistic-upload queue are reused; the new work is the **UPLOADER role**, the **`canView`
gate** (the resolve returns `media: []` for an uploader — *"cannot see others"* enforced structurally), the
**session-only client-local** own-view (local previews, **never a download**),
**delete-your-own** (token-gated + ownership-checked), and a **prominent, repeated ephemerality warning**. **Web
vertical only; native is a later CR.** **v1: role UPLOADER, personal scope, direct-append.**

## Context & constraints

- Durable truth: [uc.sharing.share-uploader](../docs/features/sharing/use-cases/uc.sharing.share-uploader.md)
  (the flow + the 6 acceptance criteria), [ADR 0031](../docs/decisions/0031-uploader-public-share.md) (the
  write-only drop-box — `canView` gate, client-local session-only own-view, delete-own, the four-point
  ephemerality warning), building on [ADR 0030](../docs/decisions/0030-contributor-public-share.md) (the sealed
  identity + share-scoped upload path), [ADR 0029](../docs/decisions/0029-public-viewer-share.md),
  [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md), [ADR 0026](../docs/decisions/0026-client-trust-tiers.md).
- Reused substrate (not rebuilt): the **cr-0101 contributor machinery** (`veyq_share_identity`, `POST /identities`
  + `/blob-uploads` + `/media`, the share-scoped upload transport) and the **shared upload queue + optimistic
  tiles** (`useOptimisticTiles` / `OptimisticTile` / the transport-capable `enqueueUploads`); the **shared
  `MediaGrid`** for the own-view; the **v1 crypto suite** (no new primitive, **no new conformance vector**); the
  blob reclaim path (`deleteSubtree`-style, `BlobStore`).
- Invariant at risk: **operator-blind-e2ee.** This is the **most blind** sharing surface — the server serves the
  uploader **no gallery** (resolve `media: []`, no download for the uploader). It must **never** hand an uploader
  another guest's media ids, or a per-guest content listing. The uploader's own-view is **client-local + session
  only** (local previews, never fetched). Delete is authorized by a **single-row ownership check**
  (`share_identity_id == the token's identity`), not gallery filtering.
- **v1 gates:** only a **personal-scope** Memory the caller owns may be uploader-shared (as cr-0100/0101); the
  name is **mandatory + sealed under `K_mem`**; contributions **direct-append** to the shared root; the upload
  path is rate-limited + byte-budgeted; the **session-only ephemerality is warned in four places** (ADR 0031 §5).

## Phases (web vertical; native is a later CR)

- [x] 1 contracts — extend `contracts/api/sharing.openapi.yaml`: add **UPLOADER** to `ShareRole`. Add **`DELETE
  /api/shares/{shareId}/media/{mediaId}`** (unauthenticated; the `contributorToken` rides an **`X-Contributor-Token`
  header**) → `204` / `403` / `404`. The resolve schema is unchanged (`media` is already an array — the backend
  returns it **empty** for an UPLOADER share). No new crypto vector. · Done when: `openApiGenerateSpecs` (backend)
  + web `generate:api` clean.
- [x] 2 backend — the **create-gate** now accepts **role UPLOADER** (still personal-scope-gated). The **resolve**
  gains a role branch: `media: []` + `nodes: []` for **UPLOADER** (the minimal capability — the client seals under
  `K_mem` from the `wrap` and titles from `sealedName`; the **blob endpoint is role-gated too**, `404` for an
  uploader), the full subtree for VIEWER/CONTRIBUTOR
  (`canView` = role ∈ {VIEWER, CONTRIBUTOR}). **Identity-create + the share-scoped upload** are allowed for
  **UPLOADER too** (`canUpload` = role ∈ {CONTRIBUTOR, UPLOADER}). New **delete** — resolve the media by id in the
  share's scope, authorize **iff** `share_identity_id == the token's identity`, delete the media (+ derivatives +
  reclaim all blobs from the store), `404` otherwise / when disabled. Slice tests: an UPLOADER resolve returns
  `media: []` even with existing media; UPLOADER upload attaches + stamps; delete-own reclaims + a
  wrong-identity/foreign token delete `403`/`404`; a VIEWER upload is still rejected. · Done when: `./gradlew
  check` green.
- [x] 3 http — `http/sharing/uploader.http`: create UPLOADER (authed) → **resolve returns `media: []`** (no bearer)
  → identity + share-scoped upload (gated) → **delete-own** (token) → revoke boundaries, with response-handler
  assertions. · Done when: the `.http` assertions pass.
- [x] 4 web (curator) — the **Uploader** option in the `ShareDialog` role choice (Viewer / Contributor / Uploader);
  an Uploader share `POST`s `role: UPLOADER`. i18n EN+DE. · Done when: `npm run check` green.
- [x] 5 web (uploader) — extend `ShareView.vue` for the **UPLOADER** role: the mandatory name pre-screen **carries
  the ephemerality warning**; after naming, a **session-only own-gallery** — the queue's optimistic tiles + the
  client-kept `{ mediaId, local preview }` of this session's uploads, rendered via the shared `MediaGrid` **from
  the local previews (never a download)** — with a **per-tile delete** (calls the delete endpoint + drops it from
  the session). The four warnings: pre-screen note, a **persistent header badge** ("visible only in this session"),
  a **dedicated reload empty-state**, and a delete note. i18n EN+DE. · Done when: `npm run check` green + a **live
  smoke** (create an Uploader share → open `/s/` fresh → the resolve shows no existing photos → name → add a photo
  → it shows from the local preview → delete it → reload shows the empty-state, not the gallery).
- [x] 6 coverage/docs — fill [uc.sharing.share-uploader.yaml](../docs/coverage/features/sharing/use-cases/uc.sharing.share-uploader.yaml):
  add test refs for all six criteria (backend slice + http + web unit), keep **apple/android** as gaps, **drop
  `status: planned`**, mark the use-case **built (web)** in [sharing/_index.md](../docs/features/sharing/_index.md).
  · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py`
  green.

## Definition of done

A curator can **turn a personal-scope Memory into a write-only drop-box** `/s/{shareId}#k=…` (role UPLOADER); an
**anonymous visitor** opens it, **sees none of the existing photos** (the resolve returns `media: []`), names
themselves once, and can **add photos** (appended to the Memory, visible to the curator) that show in **their own
session view from local previews — never a download** — and **delete their own this session**; a **reload shows a
clear empty-state**, not a gallery. **Operator-blind throughout:** the server serves the uploader no gallery and
holds only the opaque share record + the sealed anonymous identity + the `share_identity_id` stamp + ciphertext.
The **session-only ephemerality is warned prominently** (four places). Guards green (`./gradlew check`, the `.http`
assertions, `npm run check`, the four `docs/` validators). **Out of scope (follow-ups):** persisting the id list
client-side for **post-reload delete** (still no download); the **buckets UI**; **MANAGER** / contributor
view-all + delete; password protection; moderation; **native parity**.

## Retrospective

Landed the write-only UPLOADER drop-box on top of the CONTRIBUTOR machinery with **very little new surface** — the
design paid off. Notes:

- **The session-only own-view fell out of the optimistic-tile machinery for free.** Because an UPLOADER resolve
  serves `media: []`, a `done` optimistic tile is *never* reconciled away (its `mediaId` is never in the empty
  `listedMediaIds`), so it simply stays as the local-preview own-view for the whole session — and vanishes on
  reload because the queue is in-memory. No new "session gallery" state was needed: the reuse from cr-0074/0101
  did the work. The only additions were `removeFile` (drop a settled tile on delete) and a `mediaId` on the tile
  VM (to target the delete).
- **`canView` vs `canUpload` is the whole role model.** Two one-line predicates in the handler
  (`canView = {VIEWER,CONTRIBUTOR}`, `canUpload = {CONTRIBUTOR,UPLOADER}`) express "cannot see others" structurally —
  the resolve branches to `media: []` + root-node-only + no identities for an uploader. No gallery filtering, no
  per-guest listing: the operator-blind guarantee is enforced by *not returning* rather than by filtering.
- **Delete is ownership, not capability.** The earlier wording fix (mediaId is an identifier, not a capability)
  shaped the implementation cleanly: `deleteContributedMedia` authorizes on a single-row `share_identity_id ==
  the token's identity` check and reclaims blobs; a foreign/unknown media is an indistinguishable 404. The
  `contributorToken` (in the `X-Contributor-Token` header) is the real authority.
- **FK ordering bite.** First cut of the reclaim deleted the blob rows before the media rows → H2 FK violation
  (`veyq_media.blob_id → veyq_blob.id`). Fixed by deleting the referencing rows (media + derivatives) first, then
  the blobs. Caught immediately by the slice test.
- **Test-token length gotcha (twice).** The `X-Contributor-Token` header pins `[A-Za-z0-9_-]{43}`; a 44-char
  "bad token" literal is rejected at the boundary as `400`, not the `403` the test expected. Both the slice test
  and the `.http` needed exactly-43-char well-formed-but-bogus tokens. (Bean-validation runs before the handler.)
- **Live smoke proved the four warnings + the write-only guarantee end-to-end:** an UPLOADER `/s/` of a 3-photo
  "Rom 2026" showed **zero** existing tiles (write-only), the name pre-screen carried the ephemerality warning,
  the header badge + session-note banner were persistent, an added photo showed from its local preview with a
  delete button, delete removed it (curator ended at 3+1 kept, deleted one gone), and a reload dropped to the
  empty-state — not the gallery.
- **Follow-ups unchanged** (deferred, each its own slice): post-reload delete (persist the id list client-side,
  still no download), the buckets UI, MANAGER/view-all/delete, password protection, moderation, native parity.
