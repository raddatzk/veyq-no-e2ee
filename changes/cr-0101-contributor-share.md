---
id: cr-0101
title: Contributor share — a sealed anonymous identity + a share-scoped upload path (web vertical)
status: done
satisfies: [uc.sharing.share-contributor]
adrs: ["0030", "0029", "0012", "0013", "0015", "0026"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the second **sharing** slice ([uc.sharing.share-contributor](../docs/features/sharing/use-cases/uc.sharing.share-contributor.md),
[ADR 0030](../docs/decisions/0030-contributor-public-share.md)): a curator turns **one of their own (personal-scope)
Memories** into a **contributor link** `/s/{shareId}#k=<K_share>` — anyone with the link can **view it AND add
photos**, no account, no install — while the server stays operator-blind. It extends the public **viewer** share
(cr-0100, [ADR 0029](../docs/decisions/0029-public-viewer-share.md)): the crypto + resolve + gated blob reads are
reused; the new work is the **CONTRIBUTOR role**, the **mandatory display name sealed under `K_mem` → an anonymous
share identity** (the buckets forward-compat hook), the **share-scoped unauthenticated upload path** (presign
mirror + complete, [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)), **direct-append**
attribution, and the curator + contributor web surfaces. **Web vertical only; native is a later CR.** **v1 is
deliberately narrow: role CONTRIBUTOR only (view + append-upload), direct-append, personal scope** — the buckets
UI, the UPLOADER drop-box, MANAGER/edit/delete, password protection and moderation are separate later slices.

## Context & constraints

- Durable truth: [uc.sharing.share-contributor](../docs/features/sharing/use-cases/uc.sharing.share-contributor.md)
  (the flow + the 6 acceptance criteria), [ADR 0030](../docs/decisions/0030-contributor-public-share.md) (the
  CONTRIBUTOR role + the sealed anonymous identity + the share-scoped upload path + direct-append + the buckets
  hook + abuse controls), building on [ADR 0029](../docs/decisions/0029-public-viewer-share.md) (the share
  resource + anonymous access path), [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) (`symWrap(K_share,
  K_mem)` + `K_blob` under `K_mem`), [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
  (the direct-to-store upload the share-scoped presign mirrors), [ADR 0026](../docs/decisions/0026-client-trust-tiers.md)
  (the footer-link trust-tier honesty, reused).
- Reused substrate (not rebuilt): the **cr-0100 share resource** (create/list/revoke + the anonymous resolve +
  gated blob GET) and its web viewer (the shared `MediaGrid` + `MediaLightbox`); the **v1 crypto suite**
  (`symWrap`/`symUnwrap`, `randomBytes`, `sealFrame` — no new primitive, **no new conformance vector**); the
  **media upload pipeline** (`addMedia`, derivatives, chunk-sealing, the upload queue) — rewired through the
  share-scoped endpoints (injected like the cr-0100 lightbox refactor); the **blob/upload-session/media tables**
  + the presign path (`BlobStore`, ADR 0013); the base64 wire rules ([web CLAUDE.md](../web/CLAUDE.md)).
- Invariant at risk: **operator-blind-e2ee.** New server-visible surface is all opaque — the plaintext `role`,
  the **`K_mem`-sealed contributor name**, the opaque `identityId` + token hash, the `share_identity_id` stamp on
  media, and ciphertext blobs. The server must **never** see `K_share`, `K_mem`, the Memory name, the
  contributor's name, the photo bytes, or *who* contributed. The upload endpoints are a **parallel authz path on
  share state** (role CONTRIBUTOR + `enabled` + a valid contributor token), never a relaxed member filter. The
  T&S "decryptable-on-report" corollary ([security model](../docs/crosscutting/security-model.md)) extends to the
  sealed name + contributed media — intended, not a leak.
- **v1 gates:** only a **personal-scope** Memory the caller owns may be contributor-shared (as cr-0100); the
  display name is **mandatory** (blocked pre-screen) and **sealed under `K_mem`**; contributions **direct-append**
  to the shared **root** node only; the unauthenticated upload path is **rate-limited per share** + byte-budgeted.

## Phases (web vertical; native is a later CR)

- [x] 1 contracts — extend `contracts/api/sharing.openapi.yaml`: add **CONTRIBUTOR** to `ShareRole`; **authenticated**
  create already accepts `role` (relax the VIEWER-only note to allow CONTRIBUTOR). **Unauthenticated** contributor
  ops — `POST /api/shares/{shareId}/identities` `{ sealedDisplayName }` → `{ identityId, contributorToken }`;
  `POST /api/shares/{shareId}/blob-uploads` (presign PUTs, mirror of `startBlobUploads`) + `POST
  /api/shares/{shareId}/media` (complete + attach, mirror of `completeMedia`), both taking the `contributorToken`;
  the **resolve** (`GET /api/shares/{shareId}`) gains per-media `identityId` + a `identities[]` list (opaque
  `identityId` + `sealedDisplayName`). No new crypto vector. · Done when: `openApiGenerateSpecs` (backend) + web
  `generate:api` clean.
- [x] 2 backend — persist a `veyq_share_identity` row (opaque `identityId` PK, `share_id`, `sealed_display_name`
  blob, `token_hash`, `created_at`; migration V-next) + a nullable `share_identity_id` on `veyq_media` (migration
  V-next). **Create** now accepts **role CONTRIBUTOR** (still personal-scope-gated). **Identity-create**
  (unauthenticated; gated share exists + `enabled` + role CONTRIBUTOR; store the sealed name + token hash).
  **Share-scoped blob-uploads + media** (unauthenticated; gated share + `enabled` + role CONTRIBUTOR + a valid
  `contributorToken`; attach to `share.node_id`, stamp `share_identity_id`; reuse the media declare-then-verify +
  byte budget; a **per-share rate limit**). The **resolve** returns per-media `identityId` + the `identities[]`.
  Slice tests: create CONTRIBUTOR → identity-create → share-scoped upload attaches + stamps → the media +
  identity surface in the resolve; a VIEWER share rejects the upload path; `enabled=false` → upload `403`/`404`;
  a bad/absent token → `403`; a non-personal node create is rejected; operator-blindness (only ciphertext + opaque
  ids). · Done when: `./gradlew check` green.
- [x] 3 http — `http/sharing/contribute.http`: create CONTRIBUTOR (authed) → **identity-create (no bearer)** →
  share-scoped upload (gated, no bearer) → the media appears in the anonymous resolve → a VIEWER share's upload
  path `403` → revoke → upload `403`/`404`, with response-handler assertions against a seeded instance. · Done
  when: the `.http` assertions pass.
- [x] 4 web (curator) — a **role choice** (Viewer / Contributor) in `ShareDialog.vue`; a Contributor share `POST`s
  `role: CONTRIBUTOR`. The manage/revoke list is unchanged. i18n EN+DE. · Done when: `npm run check` green.
- [x] 5 web (contributor) — extend the anonymous viewer (`ShareView.vue`): on a **CONTRIBUTOR** share, a
  **mandatory display-name pre-screen** (the design source's login-gate look) → seal the name under `K_mem` →
  create the anonymous **share identity** (held on device, keyed by `shareId`). Then the read-only gallery gains
  an **Add photos** affordance that seals + uploads via the **share-scoped** endpoints (reuse `addMedia` + the
  upload queue, the upload transport injected like the cr-0100 lightbox loaders); the contributed photo appears in
  the gallery (re-resolve). i18n EN+DE. · Done when: `npm run check` green + a **live smoke** (create a Contributor
  share, open the `/s/` link in a fresh no-session context → name → add a photo → it appears; then revoke → upload
  blocked).
- [x] 6 coverage/docs — fill [uc.sharing.share-contributor.yaml](../docs/coverage/features/sharing/use-cases/uc.sharing.share-contributor.yaml):
  add test refs for all six criteria (backend slice + http + web unit), keep **apple/android** as gaps, and **drop
  `status: planned`**. Mark the use-case **built (web)** in [sharing/_index.md](../docs/features/sharing/_index.md).
  · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py`
  green.

## Definition of done

A curator can **turn a personal-scope Memory into a contributor link** `/s/{shareId}#k=…` (role CONTRIBUTOR); an
**anonymous visitor with no account** opens it, **names themselves once** (the name sealed under `K_mem` → an
anonymous share identity), and can **add photos** that appear in the Memory immediately (direct-append), attributed
to their sealed name; the curator can **revoke** it (or it **expires**) → viewing + uploading `403`/`404`.
**Operator-blind throughout:** the server holds only `shareId` + the wrap + the plaintext role + the sealed Memory
name + `nodeId` + an opaque `share_identity` (the `K_mem`-sealed contributor name + a token hash) + a
`share_identity_id` stamp + ciphertext blobs — never `K_share`/`K_mem`/any name/the photo bytes/who contributed.
Photos go **direct-from/to-store** via share-gated presigns. The no-install contributor page carries the same
trust-tier honesty as the viewer (footer link). Guards green (`./gradlew check`, the `.http` assertions,
`npm run check`, and the four `docs/` validators). **Out of scope (follow-ups):** the **buckets UI** (per-contributor
grouping/display); the **UPLOADER** drop-box (gallery-less); **MANAGER** / contributor edit + delete; password
protection; **moderation** (review-before-publish); per-contributor delete-your-own-upload; **group/tenant**
contributor shares (governance); **native parity** (web-first, like the rest of the epics).

## Retrospective

Shipped the full web vertical as scoped — CONTRIBUTOR only, direct-append, personal scope — with no new crypto
primitive/vector (the existing `symWrap`/`symUnwrap`/`randomBytes`/`sealFrame` carry it). All six criteria green
across the backend slice, the http guest boundary, and the web unit round-trip.

**What landed:**
- **Contracts** — CONTRIBUTOR added to `ShareRole`; `POST /identities`, `POST /blob-uploads`, `POST /media`
  (share-scoped, `contributorToken`-gated); resolve gained per-media `identityId` + `identities[]` (opaque id +
  `K_mem`-sealed name).
- **Backend** — `V32` (`veyq_share_identity` + `veyq_media.share_identity_id`), the CONTRIBUTOR create-gate, the
  identity-create + the two share-scoped upload endpoints (all `@PermitAll`, gated on share state + token), plus a
  per-share `ShareRateLimiter`. **Key reuse win:** rather than duplicate the media declare-then-verify/budget
  logic, `MediaHandler` grew member-less cores (`startForScope` / `completeForNode`) that both the authed path and
  the share path call — the share adds its own gate and stamps the identity; `MediaStore.attach` took an optional
  `shareIdentityId`. 5-test `ShareContributorSliceTest`, `./gradlew check` green.
- **http** — `contribute.http` asserts the genuinely-public guest boundary (unknown share → `404`, not `401`),
  verified live with `ijhttp` (8/8 with the viewer file).
- **web (curator)** — a Viewer/Contributor `SelectButton` in `ShareDialog`; the role rides `createShare`.
- **web (contributor)** — `ShareView` on a CONTRIBUTOR share shows the mandatory name pre-screen → seals the name
  under `K_mem` → an anonymous identity (persisted per-share in `localStorage`), then an **Add photos** affordance.
  **Reuse:** `addMedia` was refactored to inject a `MediaUploadTransport` (backward-compatible — the 70 media/
  lightbox/detail tests stayed green); the contributor upload is `uploadMediaVia(...)` over a share-scoped
  transport, so the exact operator-blind sealing pipeline runs, only the reserve/complete transport is share-gated.

**Design decisions during the build:**
- **`share_identity_id` on `veyq_media`** (not a separate join) — the media→identity link is 1:1 and the resolve
  already returns media rows; a nullable stamp is the least surface and the direct buckets hook.
- **The contributor token is a capability, not an auth** — it binds a session's uploads to one identity
  (anti-spoof) but never authenticates a person; attribution stays self-asserted (operator-blind, accountless),
  as the ADR states.

**Follow-up (same slice): shared optimistic upload queue.** The contributor upload initially showed a button
spinner + re-resolve (no in-grid progress). Made the global upload queue **transport-capable** — `UploadTarget`
gained an optional `{ key, upload }` so a batch can carry a share-scoped transport + its own grouping key
(defaulting to the authed `{ scopeId, nodeId }` → `addMedia`, so the internal gallery is unchanged) — and
extracted the optimistic machinery into a shared **`useOptimisticTiles`** composable + an **`OptimisticTile`**
component, both now used by `MemoryDetail` **and** `ShareView`. So a contributor's picked photo renders instantly
as a preview tile with a progress ring and reconciles into the real thumbnail on completion — the **same** cr-0074
UX as the curator, from one code path. The 22 queue/optimistic/MemoryDetail-optimistic tests stayed green
(behaviour-preserving); confirmed live (the contributor grid showed the ring-tile mid-upload, then reconciled to
the real "Klaus" thumbnail — no duplicate, no flash).

**Live browser smoke — passed (full happy-path).** On the isolated stack (`veyq.localhost:8089`, store
`PUBLIC_ENDPOINT` matched): curator created a **Contributor** share of "Rom 2026" → opened the `/s/` link
anonymously → the **mandatory name pre-screen** rendered → named "Oma Traudl" (sealed under `K_mem` → identity)
→ **Add photos** uploaded a new image via the share-scoped endpoints (direct-to-store) → it appeared in the
gallery ("2 Objekte", "Oma's Foto" beside the curator's photo); the DB confirmed a `share_identity_id`-stamped
media + a sealed (opaque) identity name → **revoked** → reopening the link showed "nicht verfügbar" (contributions
blocked). All six criteria confirmed end-to-end in a real browser.
