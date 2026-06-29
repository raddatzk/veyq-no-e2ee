---
id: cr-0028
title: Media blobs — chunked-AEAD K_blob, direct-to-store presigned, batch-shaped control plane
status: done             # planned → in-progress → done (the file is kept as a record)
satisfies: [en.memory.media]   # an enabler — the media data layer the Memory use-cases build on
adrs: ["0013", "0012", "0009", "0007", "0002"]
invariants: [operator-blind, self-hostable]
---

## Goal

Build the **media data layer**: attach an encrypted media **blob** to a Memory (or Chapter) node — sealed
client-side as **chunked AEAD** under a fresh random **`K_blob`** (wrapped under the Memory's `K_mem`),
transferred **direct-to-store** via presigned URLs ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)),
recorded as a **blob** (bytes) + a **media** (blob→node link). The control plane is **batch-shaped** (lists +
per-item results) from the start; this slice exercises **length-1 lists**. Operator-blind throughout: the
server + object store only ever hold ciphertext chunks + opaque metadata. **No content dedup**
([ADR 0013 §5](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md#5-no-content-dedup)).
**Out of scope** (their own later slices): **resumable uploads**, thumbnail/preview derivatives, public-share
transport mirrors, and the gallery/curation UI (deferred to the shell — this CR is the data layer + backend).

## Context & constraints

- Enabler: [en.memory.media](../docs/features/memory/enablers/en.memory.media.md); decisions:
  [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) (S3-only, presigned
  direct-to-store, batch-shaped, no dedup), [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)
  (`K_blob` random, under `K_mem`).
- **`K_mem` already exists** — it is the per-Memory key from [cr-0027](cr-0027-memory-tree.md) (random, wrapped
  under `K_SC`). A blob's `K_blob` is a **fresh random key wrapped under `K_mem`** (`symWrap(K_mem, K_blob)`).
  No new Memory-level key material.
- **Crypto reuse — almost nothing new.** Chunk seal/open is the **`chunk_frame`** vector (already in
  `v1.json`, already in web `v1.ts` as `sealFrame`/`openFrame`); it just isn't ported natively yet, so
  apple/android gain **`sealFrame`/`openFrame` only** (vector-gated). `K_blob` wrap is `symWrap` with the new
  context string `"blob-key"` — same vector-pinned primitive, no new vector. **`dedup_token` is NOT ported**
  (no dedup). No backend/contract crypto change.
- **Direct-to-store (invariant: self-hostable + operator-blind).** Bytes never flow through the backend; it
  signs short-TTL, single-object, single-method presigned URLs for a caller that already holds a grant for the
  scope. The in-memory store **cannot presign** → it advertises `directTransport: unavailable` and has no
  transport path; the byte transfer is exercised by **opt-in e2e** against a real MinIO (`VEYQ_S3_E2E=1`).
- **Operator-blind (invariant):** the server stores ciphertext chunks (in the object store) + opaque blob
  metadata (`chunkCount`/`byteSize`/manifest), the `K_blob` wrap, and the media→node link; never a plaintext
  byte, a content key, or a filename in clear. Guards: scope-access (caller holds a grant), media target must
  be a **MEMORY/CHAPTER** (a FOLDER has no `K_mem`), declare-then-verify byte budget.

## Phases

- [x] **1 — contracts.** `contracts/api/media.openapi.yaml` (separate `media` tag), all **batch-shaped** under
  `/api/scopes/{scopeId}/…`:
  - `POST …/blob-uploads-batch` — start N blobs (each `chunkCount`, `byteSize`, base64 `wrappedBlobKey`) →
    per-item `{ uploadId, presignedPuts[] }` or per-item error.
  - `POST …/media-batch` — complete + attach N (each `uploadId`, `nodeId`) → per-item `{ blobId, mediaId }` or
    error.
  - `GET …/nodes/{nodeId}/media` — list a node's media (`mediaId`, `blobId`, `chunkCount`, `manifest`,
    base64 `wrappedBlobKey`, opaque metadata) for read-back.
  - `POST …/blob-downloads-batch` — presign GET for N blobs → per-item `{ presignedGets[] }`.
  - Error codes `scope_not_found`/`node_not_found`/`upload_not_found` (404), `illegal_target` (422, media on a
    FOLDER), `budget_exceeded` (422), `store_unavailable` (503, no presign). **Done:** YAML valid, all `$ref`
    resolve, operationIds unique; auto-picked by the backend glob + added to the web/native generators in
    their phases.
- [x] **2 — native crypto.** Port **`chunk_frame`** (`sealFrame`/`openFrame`) to apple `VeyqCrypto` + android
  `:crypto` (`nonce(12) ‖ AEAD(K_blob, nonce, plaintext, aad = u32be(index) ‖ u32be(total))`; symWrap/symUnwrap
  already native). **Done:** apple `V1ConformanceTests` green incl. `testChunkFrame`; android `:crypto:test`
  green incl. `chunkFrame` — byte-identical to the vector on both. (`dedup_token` deliberately not ported.)
- [x] **3 — backend.** `BlobStore` port (`presignPut`/`presignGet`/`stat`, return null when unsupported) with
  `S3BlobStore` (MinIO SDK, second client for signing against `s3-public-endpoint`) + `InMemoryBlobStore`
  (dev/test, no presign); `BlobStorePreflight` + `/api/setup` `directTransport` flag; `V11__media.sql`
  (`veyq_blob` + manifest, `veyq_media` blob→node, `veyq_upload_session` OPEN-state) ; `features/media`
  (`BlobEntity`/`MediaEntity`/`UploadSession` + `MediaStore`/`MediaHandler`/`MediaResource` impl of the
  generated `MediaApi`) under the scope-access guard; declare-then-verify (`complete` stats objects, enforces
  budget, purges on excess); media target rule (MEMORY/CHAPTER only). **Done:** `./gradlew check` green;
  `MediaSliceTest` (start→complete→attach→list metadata round-trip with the in-memory store, partial-batch
  per-item result, illegal_target 422 on a FOLDER, budget_exceeded 422, non-member 404, unauth 401;
  operator-blind: stored ciphertext/wrap/manifest verbatim); the byte transport via `BlobBatchE2ETest`
  (`@EnabledIfEnvironmentVariable VEYQ_S3_E2E=1`, real MinIO on `:19000`) — default `gradle test` skips it
  cleanly.
- [x] **4 — web.** `features/memory/media.ts` (+ the `media` spec in `generate:api` + `mediaApi()`):
  `addMedia` (seal chunks under a random `K_blob`, wrap under `K_mem`, batch-start length-1, direct PUT,
  complete+attach) / `listMedia` / `openMedia` (batch-download + `openFrame` + unwrap `K_blob`). **Done:**
  `npm run check` green (`media.test.ts` round-trips chunk seal/open + `K_blob` wrap/open + the batch control
  plane with real v1 crypto + mocked API); the transport via an opt-in `media.e2e.test.ts` (`VEYQ_E2E=1`).
- [x] **5 — apple.** The same in `VeyqClient` (+ generated `MediaAPI` module). **Done when:** `swift test`
  green (seal→start→complete→list→open round-trip, mocked transport) + iOS build; opt-in live e2e.
- [x] **6 — android.** The same in `:client` (`"media"` added to the generator specs). **Done when:**
  `:client:test` green (same round-trip) + `:app:assembleDebug`; opt-in `MediaE2ETest` (`VEYQ_S3_E2E`).
- [x] **infra.** `infra/docker-compose.yml` MinIO `--profile s3` + Traefik path-route (no stripPrefix,
  passHostHeader) so the bundled store is same-origin (no CORS) for the web app. **Done:** documented; the
  e2e suites run against it locally.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Media blob: seal → direct-to-store → attach → open (operator-blind, batch-shaped) | Status |
|---|---|---|
| contracts | `media.openapi.yaml` (batch endpoints under a scope) | ✅ |
| crypto (native) | `chunk_frame` (`sealFrame`/`openFrame`) ported apple + android | ✅ |
| backend | `BlobStore` + `veyq_blob`/`veyq_media`/`veyq_upload_session` + guards | ✅ |
| web | data layer (seal chunks + `K_blob` + direct transport) | ✅ |
| apple / android | data layer (+ generated media client) | ✅ |
| infra | bundled MinIO profile + Traefik route | ✅ |
| UI (all) | the gallery/curation UI | ◻ **deferred to the shell** |
| windows / linux | — | ◻ (when those clients exist) |

## Definition of done

A member can attach + read back encrypted media on a Memory/Chapter on web·apple·android: each blob sealed as
chunked AEAD under a random `K_blob` wrapped under `K_mem`, bytes transferred **direct-to-store** via presigned
URLs (never through the backend), recorded as blob + media, **batch-shaped** control plane (length-1 lists this
slice), declare-then-verify budget + media-target + scope-access enforced server-side, **no content dedup**,
operator-blind throughout, all hermetic suites green + the opt-in store e2e green locally. **Resumable
uploads, thumbnails, share mirrors and the gallery UI are follow-on slices.** Then the next Memory slice
(journal under `K_mem`, or the gallery/curation UI on the shell).

> **Follow-up — [cr-0029](cr-0029-vendor-neutral-blob-store.md):** the MinIO bits this CR introduced were
> swapped for a **vendor-neutral** stack right after — the backend signs via the **AWS S3 SDK v2** (any
> S3-compatible store) and the bundled dev/self-host store is **Garage**, because MinIO's OSS server was
> archived in 2026 and its successor AIStor is commercial. No wire/crypto/client change.
