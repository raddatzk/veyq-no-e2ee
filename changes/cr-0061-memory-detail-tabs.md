---
id: cr-0061
title: Memory detail tabs — Gallery / Chapters (web)
status: done
satisfies: [uc.memory.gallery, uc.memory.chapters]
adrs: ["0010"]
invariants: [operator-blind-e2ee]
---

## Goal

Give the open Memory/chapter detail view the **tabbed layout** from the design source (`frontend-noe2ee`
`MemoryDetail` + `memoryTabs`): a `Tabs` bar separating the **Gallery** (the media grid) from **Chapters** (the
nested Memories). Only the two tabs that have real content this slice — the design's `journal / map / playlists /
contributions / info` tabs arrive with their features, not as empty placeholders.

## Context & constraints

- **Port the shape, not placeholders.** Use PrimeVue `Tabs / TabList / Tab / TabPanels / TabPanel` like the
  design source. Two tabs: **Gallery** (the existing grid + its loading/empty/error states) and **Chapters**
  (the nested-Memory list, promoted out of the cr-0060 chip strip into its own panel with the *Add chapter*
  action + an empty state). No `journal/map/...` tabs until those features exist.
- **Active tab is local state, not the hash.** The `#fragment` already carries the tree path
  (`#~me/reisen/rom`, cr-0031) — so the active tab is a component `ref` (default `gallery`), reset when the
  open node changes. (Deep-linking a tab is a later nicety; out of scope.)
- **No data-layer / crypto change.** Pure presentation re-org of `MemoryDetail.vue`; the gallery + chapter
  data paths (operator-blind, cr-0049/0059/0060) are untouched. The Chapters tab reuses the cr-0060
  `chapters` prop + `openChapter`/`createChapter` emits.
- **Reuse the existing UI** (`web/CLAUDE.md`): the grid/tiles/chips classes stay; only the surrounding layout
  becomes tabbed. Header actions (upload, add-chapter, favorite, rename, delete) stay in the header.
- **Scope.** Web only. **Deferred:** the other design tabs (journal/map/playlists/contributions/info) with
  their features; tab deep-linking; native parity.

## Phases

- [x] 1 tabs (web) — wrap `MemoryDetail`'s content in `Tabs` with a **Gallery** panel (the grid + states) and a
  **Chapters** panel (the chapter list out of the chip strip, with the *Add chapter* action + empty state);
  active tab a local `ref` reset on node change; i18n for the tab labels. · Done when: `npm run check` green;
  the `MemoryDetail` test renders both tabs, switches to Chapters, and still drives chapter open/create.
- [x] 2 coverage + verify — note the tabbed detail layout on
  [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml) +
  [uc.memory.chapters.yaml](../docs/coverage/features/memory/use-cases/uc.memory.chapters.yaml); live browser
  smoke: open a Memory → Gallery tab shows the grid, Chapters tab lists the chapter + adds one. · Done when:
  the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Tabbed Memory detail (Gallery / Chapters) | Status |
|---|---|---|
| web | `Tabs` layout over the existing gallery + chapter panels | ✅ done |
| apple / android | their detail UI is its own later CR | ◻ **separate (native)** |

## Definition of done

The open Memory/chapter shows a Gallery tab (the media grid) and a Chapters tab (the nested Memories, with add
+ open), ported from the design source; active tab is local state. No data/crypto change, operator-blindness
untouched. `npm run check` + the three guards green; the smoke confirmed.

## Retrospective

Ported the design source's tabbed detail layout into `MemoryDetail.vue`: a PrimeVue `Tabs` bar with a
**Gallery** panel (the existing justified grid + its loading/empty/error states) and a **Chapters** panel (the
cr-0060 nested-Memory list, promoted out of the chip strip into its own panel with a count badge on the tab, an
empty state, and the *Add chapter* action). Active tab is a local `ref` (default `gallery`), reset when the
open node changes — the `#fragment` keeps carrying the tree path. No data-layer or crypto change; the
operator-blind gallery/chapter paths (cr-0049/0059/0060) are untouched. Only the two tabs with real content
ship — journal/map/playlists arrive with their features.

`npm run check` (198 web tests; the `MemoryDetail` test now installs PrimeVue + stubs the tooltip directive,
renders both tabs, switches to Chapters, and still drives chapter open/create) + the three guards green.

**Live smoke (preview): confirmed.** Opening the Smoke Memory shows a **Galerie** tab (2 tiles) and a
**Kapitel ①** tab; switching to Chapters shows the "Tag 1" chip, switching back shows the grid — a
Galerie→Kapitel→Galerie pass produced zero console errors.

**Surfaced:** PrimeVue `Tabs` needs the PrimeVue service injected, so the `MemoryDetail` test (which mounted
with only i18n) had to add `PrimeVue` to its plugins — `Button` had worked without it, `Tabs` did not.

**Deferred:** the remaining design tabs (journal/map/playlists/contributions/info) with their features, tab
deep-linking via the URL, and native (apple/android) detail parity.
