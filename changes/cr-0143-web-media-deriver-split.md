---
id: cr-0143
title: Web media.ts split — extract the on-device pixel stage into mediaDeriver.ts (audit P4a, part 1)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

First slice of audit finding #77 (**P4a**, "split `media.ts` (953)"): lift the self-contained **on-device pixel
stage** out of the 954-line `features/memory/media.ts` god-module into its own `features/memory/mediaDeriver.ts`.

The pixel stage (decode → progressive-halving downscale → JPEG re-encode; a video's first-frame poster) is a
distinct concern from the rest of `media.ts` (the crypto/transport/read-back data layer) and the single largest
cohesive block in it (~150 lines). It has one clean, one-directional dependency edge, so it lifts out without a
cycle.

## Context & constraints

- **New file:** `features/memory/mediaDeriver.ts` — `MediaDeriver` interface, `scaleToMaxEdge` (pure),
  `createBrowserDeriver` (Canvas + `<video>`), the private `drawToJpeg`/`drawStep`/`onceEvent` helpers, and
  `deriveTiers`; plus the deriver-owned `TIER_SPECS`, `Dimensions`, `AddMediaSource`. Imports only
  `MediaDerivativeKind` from the generated client — **no import from `media.ts`**, so the edge is one-way
  (`media.ts` → `mediaDeriver.ts`), no circular import.
- **`media.ts` shrinks 954 → 788 lines** (−166). It imports `createBrowserDeriver`/`deriveTiers` (+ the moved
  types) from `./mediaDeriver` for its add-media wiring, and **re-exports** the public deriver symbols
  (`scaleToMaxEdge`, `TIER_SPECS`, `createBrowserDeriver`, `MediaDeriver`, `AddMediaSource`, `Dimensions`) so the
  existing importers — `share.ts`, `useOptimisticTiles.ts`, `uploadQueue.ts`, and 5 test files — keep importing
  from `./media` unchanged. **Zero import churn, pure code move.**
- **Behaviour-preserving.** No logic changed — code moved verbatim. Proven by the full suite: `media.test.ts`
  (drives the deriver via a stub + `scaleToMaxEdge`/`TIER_SPECS`), `MemoryDetail.optimistic.test.ts`,
  `MediaLightbox.test.ts`, `MemoryDetail.test.ts` all import the re-exported symbols and stay green. No
  observable behaviour to browser-smoke — this is a module-boundary move fully covered by the unit suite.
- **Deferred to their own CRs (rest of #77), per breadth-first scope discipline:** the further `media.ts` splits
  (upload / read-back / trash / buckets are the other cohesive seams) and the headline **`MemoryDetail.vue`
  decompose** (1557-line component → `useMemoryDate`/`useMemoryLocation`/`useMemoryCover`/`useMediaGalleryLive`
  composables + `<MemoryTrashTab>`). Those are UI-invasive (need a browser smoke) and the
  `useMediaGalleryLive`/object-URL parts overlap the deferred #74 gallery/thumbnail-pipeline work — best done
  together, not piecemeal.
- **Verify:** `cd web && npm run check` (lint + typecheck + test + build).

## Phases

- [x] 1 web — add `mediaDeriver.ts` (pixel stage + `TIER_SPECS`/`Dimensions`/`AddMediaSource`); remove the block
  from `media.ts`; import what add-media needs + re-export the public API. · **Done:** `npm run check` green —
  eslint clean, `vue-tsc` clean, **581 tests pass** (4 e2e skipped), build OK.

## Definition of done

`npm run check` green; the pixel stage lives in `mediaDeriver.ts`; `media.ts` is ~170 lines lighter; every
existing `./media` importer is unchanged via the re-export. Operator-blind unaffected (no wire/crypto change).

## Retrospective

- **Cut the cleanest seam first.** Of `media.ts`'s concerns, the pixel deriver is the most independent (one-way
  dependency, its own test coverage) — extracting it first shrinks the god-module ~18% with near-zero risk and
  sets the module boundary the later splits follow.
- **Re-export = zero churn.** Forwarding the public symbols from `media.ts` kept 8 importers + 5 test files
  untouched, so the diff is a pure move the suite proves is a no-op.
- **Held the UI-heavy headline back.** The `MemoryDetail.vue` decompose overlaps the deferred #74 gallery work
  and needs browser verification — kept out of this data-layer slice on purpose.
