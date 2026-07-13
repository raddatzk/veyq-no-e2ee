---
id: cr-0152
title: Web Trash tab — align the selection bar + tile-select with the Beiträge (buckets) mechanic
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

The Papierkorb (Trash) tab's batch UI is inconsistent with the Beiträge tab (`MemoryBuckets.vue`, cr-0122) and
reads as buggy: the action bar (**Restore / Purge**) only appears **after** a selection (no always-visible bar, no
select-all/deselect-all), and selecting is unclean — the whole tile toggles selection and the check is a passive
`<span>`, whereas Beiträge selects via a **real `<input type="checkbox">`** and reserves the tile click for the
lightbox. Port the Beiträge selection **mechanic** to the Trash tab: an always-visible selection bar with a
select-all toggle + visible-but-disabled actions, and a real per-tile checkbox.

## Context & constraints

### Reference — the Beiträge mechanic (`MemoryBuckets.vue`, cr-0122)

- `allSelected` computed (`filtered.length > 0 && every selected`) + `toggleSelectAll()` (select-all-visible ↔
  clear) + `selectedLabel` = `{selected} of {visible}`.
- An **always-visible** `.bucket-selection-bar` (shown whenever there are items): count label, a `.veyq-text-button`
  select-all toggle (`selectAllVisible` / `deselectAll`), then the action buttons **always rendered** with
  `:disabled="!selected.size"`.
- Per-tile overlay: a real `<label class="media-tile-check"><input type="checkbox" @click.stop @change="…">` +
  the 3-dot menu; the tile body `@open` opens the lightbox.

### `features/memory/useMediaTrash.ts`

Add, mirroring the buckets composable:
- `allTrashSelected` = `computed(() => trashedItems.value.length > 0 && trashedItems.value.every((m) =>
  selectedTrash.value.has(m.mediaId)))`.
- `toggleSelectAllTrash()` = `selectedTrash.value = allTrashSelected.value ? new Set() : new
  Set(trashedItems.value.map((m) => m.mediaId))`.
- `selectedTrashLabel` = `computed(() => t('memory.trash.selected', { selected: selectedTrash.value.size, visible:
  trashedItems.value.length }))`.
- Keep `toggleTrashSelect` (now driven by the checkbox). `clearTrashSelection` is superseded by deselect-all →
  drop it from the return + its template use. Return the three new members.

### i18n (`src/i18n/index.ts`, de + en `memory.trash`)

- Change `selected` from `'{count} selected'` → `'{selected} of {visible} selected'` (en) /
  `'{selected} von {visible} ausgewählt'` (de).
- Add `selectAllVisible` (`'Select all'` / `'Alle auswählen'`) + `deselectAll` (`'Deselect all'` / `'Alle
  abwählen'`), matching the buckets wording.

### `MemoryDetail.vue` — trash template + CSS

- Replace the `v-if="selectedTrash.size"` batch block with an **always-visible** selection bar (the toolbar
  already only renders in the `v-else` where `trashedItems.length > 0`): `selectedTrashLabel` count + a
  `.veyq-text-button` select-all toggle + **Restore** (`:disabled="!selectedTrash.size"`) + **Purge** (danger,
  `:disabled="!selectedTrash.size"`). Drop the separate Cancel button (deselect-all covers it).
- Replace the passive `<span class="media-tile-check">` overlay with the Beiträge overlay: `<label
  class="media-tile-check" :class="{ 'is-on': … }"><input type="checkbox" :checked="…" @click.stop
  @change="toggleTrashSelect(item.media.mediaId)">…`.
- **The one intentional deviation:** the trash tile body keeps `@open="toggleTrashSelect(…)"` (still toggles
  select) rather than opening a lightbox — the Trash tab has no lightbox surface (its lightbox actions
  delete/set-cover make no sense on an already-trashed item). So a trash tile is selectable by **either** the
  checkbox or the tile body; the checkbox is the clear affordance the bug was missing. (A read-only trash preview
  lightbox is a possible follow-up, out of scope here.)
- CSS: align the trash bar's action buttons with `.bucket-action-button` — `:disabled { opacity: 0.45; cursor:
  default }`, danger hover, icon+label — reusing the existing `.memory-trash-*` scoped classes.

### Verify

- `cd web && npm run check` (eslint + `vue-tsc` + test + build), incl. a new `useMediaTrash.test.ts` case for
  `toggleSelectAllTrash` / `allTrashSelected`.
- **Live smoke** (in-app nav): open the Trash tab with ≥1 item → the selection bar is visible with Restore/Purge
  **disabled**; the checkbox selects a tile (bar count updates, actions enable); select-all toggles all ↔ none;
  Restore on the selection re-lists the gallery; console clean.

## Phases

- [x] 1 web — `useMediaTrash` (+ test) `allTrashSelected`/`toggleSelectAllTrash`/`selectedTrashLabel`; i18n keys;
  `MemoryDetail` trash bar (always-visible + select-all + disabled actions) + real checkbox overlay + CSS. `npm
  run check` green; live smoke clean.

## Definition of done

`npm run check` green; the Trash tab shows an always-visible selection bar (count + select-all/deselect-all +
disabled-until-selected Restore/Purge) and selects via a real checkbox — matching the Beiträge mechanic. Live
smoke confirms the bar + select-all + enable/disable + restore. No change to the soft-delete / restore / purge
semantics (operator-blind unaffected).

## Retrospective

Went as scoped — a clean port of the cr-0122 mechanic. The composable additions mirror the buckets
trio verbatim; `clearTrashSelection` (superseded by deselect-all) had no other callers, so dropping
it was free. The danger action was re-aligned in passing: `.memory-trash-purge-action` used to be
permanently red — now, like `.bucket-action-danger`, it colors on hover only, so the disabled/idle
bar reads uniformly muted. Live smoke on `veyq.localhost:8081` (2 trashed items): bar always visible
with both actions `disabled` (opacity 0.45) at zero selection, per-tile checkbox drives count +
enable, select-all ↔ deselect-all toggles 0↔2, single-item restore re-listed the gallery (header
33→34) leaving the other trashed item untouched; console clean. One pre-existing observation, out
of scope: the gallery renders 35 tiles while the header says "34 Objekte" — the count moved
consistently (34→33→34) through delete/restore, so it's unrelated to the selection mechanic.

Follow-up (user feedback on the first cut): the bar still *looked* different from Beiträge — the trash
count was bold and the actions were `.veyq-text-button`s (white surface, bold 1rem) instead of the
transparent `.bucket-action-button` look. Fixed by promoting `.bucket-action-button`/`-label`/`-danger`
from MemoryBuckets' scoped CSS into the global `memory.css` (the same sharing move cr-0125 made for the
`.media-tile-*` overlay classes) and using them for Restore/Purge; the trash count/gap now match
`.bucket-selection-count`/`-bar` exactly. Lesson: "port the mechanic" implicitly includes the visual
classes — aligning behavior but keeping the old button classes left a visible seam the CR's CSS phrasing
("align with .bucket-action-button") had actually already called for.

Second follow-up (user): destructive buttons have their own convention (ui-web-styleguide "destructive =
danger with an explicit label", concretely the legacy-ported `.asset-bucket-danger-button` pattern) —
danger text + `--app-danger-line` border at rest, `--app-danger-soft` fill on hover. cr-0122's
`.bucket-action-danger` had only a hover tint; the now-shared class was switched to the convention, which
fixes all three destructive bar actions at once (Beiträge bucket-delete + move-to-trash, Papierkorb purge).
Verified light + dark (tokens are theme-aware in base.css).
