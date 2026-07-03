---
id: cr-0074
title: Optimistic in-grid upload tiles — picked photos appear instantly, reconcile to the real thumbnail
status: done
satisfies: [uc.memory.upload]
adrs: []
invariants: [operator-blind-e2ee]
---

## Goal

When a user adds photos to a Memory, show the picked images **immediately as tiles in the gallery grid** — with
an **instant local preview** and a **per-tile progress/error state** — instead of only in the TopBar uploads tray
(cr-0058). Each optimistic tile reconciles into the **real decrypted thumbnail** when the upload + derivative
round-trip completes. Inbox #49; web-only (reuses the existing upload queue + justified layout — no contract,
backend, or http change).

The instant preview is a **local `URL.createObjectURL(file)`** shown only on the user's own device, before the
bytes are sealed; it is never uploaded in the clear and no plaintext crosses the wire (the real upload stays the
existing operator-blind sealed path, cr-0028). So **operator-blindness is unchanged** — this is pure client UX.

## Context & constraints

- **Reuse the upload queue wholesale (cr-0058).** `useUploadQueue()` already exposes reactive `batches` with
  per-file `{ id, status, progress (0..1), error, file }` and a `target { scopeId, nodeId, memoryKey }`. The grid
  **filters batches to its own `node.id`** and turns each still-in-flight file into a tile. No new queue surface
  beyond one addition (below).
- **Reuse the justified layout (cr-0059).** `calculateJustifiedMediaLayout` keys tiles by `id` + `aspectRatio`.
  An optimistic tile supplies `id = upload:<fileId>` and an aspect ratio **probed client-side from the File**
  (`createBrowserDeriver().probeDimensions`, already used at upload) — until probed, a 4:3 fallback. Optimistic
  tiles are **pinned ahead** of the listed media (the freshest action), in enqueue order; on reconcile the real
  tile sorts into its date position.
- **Reconcile by `mediaId`, not by a full reload.** The queue records the **`mediaId`** `addMedia` returns on
  each `UploadFile` (the one new field). An optimistic tile is shown while its file is `queued`/`uploading`/
  `failed`, **or** `done` but its `mediaId` is not yet in the listed `items`; once the real media is listed (same
  `mediaId`), the optimistic tile drops → a clean 1:1 handoff, never a duplicate.
- **`load()` becomes incremental (no flash).** Today the post-batch `onComplete → load()` does `items = []` then
  re-lists, so the grid blinks empty — jarring next to optimistic tiles. Merge the fresh list into `items`
  (revoke thumbs only for removed media) so existing tiles stay put while the new ones slot in.
- **Per-tile affordances kept minimal.** Progress ring/bar bound to `file.progress`; a `failed` tile shows an
  error + **Retry** (`retryFile`). **Cancel stays in the tray** (no per-tile ✕ — avoids grid clutter). A **video**
  upload shows a generic placeholder tile (its poster is a later nicety, not this slice).
- **Scope.** Web only. **Deferred:** inbox #48 (live media updates + cheap change-detection over the mediaId set)
  as a follow-up CR (needs a backend signal); cancel-on-tile; a video first-frame preview; drag-reorder.

## Phases

- [x] 1 web — queue: record the returned **`mediaId`** on each `UploadFile` after `addMedia` completes (the one
  new field), so the grid can reconcile by it. · Done when: a `uploadQueue` unit test asserts a settled file
  carries its `mediaId`, and `npm run check` stays green.
- [x] 2 web — grid (MemoryDetail): subscribe to `useUploadQueue()`, derive this node's **optimistic tiles**
  (client-probed aspect ratio + a local `objectURL` preview + `progress`/`failed`/`retry`), interleave them ahead
  of `sortedItems` in the layout input, render a tile overlay (dimmed preview + progress ring; failed → retry),
  reconcile by `mediaId` (drop the optimistic tile once the real media lists), and make `load()` **incremental**
  (merge, no empty-flash; revoke only removed thumbs). Own the preview `objectURL` lifecycle (revoke on
  drop/unmount). i18n EN+DE for the tile states. Add the `optimistic-in-grid-tiles` acceptance criterion to
  `uc.memory.upload` coverage. · Done when: `npm run check` + trust-chain guards green; unit tests cover the
  optimistic-tile model (build from queue, reconcile by mediaId, revoke) + the incremental merge; a live smoke
  (re-seeded instance): pick 3 photos → they appear instantly with previews + progress → each swaps to its real
  thumbnail with no flash/duplicate; a forced failure shows retry.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Optimistic in-grid upload tiles (instant preview + progress, reconcile by mediaId) | Status |
|---|---|---|
| web | queue `mediaId` + the grid's optimistic tiles + incremental `load()` | ☑ done |
| apple / android | native optimistic-tile parity is their own later CR (web-first, ADR 0010); the upload queues differ per surface | ◻ **separate (native)** |

## Definition of done

Picking photos for a Memory shows them **instantly** as grid tiles — the user's own image as a dimmed local
preview with a progress ring — and each tile **reconciles into the real decrypted thumbnail** on completion with
**no empty-flash and no duplicate** (matched by `mediaId`); a failed upload shows an inline **retry**. The TopBar
tray (cr-0058) still works. **Operator-blind unchanged** — the preview is a local object URL, never uploaded in
the clear. `npm run check` + the trust-chain guards green; the live smoke confirmed.

## Retrospective

Landed web-only, no contract/backend/http change — as scoped.

- **Phase 1 (queue).** One field: `uploadOne` now captures `addMedia`'s `{ mediaId }` onto the settled
  `UploadFile`. A `uploadQueue` unit test pins it. Nothing else in the queue moved.
- **Phase 2 (grid).** The reconcile/selection logic is a **pure module** (`optimisticUpload.ts`:
  `selectOptimisticUploads` + `removedThumbIds` + `optimisticTileId`), unit-tested in isolation; `MemoryDetail`
  owns only the reactive/DOM lifecycle around it (object-URL previews, client-probed aspect ratio, donation).
- **Seamless handoff (beyond the literal drop rule).** The CR spec drops the optimistic tile the instant its
  `mediaId` lists — which alone leaves a ~100–300 ms grey-placeholder gap while the real thumbnail decrypts. To
  honour the "reconciles with no flash" done-criterion, `mergeItems` **donates** the done upload's still-live
  preview object-URL into `thumbs` at merge time; the real decrypted thumbnail then replaces it (revoking the
  donation) when it lands. Single-owner revoke throughout (a `donatedThumbs` set + a `donated` flag on the
  preview) so nothing double-frees or leaks.
- **The one real gotcha: a render deadlock.** First cut gated the grid/empty state on the *measured* layout
  (`tiles.length`). But the justified layout needs the grid element mounted to measure its width — gating the
  grid on measured tiles meant *no width → no tiles → grid never renders → no width*. It silently broke **all**
  existing gallery tests (0 tiles). Fix: gate on **content** (`items.length || optimisticFiles.length`), never
  on the measured layout. Worth remembering for any future grid work.
- **`load()` is now incremental** (`{ reset }`): a Memory/chapter switch clears up front; a post-upload reload
  merges (revoking only dropped thumbnails), so tiles don't blink empty next to the optimistic ones.
- **Verification.** `npm run typecheck` + 364 web tests (incl. the new `optimisticUpload`, `uploadQueue`, and
  `MemoryDetail.optimistic` suites) + `vite build` green; all four doc guards green.
- **Live smoke — confirmed** (re-seeded 127.0.0.1 instance, real backend + garage, browser at `127.0.0.1:8089`).
  Instrumented timeline of a 3-photo batch: at the picker-flush (~1.2 s) three optimistic tiles appeared with
  local `blob:` previews **and** progress rings, pinned ahead of the listed media, held for the ~3.8 s upload
  window, then in one step dropped to zero while the real tiles went 3→6 **all carrying decrypted thumbnails in
  lockstep** — no duplicate, no grey-placeholder gap (the donation handoff working). A forced store failure
  (PUTs rejected) surfaced a failed tile with ⚠ "Upload fehlgeschlagen" + a retry button over the dimmed
  preview; clicking retry (store healed) re-uploaded and reconciled into the real thumbnail (count 15→16, every
  tile a real `blob:` thumbnail). Operator-blind held: previews are local object URLs, the real bytes went the
  sealed presigned-PUT path.

**Operator-blind unchanged:** the preview is a local `URL.createObjectURL(file)` shown only on-device before
the bytes are sealed; the real upload stays the existing sealed direct-to-store path.
