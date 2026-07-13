---
id: cr-0151
title: Web MemoryDetail decompose — extract the useMediaGalleryLive core (audit P4a, final slice)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Final slice of audit finding #77 (**P4a**, decompose the god-component `MemoryDetail.vue`): lift the **live-gallery
core** — the listed media, its thumbnail pipeline, the cr-0107 live poll, load/merge/upsert, optimistic tiles, the
justified layout, and the optimistic-delete/undo — into a `useMediaGalleryLive` composable. This is the donation-
tangled knot the earlier slices (cr-0145/0146/0147) deliberately left behind; cr-0150 resolved its thumbnail
ownership, so this is now a **behaviour-preserving structural move** onto a clean seam. `MemoryDetail.vue`
(currently ~1180 lines) is left as orchestration + template + dialogs.

## Context & constraints

### `features/memory/useMediaGalleryLive.ts` — what it OWNS

`useMediaGalleryLive(ctx)` owns the gallery core, instantiating `useOptimisticTiles` + `useThumbnailPipeline`
(cr-0150) internally (they belong to the gallery):

- **State:** `items`, `status`, `mediaRevision`/`deleteRevision` (the cr-0107 poll cursors), `pendingDelete`
  (cr-0111 tombstone), `sortDir`, the lightbox `lightboxIndex`/`lightboxItems`, and the `undo` snackbar state.
- **Derived:** `listedMediaIds`, `sortedItems` (date sort, undated-last), `aspectRatios`, `layoutItems` (optimistic
  tiles pinned ahead of media → `MediaGrid`), `hasContent`.
- **Load / live:** `load({reset})`, `mergeItems` (→ `pipeline.reconcile` + `pendingDelete`), `pollMedia`,
  `upsertItems`; the poll lifecycle — a `watch([node,memoryKey])` that tears down + `load({reset:true})` +
  `startMediaRevisionPolling`, and `onBeforeUnmount` teardown (`stopMediaRevisionPolling`). Thumbnails run through
  the injected `pipeline` (decrypt = `openGrid(scopeId, memoryKey, item)`).
- **Delete/undo:** `onDeleteFromLightbox`, `removeFromGrid`, `showUndo`/`clearUndo`/`onUndoDelete`, and
  `liftTombstone(id)` (drop a `pendingDelete` entry — the trash callback) + `onCapturedAtUpdated` (a lightbox date
  edit patches the item in place so the grid re-sorts).
- **Lightbox helpers:** `openLightboxAt`, `openBucketLightbox`.

### The ctx (host → core)

Getters mirroring the other slices: `scopeId()`, `node()`, `memoryKey()`. Plus **`onSettle: () => Promise<void>`**
— the reconcile settle point `load`/`pollMedia` call after a list refresh (open-time self-heal + post-upload),
which MemoryDetail wires to `reconcileAuto()` then `reconcileAutoLocation()`.

### The ordering knot (why the wiring is specific)

`useMemoryDate`/`useMemoryLocation` take `items` and return `reconcileAuto`/`reconcileAutoLocation`; the gallery
owns `items` and needs those reconcilers via `onSettle`. So in MemoryDetail: **create the gallery first**
(`onSettle: () => settle()`, a deferred closure), then `useMemoryDate`/`useMemoryLocation` on `gallery.items`,
then define `settle()` = `reconcileAuto()` → `reconcileAutoLocation()`. `settle` runs only inside the async
load/poll (long after setup), so the late binding is safe (no TDZ). `useMemoryCover` takes `gallery.thumbs`;
`useMediaTrash` takes `reloadGallery: () => gallery.load()` + `liftTombstone: gallery.liftTombstone`.

### `MemoryDetail.vue` — what STAYS + what it reads back

Stays: props/emits, i18n/capabilities/viewMode, the upload picker (`flushSelection`→`enqueueUploads` with
`onFileDone: () => gallery.pollMedia()` / `onComplete: () => gallery.load()`), the sibling-slice wiring
(date/location/cover/trash), the template + all dialogs, and the undo snackbar markup (bound to `gallery.undo`).
Exposed back for the template + siblings + dialogs: `items`, `thumbs`, `layoutItems`, `status`, `hasContent`,
`sortDir`, `lightboxItems`/`lightboxIndex`, `openLightboxAt`/`openBucketLightbox`, `load`/`pollMedia`,
`onDeleteFromLightbox`/`removeFromGrid`/`onUndoDelete`/`undo`, `liftTombstone`, `onCapturedAtUpdated`. The template
binds these (destructured), so the markup is unchanged.

### Behaviour-preserving + coverage

The move is behaviour-preserving: no change to the load/poll/merge/delete semantics or the operator-blind path.
Add **`useMediaGalleryLive.test.ts`** (mount harness, `./media` + `./mediaRevisionPolling` mocked): `load` lists +
decrypts + lands `ready`; `mergeItems` keeps a `pendingDelete` tombstone out; `pollMedia` upserts a delta +
decrypts only it; `removeFromGrid` tombstones + drops the thumb + fires undo; `onUndoDelete` restores. The
existing `MemoryDetail.test.ts` + `MemoryDetail.optimistic.test.ts` must stay green (the template contract is
unchanged).

### Verify

- `cd web && npm run check` (eslint + `vue-tsc` + test + build), incl. the new test; the two existing MemoryDetail
  tests stay green.
- **Live smoke** (in-app hash nav to keep the session): the Memory gallery lists + renders thumbnails; a lightbox
  delete tombstones + the undo restores; the live poll still auto-inserts (or at least the grid stays stable);
  console clean. (MemoryDetail is the only consumer — no share-page surface here.)

## Phases

- [x] 1 web — added `useMediaGalleryLive.ts` (the whole live-gallery core, owning `useOptimisticTiles` +
  `useThumbnailPipeline` internally + its own reset/poll `watch` + unmount teardown) + `useMediaGalleryLive.test.ts`
  (3 cases: load lists+decrypts+settles; an optimistic delete arms undo + a stale poll can't resurrect it via the
  `pendingDelete` tombstone; undo restores + re-lists). Replaced the gallery core in `MemoryDetail.vue` with the
  composable call + destructure; wired the ordering knot (gallery first with `onSettle: () => settle()`, then
  date/location on `gallery.items`, `settle()` = `reconcileAuto` → `reconcileAutoLocation` late-bound); the
  slimmed reset `watch` now only handles tab + trash reset. **Done:** `npm run check` green (**608 tests**, +3);
  `MemoryDetail.vue` **1180 → 941 lines** (1557 at the start of #77); the two existing MemoryDetail integration
  tests (`MemoryDetail.test.ts` + `.optimistic.test.ts`) stay green unchanged.

## Definition of done

`npm run check` green (incl. the new test + the two existing MemoryDetail tests); the live-gallery core lives in
`useMediaGalleryLive.ts` with a unit test; `MemoryDetail.vue` is materially lighter (target ≤ ~900 lines) with its
template + dialogs unchanged; the thumbnail pipeline (cr-0150) + optimistic tiles are owned by the composable.
Operator-blind unaffected. Closes audit #77 (P4a).

## Retrospective

- **The seam cr-0150 built paid off.** With thumbnail ownership already encapsulated in `useThumbnailPipeline`,
  lifting the gallery core was a structural move, not a correctness one — the composable instantiates the pipeline
  + optimistic tiles internally and the host never touches an object URL again.
- **The ordering knot resolved with a late-bound `settle()`.** date/location need `items` (from the gallery) yet
  the gallery needs their reconcilers (via `onSettle`). Creating the gallery first with `onSettle: () => settle()`
  — a closure that only resolves `reconcileAuto`/`reconcileAutoLocation` when the async load/poll actually fires,
  long after setup — breaks the cycle with no TDZ. The composable owning its OWN reset/poll `watch` let the
  host's watch shrink to just tab + trash reset.
- **Behaviour preservation proven by the pre-existing integration tests + a live pass.** `MemoryDetail.test.ts` +
  `MemoryDetail.optimistic.test.ts` mount the real component (now on `useMediaGalleryLive`) and assert the
  gallery/optimistic/reconcile behaviour — both stay green **unchanged**, stronger than the composable unit test
  alone for a behaviour-preserving move. **Live smoke passed** on `veyq.localhost:8081` (in-app hash nav to keep
  the session): the gallery lists + decodes all 34 thumbnails (`load` path); a lightbox soft-delete removes the
  tile 34→33 (`onDeleteFromLightbox`→`removeFromGrid`); restoring it from the Trash tab re-lists the gallery 33→34,
  exercising the `reloadGallery` (`gallery.load`) + `liftTombstone` seam back into the composable; console clean
  throughout.
- **Closes #77 (P4a).** MemoryDetail went 1557 → 941 across the P4a slices (date/location/cover/trash/gallery all
  lifted); what remains is orchestration + template + dialogs + the upload picker.
