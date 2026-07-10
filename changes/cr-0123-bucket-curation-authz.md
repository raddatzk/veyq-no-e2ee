---
id: cr-0123
title: Bucket curation authz — a member may move within any owner-root + manage a contributor's sub-buckets
status: done
satisfies: [uc.memory.buckets, en.memory.buckets]
adrs: ["0034"]
invariants: [operator-blind-e2ee]
---

## Goal

Relax the bucket authz so a scope **member** can curate a **contributor's** contributions the way cr-0122's UI
already offers — **without ever crossing an owner boundary or touching a primary**. The rule the user chose:
re-bucket only **within one root** (same `owner_key`); never move media from one user to another; a contributor's
**primary** bucket (their name/identity) stays untouchable.

Three server relaxations (pure authz logic — no schema, operator-blind untouched: only opaque `owner_key`/`bucketId`
+ client-sealed names are touched):

1. **Move (`moveBucketMedia`).** Was `owner_key == caller` for every source medium + the target. Now: all source
   media **and** the target bucket must share **one** `owner_key` (the same root), and the caller must be a scope
   member. `targetBucketId = null` ⇒ that shared owner's primary (not the caller's). No cross-owner (enforced by a
   single distinct source owner). A member can now move a contributor's media between the contributor's buckets.
2. **Rename / delete sub-bucket.** Was `owner_key == caller`. Now: **any scope member** may rename/delete a
   **sub-bucket**; the **primary** stays non-renameable/non-deletable (the `is_primary` block survives). Delete still
   reparents the sub's media to that root's own primary (unchanged).
3. **Create sub-bucket under a chosen root.** `CreateBucketRequest` gains an optional **`parentBucketId`**: null ⇒
   the caller's own primary (back-compat); a primary's id ⇒ a sub under **that** root, stamped with **that root's**
   `owner_key` (so a member can add sub-buckets under a contributor's root). The parent must be a **primary**.

Then the cr-0122 web UI lights up for contributor buckets: `moveTargets` becomes **same-owner** (so Move works
inside a contributor root), and the bucket-management buttons (new sub / rename / delete) enable for a
contributor's **sub-buckets** while its primary stays read-only.

## Context & constraints

- **Invariant — no cross-owner, primary protected.** The move stays owner-scoped (one distinct source owner ==
  target owner); rename/delete keep the `is_primary` guard; create derives the sub's `owner_key` from the parent
  **primary**, never the caller. Attribution (`owner_key`) is preserved on every path — a contribution stays the
  contributor's.
- **Operator-blind untouched.** No new plaintext/keys; `sealed_name` stays client-sealed ciphertext; the server
  compares opaque `owner_key` strings only. No migration (existing `owner_key`/`is_primary`/`created_by` columns).
- **API-first.** Contract first (`contracts/api/media.openapi.yaml`); the backend regenerates its JAX-RS models.
  Backend authz in `MediaHandler`/`MediaStore` only; the `BucketsResource` 403/404 mappings already exist.
- **Verify:** backend `./gradlew check` (update `MediaBucketsSliceTest` — invert the foreign-sub rename/delete case
  to *allowed*, keep the primary-protection case, add a contributor-root move + a cross-owner-move-rejected case);
  web `cd web && npm run check` (regenerated client + the `MemoryBuckets` move/manage wiring); the 5 guards. A live
  smoke needs a **backend restart** (contract change ⇒ codegen, not hot-reload) + login — deferred to the user.

## Phases

- [x] 1 **contract** — `media.openapi.yaml`: `moveBucketMedia` authz → one shared `owner_key`; `renameBucket`/
  `deleteBucket` → any member, sub only (primary 403); `CreateBucketRequest.parentBucketId` (optional) + a 403 when
  the parent isn't a primary. Descriptions/summaries updated off "caller's own".
- [x] 2 **backend** — `MediaHandler`/`MediaStore`: `moveMedia` derives the shared source owner (`distinct().single`
  → reject on >1) + target must match; `createBucket(parentBucketId)` resolves the root owner from the parent
  primary (`CreateBucketOutcome.Forbidden` when not a primary); rename/delete drop the owner==caller clause, keep
  `isPrimary`. · **Done:** `MediaBucketsSliceTest` updated (inverted the foreign-sub case → allowed; added
  member-creates-under-another-root, non-primary-parent → 403, cross-owner-move → 403; kept primary-protection —
  **9 tests**). `./gradlew check` **BUILD SUCCESSFUL**.
- [x] 3 **web** — `MemoryBuckets.vue`: `moveTargets` = same-owner buckets (contributor-root move enabled);
  sub-bucket management enabled for any sub / create under any primary (primary excluded, no `canManage` gate);
  `createBucket` passes `parentBucketId` (the active primary root). · **Done:** regenerated client; `npm run check`
  green (**563 tests** — `MemoryBuckets.test.ts` gained a same-owner-sibling move-enabled case); 5 guards green
  (coverage `uc.memory.buckets.yaml` retargeted). Live smoke needs a **backend restart** (contract → codegen) — deferred to the user.
- [x] 4 **apple / android** — **n/a** (native bucket surface not built).
