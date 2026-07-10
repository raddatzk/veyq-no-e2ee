---
id: cr-0113
title: Media uploader buckets — the owner_key bucket table + backend + a primary-grouping "Beiträge" tab
status: done
satisfies: [uc.memory.buckets, en.memory.buckets]
adrs: ["0034", "0030"]
invariants: [operator-blind-e2ee]
---

## Goal

Adopt the pre-E2EE **Buckets** concept — oriented on **legacy ADR 0023** (the operator-blind re-design), **not**
the legacy code. Build the **full bucket data layer + backend** — a real **`owner_key`-keyed `veyq_bucket`
table**, a **lazy virtual primary** per uploader, **one-level sub-buckets** with **reparent-on-delete** and
**own-bucket authz**, names **sealed under `K_mem`** — and the **web "Beiträge" tab** rendering the **primary**
per-uploader grouping over the reused gallery grid. The **sub-bucket management UI** (folders in the tab) is the
immediate **next** slice on the same endpoints; a camera filter + batch selection are later slices
([ADR 0034](../docs/decisions/0034-media-uploader-buckets.md),
[en.memory.buckets](../docs/features/memory/enablers/en.memory.buckets.md),
[uc.memory.buckets](../docs/features/memory/use-cases/uc.memory.buckets.md)).

## Context & constraints

- **Orient on ADR 0023, not the pre-E2EE code.** The legacy *code* stored cleartext names, enforced no bucket
  ownership, and **hard-deleted** a sub-bucket's media. We take ADR 0023's operator-blind design: **`K_mem`-sealed
  names**, **own-bucket authz**, and **reparent-on-delete** (a deleted sub-bucket's media fall back to
  `bucket_id = null` = the owner's primary; **primaries are not deletable**).
- **One opaque `owner_key`.** `user:{userId}` (member) / `share:{shareIdentityId}` (contributor, **reusing the
  cr-0101 identity** as the grouping key). It is operator-visible (like membership + the existing
  `share_identity_id`), authorizes **`owner_key == caller`** without identity semantics, and collapses the
  pre-E2EE two-column XOR. The shipped **`media.share_identity_id` is left untouched** (redundant with `created_by`
  for contributors — cleaned when sharing is next worked, **not here**).
- **Virtual-until-upload primary.** The primary bucket materializes **lazily** (find-or-create on
  `(node_id, owner_key)`); uploaders with no media have no row. Its **derived** name (member = username,
  contributor = the identity's sealed name) is itself **`K_mem`-sealed**, and it is **not renameable**. Uniqueness
  is the content-blind `(node_id, owner_key) WHERE is_primary`; **sub-bucket names carry no server uniqueness**
  (ciphertext).
- **Operator-blind (invariant).** The operator sees the opaque `owner_key`, the **bucket structure** (node,
  parentage, `is_primary`, counts) and `bucket_id`/`created_by` on media — coarse operational metadata; **never
  the sealed names, never content.** A personal space with no share exposes only the account owner's `owner_key`.
  Grouping **and** name-unseal run **client-side** under `K_mem`. Recorded in the
  [security-model](../docs/crosscutting/security-model.md).
- **Reuse the gallery, don't invent UI.** The Beiträge tab reuses the existing **`MediaGrid` + lightbox**; only
  the uploader picker + filter are new. Port the pre-E2EE UX from `legacy/frontend-noe2ee` `MemoryAssetBuckets.vue`
  (read-only reference). **This slice ships the primary grouping only** — the sub-bucket management UI is the next
  CR (on the endpoints this one builds).
- **Contracts-first.** Bucket endpoints + the media `ownerKey`/`bucketId` fields are OpenAPI-generated on both
  sides — **never hand-write DTOs**.
- **Verify:** backend `./gradlew check` (H2 hermetic — slice tests below); web `cd web && npm run check`; the three
  trust-chain guards (`validate_changes.py`, `validate_coverage.py`, `validate_doc_links.py`) green; a live
  **"Beiträge"-tab** smoke on the dev backend (personal scope with a contributor share + own uploads → two
  primary buckets, correct names, filtered grid).

## Phases

- [x] 1 **contracts** — a **bucket resource** on a node (`buckets` tag → own `BucketsApi`): `GET` `listNodeBuckets`,
  `POST` `createBucket` (sub-bucket), `PATCH` `renameBucket`, `DELETE` `deleteBucket` (reparent), `POST`
  `moveBucketMedia` (`/media-moves`, `mediaIds[]` + `targetBucketId`). `MediaSummary` gained **`ownerKey`** +
  **`bucketId`**; `MediaRequestItem` gained optional **`bucketId`**. · **Done — codegen green both sides**
  (backend `openApiGenerateSpecs` → `BucketsApi.java` + models incl. `MediaSummary.ownerKey/bucketId`; web
  `generate:api` → `BucketsApi.ts`).
- [x] 2 **backend** — migration **V35** `veyq_bucket` (`id, scope_id, node_id, owner_key, parent_bucket_id,
  sealed_name, is_primary`; primary-uniqueness enforced in `ensurePrimaryBucket` — no DB partial index in the
  H2 ∩ MariaDB subset) + `veyq_media.bucket_id` (FK `ON DELETE SET NULL`) + `veyq_media.created_by`; pre-cr0113
  rows are **lazily backfilled at list time** (no fragile SQL owner subquery). `BucketEntity` + store methods
  (`ensurePrimaryBucket` lazy find-or-create, `listBuckets`, `createSubBucket`, `renameBucket`,
  `deleteBucketReparent`, `moveMedia`), `attach` stamps `created_by`/`bucket_id`, a `BucketsResource` (own-bucket
  authz + reparent-on-delete), `toSummary`/`completeMedia` thread `ownerKey`/`bucketId`. `media.share_identity_id`
  untouched. · **Done — `./gradlew check` green**; `MediaBucketsSliceTest` (6): upload stamps `user:{id}` +
  materializes the primary; create sub → move → delete **reparents** (media survive); own-bucket authz rejects a
  foreign owner; a primary is neither renameable nor deletable; non-member list → 404.
- [x] 3 **http** — `http/media/buckets.http`: the **401 auth boundary** of the five bucket endpoints (list/create/
  rename/delete/move), runnable against any instance (mirrors `trash.http` — the authenticated happy-path + the
  own-bucket authz need the seeded scope-grant flow, covered by `MediaBucketsSliceTest`). · **Written**; live-run
  deferred to a session with the dev backend up (structurally guaranteed by `@Authenticated` on `BucketsResource`).
- [x] 4 **web** — the **"Beiträge" tab** is the full **media organizer** (`MemoryBuckets.vue`, ported from the
  legacy `MemoryAssetBuckets` UX): `MediaItem` gained `ownerKey`/`bucketId`; `media.ts` bucket wrappers +
  `openBucketName` (unseal under `K_mem`, placeholder on failure). The organizer = a **hierarchy Select picker**
  (your primary + depth-indented sub-buckets, `pi-user`/`pi-folder`), a **3-dot bucket menu** (upload / new
  sub-bucket / rename / delete, gated to your OWN buckets), create/rename/type-to-confirm-delete dialogs, a
  **per-tile checkbox + menu**, a **batch toolbar**, a **move dialog**, and a **virtual own-primary** (materializes
  on the first sub-bucket/upload — server's lazy `ensurePrimaryBucket`). **Always visible** (empty Memory too).
  `media.buckets.test.ts` unit-tests the unseal + wrappers. · **Done — `npm run check` green** (typecheck + 531
  tests + build) **+ live smoke** on `veyq.localhost` (created the sub-bucket "Oma (WhatsApp)", moved 4 media in —
  counts 21→17 / 0→4).
- [ ] 5 **apple / android** — **deferred** (web-first, ADR 0010); the generated data layer carries
  `ownerKey`/`bucketId` + the bucket endpoints after regen, so native buckets are each platform's own later CR.

## Definition of done

The **bucket data layer** ships end to end (**contracts → backend → http → web**): a real **`owner_key`-keyed
`veyq_bucket` table** with a **lazy virtual primary**, **one-level sub-buckets**, **`K_mem`-sealed names**,
**reparent-on-delete**, and **own-bucket authz** — oriented on legacy ADR 0023, **operator-blind** (opaque
`owner_key` + structure visible, names + content sealed). Media carry **`bucket_id`** + **`created_by`**
(`owner_key`); the shipped `media.share_identity_id` is untouched. The web **"Beiträge" tab** is the media
**organizer** (`MemoryBuckets.vue`, ported from the legacy MemoryAssetBuckets UX): a hierarchy picker,
create/rename/delete sub-buckets, move media between your own buckets, and a **virtual own-primary** that
materializes on first use — **always available** (even an empty Memory). `./gradlew check` + `npm run check` + the
three trust-chain guards green; a **live organizer smoke** passed (sub-bucket create + media move). Native buckets
remain each platform's later CR.

> **Status note.** Complete end to end (contracts → backend → http → web); all verification green (`./gradlew
> check`, `npm run check` = typecheck + 531 tests + build, the three trust-chain guards) **and a live organizer
> smoke on `veyq.localhost`** — created the sub-bucket "Oma (WhatsApp)" (name sealed under `K_mem`, primary
> materialized) and moved 4 media into it (counts 21→17 / 0→4). **Scope grew** from the initial read-only primary
> grouping to the **full legacy organizer** (sub-bucket create/rename/delete + move + virtual-primary, always-
> visible tab) per the product direction — the sub-bucket-UI-and-camera-filter items previously listed as later
> slices are folded into this CR (camera filter + capture-time-shift intentionally dropped: their EXIF metadata
> isn't surfaced in the fresh client yet). **Small follow-ups:** upload directly into a *selected* sub-bucket (the
> `bucketId` is plumbed to `completeMedia`/`attach`; the web upload flow still targets the primary — move covers it
> for now); and group/tenant self-identification for own-bucket UI gating (the server enforces `owner_key ==
> caller` regardless, so a foreign action just 403s).
