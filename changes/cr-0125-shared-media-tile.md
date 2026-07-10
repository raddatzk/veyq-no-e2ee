---
id: cr-0125
title: Shared MediaTile — one tile format for the Galerie, Papierkorb and Beiträge grids
satisfies: []            # cross-cutting web refactor — de-duplicates the tile markup/CSS, no new use-case
adrs: []
invariants: [operator-blind-e2ee]
status: done
---

## Goal

The three media grids (Galerie, Papierkorb, Beiträge) each hand-rolled their own tile: different wrappers (a
`<button>` here, a `<div>` there), and the crucial image-fit CSS (`width/height: 100%; object-fit: cover`) lived
**scoped** on the gallery's `.gallery-tile img` — so the Beiträge tile, in a different component, never got it and
rendered the thumbnail at natural size (the cr-0122 crop/oversize bug). Extract **one** `MediaTile.vue` used by all
three so they render **byte-for-byte identically** and this class of bug can't recur.

`MediaTile` = a positioned wrapper (`.media-tile`, the tile geometry falls through via `:style`/`:data-media-id`) →
a single clickable image button (`.media-tile-open`, emits `open`) rendering the decrypted thumbnail / file icon /
shimmer placeholder with the fit rule **in the component** → an optional `#overlay` slot (selection check + 3-dot).
A `selected` prop draws the shared outline. The overlay-position classes (`.media-tile-check` top-left,
`.media-tile-menu` top-right) move to global `memory.css` so every grid's overlay sits identically.

Each grid keeps its own interaction, wired onto the one tile:
- **Galerie** — `@open` → the lightbox; no overlay.
- **Papierkorb** — the whole tile toggles selection (`@open` → `toggleTrashSelect`); overlay = the check + a 3-dot.
- **Beiträge** — `@open` → the lightbox; overlay = a real select checkbox + a 3-dot.

## Context & constraints

- **Web-only, no behavior change.** New `web/src/features/memory/components/MediaTile.vue`; `MemoryDetail.vue`
  (Galerie + Papierkorb tiles) and `MemoryBuckets.vue` rewired to it; the per-tab tile CSS
  (`.gallery-tile*`/`.memory-trash-tile*`/`.bucket-tile*`) removed in favour of the component + a small global
  overlay block. No contract/backend/crypto change; operator-blind untouched (still the client-decrypted thumbnail
  object URLs).
- **Verify:** `cd web && npm run check` — `MemoryDetail.test.ts` + `MemoryBuckets.test.ts` retargeted from the old
  per-tab selectors to `.media-tile` / `.media-tile-open` / the overlay classes; the gallery order/open/icon-fallback
  + trash select + bucket select/delete cases stay green. A live smoke (hard-refresh all three tabs — identical
  grids) is the user's, the preview browser being down.

## Phases

- [x] 1 **web** — created `MediaTile.vue` (wrapper + image button + `#overlay` slot + `selected` outline, fit in
  the component); global `.media-tile-check`/`.media-tile-menu` in `memory.css`; rewired Galerie (no overlay) +
  Papierkorb (whole-tile select + a `:deep(.media-tile-img)` dim for trashed media) in `MemoryDetail.vue`, and
  Beiträge (checkbox + menu) in `MemoryBuckets.vue`; dropped the `.gallery-tile*` / `.memory-trash-*tile*` /
  `.bucket-tile*` CSS blocks; retargeted the selectors in `MemoryDetail.test.ts`, `MemoryDetail.optimistic.test.ts`
  and `MemoryBuckets.test.ts`. · **Done:** `npm run check` green (**566 tests**), 5 guards green. The `OptimisticTile`
  (its own `.gallery-tile*` scoped styles) + `ShareView`'s public-share grid are untouched (out of scope). Live smoke
  (hard-refresh all three tabs — identical grids) is the user's.
- [ ] 2 **apple / android** — **n/a** (web tile refactor).
