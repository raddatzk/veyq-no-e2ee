---
id: cr-0150
title: Web shared thumbnail pipeline — dedupe MemoryDetail ⇄ ShareView + resolve the donation ownership tangle (audit P3d)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Audit finding #74 (**P3d**), the share-viewer thumbnail-pipeline half: `MemoryDetail.fetchThumbs`/`mergeItems`
and `ShareView.load` hand-roll the **same** decrypt-per-tile → object-URL-map pipeline (prune removed → donate
optimistic previews → `Promise.all` decrypt each tile). Factor it into one shared helper built on the cr-0148
`useObjectUrls` primitive, and in doing so **resolve the donation ownership tangle** so a thumbnail URL has a
single revoke owner. This is the correctness groundwork the `useMediaGalleryLive` extraction (#77 → cr-0151)
depends on — the tangle is exactly what blocked that extraction.

## Context & constraints

### The tangle (why this comes first)

An optimistic-upload preview URL is minted by `useOptimisticTiles` (`URL.createObjectURL(file)`) and, once the
media is listed, **donated** into the gallery `thumbs` map: `donorPreviewFor(mediaId)` flips a `donated` flag and
hands the URL over. From that instant revocation ownership moves from the composable (which stops revoking —
guards at `useOptimisticTiles.ts:66,104`) to the gallery, which tracks it in a `donatedThumbs` Set and revokes it
when the real thumbnail supersedes it, the tile drops, or on unmount. So "revoke exactly once" is split across
**two owners** signalled by two flags — the fragility that `useObjectUrls` (single-owner, auto-revoke-on-unmount)
was built to remove. Both `MemoryDetail` (`thumbs` L72 + `donatedThumbs` L270) and `ShareView` (`thumbs` L66 +
`donatedThumbs` L67) carry an identical copy of this.

### Phase 1 — `useObjectUrls.adopt(key, url)` (+ tests)

Extend the keyed primitive with `adopt(key, url: string)`: store an **externally-minted** URL under `key` and
take over its lifecycle (revoke-on-replace, `drop`, `clear`, unmount) — the same guarantees as `set`, but for a
URL the caller already created rather than one minted from a `Blob`. This lets the gallery's `useObjectUrls`
**own the donated URL as the sole revoker**: on donation the caller `adopt`s it; `useOptimisticTiles` keeps its
"don't revoke after handoff" guard (it minted it, it hands it off), and the double-owner split collapses to one
owner. A later real-thumbnail `set(key, blob)` revokes the adopted URL exactly once (existing `set` behaviour).
Add cases to `useObjectUrls.test.ts`: adopt stores + exposes; a subsequent `set`/`drop`/`clear`/unmount revokes
the adopted URL exactly once; adopt over an existing key revokes the previous.

### Phase 2 — the shared pipeline helper (on `useObjectUrls`)

`features/memory/useThumbnailPipeline.ts` — `useThumbnailPipeline(ctx)` owning the common core of
`fetchThumbs`/`mergeItems`-prune (MemoryDetail L377-430) + `ShareView.load`'s decrypt loop (L235-267):

- Holds its own `useObjectUrls()` (the `thumbs` map, exposed as `Ref<Map<string,string>>` for templates + the
  cover/buckets readers) and an internal donated-**placeholder** set (now a *decrypt-pending* hint only, no
  longer an ownership marker — ownership is uniformly `useObjectUrls`).
- `sync(listedItems)` does: (a) `drop` thumbs whose mediaId is no longer listed; (b) for each newly-listed
  derivable item without a thumb, `donorPreviewFor(id)` → `adopt` the donated preview + mark it placeholder;
  (c) `Promise.all` decrypt each derivable item lacking a **real** thumb → `set(id, blob)` (clears placeholder),
  guarded by an `isCurrent()` node/share race check.
- Parameterised by the only varying seams: `decryptTile(item) → Promise<Uint8Array|null>` (`openGrid` vs
  `openShareGrid`), `isDerivable(item)`, `idOf(item)`, `donorPreviewFor`, `isCurrent`. Everything else (prune,
  donate-via-adopt, skip-real-thumb, revoke-on-replace, unmount teardown) is identical and lives here once.
- Exposes `thumbs` (the ref), `sync`, `dropThumb(id)`, `revokeAll` (for the callers' delete/reset paths).

### Phase 3 — rebase both callers onto the helper

- **`MemoryDetail.vue`:** `thumbs`/`donatedThumbs`/`setThumb`/`revokeThumbs`/`fetchThumbs` + the thumbnail half of
  `mergeItems` route through `useThumbnailPipeline({ decryptTile: openGrid…, isCurrent: node-race })`. The
  `thumbs` ref it still hands to `useMemoryCover` (L198) + `MemoryBuckets` (L783) + the lightbox is now
  `pipeline.thumbs` (same `Ref<Map>` shape — templates + readers unchanged). `mergeItems` keeps its **non-thumb**
  responsibilities (items merge + `pendingDelete` prune); only its thumb-prune/donate delegates. `removeFromGrid`
  /`upsertItems` delete paths call `pipeline.dropThumb`.
- **`ShareView.vue`:** the `load` decrypt loop (L235-267) + `thumbs`/`donatedThumbs`/`revokeThumbs` route through
  the same helper with `decryptTile: openShareGrid(shareId, si, pwToken)` and the per-item `si.memoryKey`
  adapter. Share-only concerns (role gating, password shell, uploader empty-view, name pre-screen, no-poll) stay
  untouched in `ShareView`.
- Delete the two hand-rolled `thumbs` Maps + `donatedThumbs` Sets + the duplicated revoke/setThumb functions.

### Scope discipline (what this CR does NOT do)

No extraction of the gallery core out of MemoryDetail (that is cr-0151 / #77) — this CR only factors the
thumbnail pipeline + fixes ownership, leaving `items`/polling/`load`/`pollMedia`/`upsertItems` where they are. No
change to the decrypt layer (`openBlobFromUrls` already dedupes authed vs share, media.ts L742) or to
`useOptimisticTiles`'s minting (only its handoff is now `adopt`ed by a single owner).

### Verify

- `cd web && npm run check` (eslint + `vue-tsc` + test + build) — incl. the new `useObjectUrls.adopt` tests and a
  new `useThumbnailPipeline` unit test (mock `decryptTile` + a fake `donorPreviewFor`; assert prune/donate/decrypt
  + single-revoke on supersede/drop/unmount).
- **Live smoke** (session up on `veyq.localhost:8081`): (1) a Memory gallery decrypts thumbnails + no leaked/broken
  tiles; (2) an **optimistic upload** shows the donated preview then swaps to the real thumbnail with no flicker
  or double-revoke (console clean); (3) a **public share** page renders its thumbnails through the same helper.

## Phases

- [x] 1 web — `useObjectUrls.adopt(key, url)` (via a shared `put` that revokes-unless-idempotent) + 5 tests
  (adopt stores + auto-revokes on unmount; `set`/`drop` after adopt revoke the adopted url exactly once; re-adopt
  same url is a no-op). `npm run check` green.
- [x] 2 web — `useThumbnailPipeline.ts` (`reconcile`/`decrypt`/`sync`/`dropThumb`/`revokeAll`, generic over the
  item type, parameterised by `idOf`/`isDerivable`/`decryptTile`/`donorPreviewFor` + a per-call `isCurrent`
  guard) + a 6-case unit test (decrypt installs/skips, donate→supersede revokes the donation once, prune revokes,
  stale-`isCurrent` drop, drop/revokeAll/unmount). `npm run check` green.
- [x] 3 web — rebased `MemoryDetail` (mergeItems→`reconcile`, fetchThumbs→`decrypt`, removeFromGrid/upsertItems→
  `dropThumb`, revoke→`revokeAll`) + `ShareView` (the whole load decrypt loop → `reconcile`+`decrypt`) onto the
  helper; deleted both hand-rolled `thumbs` Maps + `donatedThumbs` Sets + the `setThumb`/`revokeThumbs`/
  `fetchThumbs` copies. `npm run check` green (**605 tests, +10**); no direct `createObjectURL`/`revokeObjectURL`
  left in either component.

## Definition of done

`npm run check` green; one `useThumbnailPipeline` (on `useObjectUrls`, with `adopt`) is the single source of the
decrypt-per-tile → object-URL pipeline for both MemoryDetail and ShareView; the duplicated `thumbs` Maps +
`donatedThumbs` Sets are gone; a thumbnail URL has exactly one revoke owner (`useObjectUrls`). Live smoke shows
the optimistic-donation handoff still swaps cleanly. Operator-blind unaffected (client-side decrypt + in-memory
object URLs only). Unblocks cr-0151 (#77 `useMediaGalleryLive`).

## Retrospective

- **`adopt` was the key that unlocked the extraction.** The donation tangle wasn't really about the pipeline dup
  — it was that a thumbnail URL had two revoke owners. Giving `useObjectUrls` an `adopt` (own an externally-minted
  URL) collapsed it to one owner; the `donatedThumbs` Set survived only as a **decrypt-pending** hint inside the
  helper, no longer part of revocation correctness. Everything downstream (the #77 extraction) gets a clean seam.
- **The dedupe fixed a latent leak for free.** ShareView revoked a superseded/pruned thumbnail only `if !donated`
  (L237/L259 pre-change) — so a donated preview that got superseded by the real thumbnail **leaked**. MemoryDetail
  revoked unconditionally. Unifying on the single-owner `store.set`/`store.drop` (revoke-always) made ShareView
  correct by construction. Noted because it's an unintended-but-welcome correctness win, not just a dedup.
- **Live smoke: MemoryDetail proven, ShareView by construction.** The internal gallery was live-verified on
  `veyq.localhost:8081` — 34 thumbnails decode + render through the new pipeline, the header cover (which reads
  `thumbs`) renders, console clean. ShareView uses the **identical** `useThumbnailPipeline` (only `decryptTile`
  differs) and is covered by the helper's unit test + green type-check/build; its share-page live check was not
  run separately (it would mean minting a throwaway share + a full-page nav that drops the session). The
  donation→adopt→supersede-revoke-once path — the actual risk — is asserted directly in the unit test.
- **Process nit:** a full-URL `navigate` in the preview pane drops the session-scoped login (fresh context);
  in-app hash routing (`location.hash = …`) keeps it. Use that for logged-in smokes.
