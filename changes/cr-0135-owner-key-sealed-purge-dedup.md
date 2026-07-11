---
id: cr-0135
title: Backend cleanups P2c-rest — OwnerKey sealed type + shared media-purge (audit P2c)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Finish the two structural refactors deferred from [cr-0128](cr-0128-backend-cleanups-deadcode-enum-decode.md)
(audit bundle 66 / P2c): (1) model the `owner_key` string-union (`user:{id}` XOR `share:{id}`) as a typed
`OwnerKey` sealed interface, and (2) collapse the FK-ordered per-media hard-delete that was copy-pasted across
three stores into one shared `MediaStore.purgeMediaRows`. Pure internal cleanup — no wire, DB, or behavior
change.

## Context & constraints

- **`owner_key` is a wire+DB contract, so `OwnerKey` is internal-only with a byte-identical round-trip.** The
  format is pinned by the OpenAPI spec (`BucketSummary`/`MediaSummary.ownerKey`) and the web client parses the
  `user:`/`share:` prefixes itself, and it is the `veyq_bucket.owner_key` column. So `OwnerKey.format()`/`parse()`
  reproduce the exact strings; the DTO fields, the DB column and the Panache equality queries keep using the
  string. The type just centralizes the convention (one place for the prefixes) and turns the 4 construction
  sites into typed construction + the single server-side `share:` inspection into an exhaustive `when`. The
  server still only ever *parses* the `share:` form (to fetch a contributor's sealed name); a `user:` key stays
  opaque relay. The `BucketEntity.OWNER_*_PREFIX` constants move onto `OwnerKey`.
- **`purgeMediaRows(media)` unifies three identical bodies:** `MediaStore.purgeTrashedBefore` (retention purge),
  `MediaStore.purgeMedia` (member-expedited purge) and `SharingStore.deleteContributedMedia` (contributor
  delete-own) all did derivative → media → blob-row deletes in FK order and returned the reclaim keys. The
  helper keeps that contract exactly — it deletes rows only and returns keys; the **caller** keeps its
  `@Transactional` boundary, the best-effort post-commit `blobStore.delete`, and any revision bump (only the
  contributor delete removes a *live* media, so only it calls `bumpMediaRevision` — the two trash purges don't).
  `SharingStore`'s duplicate private `deleteBlobRow` (a verbatim copy of `MediaStore`'s) is deleted.
- **`ScopePurge.eraseScope` is deliberately NOT folded in** — it is per-scope and set-based, deletes the blob
  *objects* first (before rows) and never touches blob rows one-by-one, so unifying it would break its ordering.
- **Invariant — operator-blind: preserved, not threatened.** `owner_key`'s byte format is unchanged (the client
  contract and DB value are identical), and the purge is erasure-only (no key/plaintext). No path changes what
  the operator sees.
- **Verify:** `cd backend && ./gradlew check` — the slice tests pin the exact `owner_key` byte values
  (`MediaBucketsSliceTest`: `equalTo("user:$userId")`) so any round-trip drift fails the build.

## Phases

- [x] 1 backend — add `OwnerKey.kt`; rewire the 4 construct + 1 parse sites (`MediaStore`, `MediaHandler`) and
  drop the `BucketEntity` prefix constants; add `MediaStore.purgeMediaRows`, collapse the 3 purge bodies + the
  duplicate `deleteBlobRow`. · **Done:** `./gradlew check` → **BUILD SUCCESSFUL**, 298 tests / 0 failures / 1
  skipped; owner_key byte values unchanged (slice tests green); trust-chain guards green.

## Definition of done

`./gradlew check` green with the owner_key value-pins intact; `owner_key` constructed/parsed only through
`OwnerKey`; the three media purges share `purgeMediaRows`; no wire/DB/behaviour change; trust-chain guards
green. No `satisfies:` — a structural refactor.

## Retrospective

- **Sparring paid off before a keystroke.** The blast-radius scan showed `owner_key` is a hard wire+DB contract
  (spec-pinned, client-parsed), not an internal encoding — so I flagged that `OwnerKey` can only be an internal
  type with a lossless round-trip and that its value is modest, and confirmed scope with the human rather than
  silently "cleaning up" a contract string. That framing is what kept the refactor safe (format byte-identical)
  instead of an accidental API break.
- **Right call to leave `ScopePurge` out.** The tempting "dedup all four" would have broken its objects-first,
  set-based ordering; the scan caught that the fourth site is a different shape, so the helper stays honest
  (per-media, rows-only, caller-owns-objects) and the two trash purges + the contributor delete collapse cleanly.
- **Next time:** nothing notable — the pre-scan + value-pinning tests made this a low-risk change on an
  otherwise sensitive (attribution + erasure) surface.
