---
id: cr-0145
title: Web MemoryDetail decompose — extract useMemoryDate composable (audit P4a, part 2)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Second slice of audit finding #77 (**P4a**, decompose the 1557-line `MemoryDetail.vue`): lift the **Memory-date**
reactive slice out of the component into a `useMemoryDate` composable — the first of the finding's named
composables (`useMemoryDate`/`useMemoryLocation`/`useMemoryCover`/`useMediaGalleryLive`).

The date logic (header label, editor open-state, save, and the `auto`-range self-heal) was ~50 lines inlined in
`MemoryDetail.vue`'s `<script setup>`, mixing five concerns in one component. The pure date maths already lived
in `./memoryDate` + `./memoryDateRange`; this pulls the reactive **wiring** around them into its own unit.

## Context & constraints

- **New composable:** `features/memory/useMemoryDate.ts` — `useMemoryDate(ctx)` where `ctx` passes the props as
  **getters** (`scopeId`/`node`/`memoryKey`/`chapters`) so it stays reactive, plus the live `items` ref (`auto`
  materializes from it) and an `onMetaUpdated` callback (the host's `metaUpdated` emit). It calls `useI18n()`
  itself and returns `{ dateEditorOpen, storedDate, autoRange, effectiveDate, dateLabel, datedChapters,
  onDateSave, reconcileAuto }`. Logic moved **verbatim** — same seal/PATCH, same self-heal guards.
- **`MemoryDetail.vue`:** the ~50-line block becomes a 9-line `useMemoryDate({...})` call + destructure (−43 net);
  the `../memoryDate` and `../memoryDateRange` imports (used only by the date block) move into the composable.
  **Template unchanged** — the destructured refs keep the same names (`dateLabel`, `dateEditorOpen`, `storedDate`,
  `autoRange`, `datedChapters`, `onDateSave`) the markup already binds; `reconcileAuto` is still called at the two
  settle points (open-time self-heal + post-upload-batch).
- **Behaviour-preserving, verified three ways:**
  1. `MemoryDetail.test.ts` — the `Memory date — header + auto-reconcile (cr-0067)` describe block mounts the real
     component (now via the composable), drives the date-editor submit, and asserts the sealed `updateNode` PATCH +
     the auto-reconcile. Green.
  2. `vue-tsc` — catches any template binding left dangling by the extraction. Clean.
  3. Live app at `veyq.localhost:8081` (the running dev instance) mounts clean — no console errors, no render
     crash from the module-graph change.
- **Deferred (rest of #77):** `useMemoryLocation`/`useMemoryCover` (same pattern, next), `useMediaGalleryLive` +
  `<MemoryTrashTab>` (the object-URL/gallery parts overlap #74's deferred thumbnail-pipeline dedup — best together),
  and the further `media.ts` seams.
- **Verify:** `cd web && npm run check` (lint + typecheck + test + build).

## Phases

- [x] 1 web — add `useMemoryDate.ts`; replace the inlined date block with the composable call; move the date
  imports. · **Done:** `npm run check` green — eslint clean, `vue-tsc` clean, **581 tests pass** (incl. the date
  describe block), build OK; live app mounts clean at `veyq.localhost:8081`.

## Definition of done

`npm run check` green; the Memory-date slice lives in `useMemoryDate.ts`; `MemoryDetail.vue` is ~43 lines lighter
with its template untouched; the date component tests still pass. Operator-blind unaffected (seal/PATCH unchanged).

## Retrospective

- **Getters, not values, for a composable over `props`.** Passing `() => props.x` keeps the composable reactive
  to the component's props without coupling it to the component — the clean seam for the remaining
  `useMemoryLocation`/`useMemoryCover` extractions to follow.
- **Verbatim move + existing component test = proof.** The date describe block already mounts the component and
  asserts the sealed PATCH, so it validates the extraction end-to-end — no new test needed, no risky live-login
  smoke (which would mean entering the Secret Key).
