---
id: cr-0063
title: Grid / list view toggle for the memory card grid (web)
status: done
satisfies: [uc.memory.browse, uc.memory.chapters]
adrs: ["0010"]
invariants: [operator-blind-e2ee]
---

## Goal

Let the user toggle the memory **card grid** between the current cards and a plain **list**, everywhere the grid
appears — the browse overview (folders + Memories) and the Chapters tab — with the choice **remembered** across
sessions.

## Context & constraints

- **One remembered preference, app-wide.** A `viewMode` (`grid` | `list`) preference in `localStorage`
  (`veyq:view-mode`), following the `useThemeState` pattern (a `ref` + a setter/toggle, no init side-effects
  needed). Both the browse grids and the Chapters-tab grid read the same value, so switching in one place
  applies everywhere.
- **CSS modifier, not new templates.** The card layouts (`MemoryCard`, folder card, create card) stay; the
  `.memory-card-grid` container gets an `is-list` class (driven by `viewMode`) whose global overrides in
  `memory.css` relayout it as a single-column list — `MemoryCard`'s 16:9 cover + stacked text becomes a compact
  row (small icon left, name right); the already-row-shaped folder/create cards just go full-width.
- **A small reusable toggle.** A `ViewModeToggle` (two icon buttons — grid / list — sharing `useViewMode`),
  placed in the browse toolbar and above the Chapters-tab grid. Both instances drive the one preference.
- **No data/crypto change.** Pure presentation; operator-blindness untouched.
- **Scope.** Web only. Deferred: per-view (non-shared) modes, sort/density options, native parity.

## Phases

- [x] 1 preference + toggle (web) — `useViewMode` store (`grid`|`list`, persisted to `localStorage`); a
  `ViewModeToggle` component (grid/list icon buttons). · Done when: `npm run check` green; a `useViewMode` test
  round-trips toggle + persistence and a `ViewModeToggle` test flips the mode.
- [x] 2 wire + list styles (web) — the `.memory-card-grid.is-list` overrides in `memory.css` (single column +
  compact `MemoryCard` row); `MemoryBrowse` gets the toggle + binds `is-list` on both grids; `MemoryDetail`'s
  Chapters tab gets the toggle + `is-list` on its grid; i18n for the toggle labels. · Done when:
  `npm run check` green; the `MemoryDetail`/`MemoryBrowse` tests still pass with the toggle present.
- [x] 3 verify — live browser smoke: toggle to list in the browse → the Memories/folders render as a list;
  open a Memory → the Chapters tab is a list too (shared preference); reload → the choice persists. · Done
  when: the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Grid / list card-grid toggle | Status |
|---|---|---|
| web | `viewMode` preference + `ViewModeToggle` + `is-list` layout | ✅ done |
| apple / android | their browse/detail UI is its own later CR | ◻ **separate (native)** |

## Definition of done

The user can switch the memory card grid between cards and a list in both the browse and the Chapters tab; the
choice is remembered and shared across both. No data/crypto change. `npm run check` + the three guards green;
the smoke confirmed.

## Retrospective

Added an app-wide grid/list toggle for the memory card grid. **Preference:** a `useViewMode` store
(`grid`|`list`, persisted to `localStorage` `veyq:view-mode`, following the `useThemeState` pattern — a `ref` +
`setViewMode`/`toggleViewMode`). **Toggle:** a small `ViewModeToggle` (two icon buttons, grid `pi-th-large` /
list `pi-bars`, `aria-pressed`) placed in the browse toolbar and above the Chapters-tab grid — both instances
share the one preference, so switching anywhere applies everywhere. **List layout:** a `.memory-card-grid.is-list`
CSS modifier in `memory.css` (higher specificity than the base card rules) collapses the grid to one column and
turns `MemoryCard`'s 16:9 cover + stacked text into a compact row (40px icon left, name right, ~58px tall); the
already-row-shaped folder/create cards just go full-width. No template changes to the cards; no data/crypto change.

`npm run check` (201 web tests: `useViewMode` toggle+persist, `ViewModeToggle` flip, plus the existing
browse/detail suites with the toggle present) + the three guards green.

**Live smoke (preview): confirmed.** Browse → clicked list: "Smoke Memory" + the folders/create cards render as
full-width rows (card height 250px → 58px), `localStorage` = `list`. Opened the Memory → the Chapters tab is a
list too (shared preference, chapter card 58px). Reload → the choice persisted (`list`, toggle + grid still in
list mode).

**Deferred:** per-view (non-shared) modes, sort/density options, native (apple/android) parity.
