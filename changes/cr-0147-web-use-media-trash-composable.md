---
id: cr-0147
title: Web MemoryDetail decompose — extract useMediaTrash composable + its first test (audit P4a, part 4)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Fourth slice of audit finding #77 (**P4a**, decompose the 1557-line `MemoryDetail.vue`): lift the whole **Trash
tab** — listing, thumbnails, batch selection, the restore/purge menu, and the irreversible-purge confirm — into a
`useMediaTrash` composable, and give it its **first unit test** (the tab had ~none).

## Context & constraints

- **`features/memory/useMediaTrash.ts`** — `useMediaTrash(ctx)` owns `trashedItems`/`trashThumbs`/`trashStatus`/
  `selectedTrash`/`trashMenu`/`purgeConfirm`, `loadTrash` (lazy list + per-tile thumbnail decrypt), `resetTrash`
  (Memory-switch), the selection + menu + `requestPurge`/`restoreSelected`/`purgeSelected`/`confirmPurge`, and its
  own `onBeforeUnmount(revokeTrashThumbs)`. Two outward effects go back to the host via callbacks: `reloadGallery`
  (a restore re-lists the gallery) and `liftTombstone` (a restore/purge lifts the gallery's optimistic-delete
  tombstone) — the only coupling to the gallery-live slice, made explicit. Calls `useI18n()` itself.
- **Clean seam confirmed before cutting:** the trash tab writes `trashedItems` only itself (loads its own list),
  its `trashThumbs` carry **no** donation semantics (unlike the gallery's `thumbs`/`donatedThumbs`), and its sole
  reach into the gallery is the two callbacks above — so it lifts out without dragging the donation-tangled
  gallery-live core with it.
- **`MemoryDetail.vue`:** the trash state + ~120-line function block become one `useMediaTrash({...})` call +
  destructure; the Memory-switch reset uses `resetTrash()`; the unmount hook sheds `revokeTrashThumbs()`; the
  cover-only-now `media` imports (`listTrash`/`purgeMedia`) and the `MenuItem` import drop out. **Template
  unchanged** (the `<Menu ref="trashMenu">` template ref binds the composable-owned ref). **Component 1358 → 1246
  lines** (**−112**; 1557 at the start of #77).
- **First trash test:** `useMediaTrash.test.ts` (5 cases) mounts the composable in a harness with `./media`
  mocked — asserts `loadTrash` lists + decrypts a thumb per item + lands `ready` (and `error` on failure),
  `restoreSelected` restores each + fires `liftTombstone` + `reloadGallery` once + drops them, `confirmPurge`
  hard-deletes + drops, and `resetTrash` clears. This closes a pre-existing coverage gap and verifies the
  extraction end-to-end.
- **Behaviour-preserving:** the new test + `vue-tsc` + build are green (**586 tests**), and the live dev app at
  `veyq.localhost:8081` mounts with no console errors after HMR.
- **Deferred (rest of #77):** `useMediaGalleryLive` — the donation-tangled live-gallery core (items, thumbs +
  `donatedThumbs`, polling, `mergeItems`/`fetchThumbs`) whose thumbnail pipeline **overlaps #74's deferred
  share-viewer dedup**; best done with that combined work + a shared `useObjectUrls`. Plus the further `media.ts`
  seams.
- **Verify:** `cd web && npm run check` (lint + typecheck + test + build).

## Phases

- [x] 1 web — add `useMediaTrash.ts` + `useMediaTrash.test.ts`; replace the trash state/functions with the
  composable call; route the reset through `resetTrash()`; drop the now-unused imports. · **Done:** `npm run
  check` green — eslint clean, `vue-tsc` clean, **586 tests pass** (incl. the 5 new), build OK; live app mounts
  clean.

## Definition of done

`npm run check` green; the Trash tab lives in `useMediaTrash.ts` with a unit test; `MemoryDetail.vue` is ~112
lines lighter with its template untouched and the trash object-URL lifecycle (incl. unmount) owned by the
composable. Operator-blind unaffected (soft-delete/restore/purge calls unchanged).

## Retrospective

- **Cut where the coupling is thin.** Verified the trash tab's only gallery reach was two effects (re-list +
  tombstone-lift) and no donation — so it extracts cleanly, unlike the gallery-live core it leaves behind. Naming
  those two effects as `reloadGallery`/`liftTombstone` callbacks turned an implicit coupling into a contract.
- **Extracted the gap into coverage.** The trash tab had no test; the move was the moment to add one — so the
  slice both shrinks the god-component and leaves the trash logic better-guarded than it found it.
- **Held the line at the gallery.** `useMediaGalleryLive` is the donation/polling/pipeline knot that overlaps
  #74; left for the combined CR rather than forcing a leaky seam here.
