---
id: cr-0122
title: Beiträge (buckets) selection + action-bar redesign — web UX
status: done
satisfies: [uc.memory.buckets, en.memory.buckets]
adrs: ["0034"]
invariants: [operator-blind-e2ee]
---

## Goal

Rework the **Beiträge** tab (`MemoryBuckets.vue`, cr-0113) into an explicit selection + action-bar UX the user asked
for while curating uploader contributions. **Web-only** — no contract/backend/crypto change (operator-blind is
untouched: buckets stay opaque `owner_key` + client-sealed names, media ops are the existing endpoints). The
member-can-curate-a-contributor-bucket parts that the server currently forbids (move a `share:` bucket's media,
manage its sub-buckets) are **out of scope here** and land in **cr-0123** (the authz relaxation); this CR only
un-hides selection where an action already works today (delete = any member soft-deletes to trash).

Concretely, on the Beiträge tab:

1. **Explicit action bar, no 3-dot menus.** The two ellipsis menus (bucket options beside the dropdown; the
   selection menu) are gone. Bucket actions (Upload, New sub-bucket, Rename, Delete bucket) become **buttons to the
   right of the bucket dropdown**; media actions (Move, Delete) become **visible buttons** in the selection bar,
   active only when something is selected.
2. **One row.** The bucket dropdown (now content-width, not full-width) + its action buttons + the selection bar
   (`X of Y selected` · a single **Select all ⇄ Deselect all** toggle · Move · Delete) sit on **one line**, wrapping
   on narrow widths.
3. **Selection works on contributor buckets too.** The per-tile checkbox + the selection bar were gated to your own
   buckets (`canManage`); they now show for **every** bucket. **Delete** works there today (soft-delete to trash is
   any-member). **Move** stays disabled for a foreign bucket (no same-owner target until cr-0123).
4. **Tile chrome.** Checkbox **top-left**, the per-tile 3-dot **top-right** (was checkbox top-right, 3-dot
   bottom-right).
5. **Delete without a modal + undo.** Deleting selected media goes **straight to the Papierkorb** (it is a
   recoverable soft-delete) — the type-the-count confirm dialog is gone — with a **local undo bar** ("N moved to
   trash — Undo", auto-dismiss) that calls `restoreMedia` on the just-trashed ids.
6. **Sharper, smaller tiles.** Bucket tiles rendered too large (the thumbnail upscaled + soft). The bucket grid now
   uses a **smaller target row height** than the gallery so the thumbnail renders crisply. (If the user prefers
   keeping the size and loading the higher-res *preview* derivative instead, that's a follow-up — it needs a new
   blob-load path.)

## Context & constraints

- **Web-only, one component.** All changes live in `web/src/features/memory/components/MemoryBuckets.vue` + its
  scoped CSS + `web/src/i18n/index.ts` (a few labels: the select-all toggle, the undo bar). `MediaGrid.vue` is
  reused with a smaller `targetHeight` — no change to the grid itself.
- **Operator-blind untouched.** No contract/backend/crypto change. Delete = existing `deleteMedia` (soft-delete);
  undo = existing `restoreMedia`; move = existing `moveBucketMedia` (own buckets only, unchanged here).
- **Honest gating.** For a **contributor** bucket, Move renders **disabled** (its target set is empty until the
  cr-0123 same-owner relaxation) and the bucket-management buttons follow the existing `canManage` enable rules —
  no button silently 403s.
- **Undo is local, not global.** veyq has no toast system; rather than add app-wide infra, the undo lives as a
  small in-component bar with an auto-dismiss timer. A shared toast can come later if more surfaces need it.
- **Verify:** `cd web && npm run check` (adds a `MemoryBuckets` component test — selection toggle, delete-to-trash
  without a dialog + undo restore, contributor-bucket selection shown but move disabled); the 5 doc guards; a live
  smoke deferred to the user (preview logged out) — checkbox top-left/3-dot top-right, one-line action bar, delete
  with undo, crisper tiles.

## Phases

- [x] 1 **web** — `MemoryBuckets.vue`: dropped both 3-dot menus; bucket-action buttons right of a content-width
  dropdown; one-line action bar with the Select-all⇄Deselect-all toggle + visible Move/Delete; ungated the per-tile
  checkbox + selection bar for all buckets; tile checkbox top-left + 3-dot top-right; delete straight to trash + a
  local undo bar (`restoreMedia`); smaller bucket-grid `targetHeight`. i18n: `deselectAll`, `deletedToTrash`,
  `undo`. · **Done:** `npm run check` green (**562 tests** incl. a new `MemoryBuckets.test.ts` — select-all + delete
  to trash + undo restore; contributor bucket shows selection but Move disabled), 5 guards green. Live smoke deferred
  to the user (preview logged out). Move for a contributor bucket stays disabled (`moveTargets` empty when the active
  bucket isn't yours) — it lights up with cr-0123.
- [ ] 2 **apple / android** — **n/a** (web-only Beiträge UX; native has its own bucket surface, if/when built).

## Follow-ups (separate CRs)

- **cr-0123** — the backend/contract **authz relaxation** so a member can Move a contributor bucket's media within
  the same owner-root and manage its sub-buckets (create/rename/delete), the contributor's **primary** untouchable.
  Then this tab's Move + bucket-management buttons light up for contributor buckets. (Backend map already scouted:
  `MediaHandler`/`MediaStore` owner-key gates → "same `owner_key` for source+target, caller is a member"; pure
  authz logic, no schema.)
- Optional: load the **preview** derivative for tiles (higher-res) if the smaller-tile fix isn't enough.

## Follow-up — default to your own bucket + remember the last (2026-07-10)

The picker defaulted to the **alphabetically-first** bucket, which is often a **contributor's** (e.g. "Aaa …") —
so opening Beiträge dropped you into someone else's contributions. Now `MemoryBuckets.vue` opens on the bucket the
user last chose **for that node** (persisted in `localStorage` under `veyq.beitraege.bucket.<nodeId>`), else **your
own** bucket (the member primary — `isPrimary && owner_key = user:…`, incl. the virtual self-primary), else the
first row (`preferredBucketId`). `chooseBucket` writes the memory (best-effort — a private-mode/quota throw is
swallowed). Web-only, no contract/backend/crypto change. `npm run check` green (`MemoryBuckets.test.ts` gained a
default-to-own case + a remembers-last-per-node case; the contributor cases now select the bucket explicitly since
it is no longer the default), 5 guards green.

## Follow-up — tile image fit: same grid as the gallery (2026-07-10)

The bucket tiles rendered oversized + cropped/distorted (**not** the same grid as the Galerie). Root cause: the
image-fit CSS — `width/height: 100%; object-fit: cover` — lives on the gallery's **scoped** `.gallery-tile img`
rule (MemoryDetail.vue only); the global `.media-grid-tile-media` carries **only** the opacity fade. So the bucket
`<img>` got no sizing and rendered at natural resolution. (This — not tile size — was also the real cause of the
earlier "zu groß" report; the phase-1 `targetHeight` shrink treated the wrong cause.) Fix: replicate the fit rule in
MemoryBuckets' scoped CSS (`.bucket-tile-open img`) **and revert the `targetHeight` override** so the bucket grid is
now byte-for-byte the gallery's justified grid (default height, `object-fit: cover`). Web-only; `npm run check` green.
