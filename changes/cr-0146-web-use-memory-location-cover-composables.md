---
id: cr-0146
title: Web MemoryDetail decompose — extract useMemoryLocation + useMemoryCover composables (audit P4a, part 3)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Third slice of audit finding #77 (**P4a**, decompose the 1557-line `MemoryDetail.vue`): lift the **Memory-location**
and **Memory-cover** reactive slices into `useMemoryLocation` + `useMemoryCover` — completing the three field-editor
composables the finding names (`useMemoryDate` landed in cr-0145).

## Context & constraints

- **`features/memory/useMemoryLocation.ts`** — `useMemoryLocation(ctx)` (props as getters + the live `items` ref +
  `onMetaUpdated`). Owns the editor/consent state, the header label + `locationResolved`, the medoid/gpsPoints
  computeds, `openLocationEditor`/`enableMaps`, `onLocationSave`, and the geocoding self-heal `reconcileAutoLocation`.
  Calls `useI18n()` + `useMapConfig()` itself; the `./memoryLocation` + `./geocode` + `useMapConfig` imports move
  out of the component.
- **`features/memory/useMemoryCover.ts`** — `useMemoryCover(ctx)` (props as getters + the live gallery `thumbs`
  ref + `onMetaUpdated`). Owns the editor/lightbox-seed state, the decrypted `coverSrc`/`coverExistingSrc`/
  `coverMediaId`, the lightbox `onSetCoverFromPhoto`, `open`/`closeCoverEditor`, and the read-modify-write
  `onCoverSave`. It also owns the **upload-cover object-URL lifecycle** — the `watch(storedCover)` decrypt and, via
  its own `onBeforeUnmount(revokeUploadCover)`, the revoke — so that lifecycle leaves the component entirely (the
  component's unmount hook drops its `revokeUploadCover()` line).
- **`MemoryDetail.vue`:** the two inlined blocks (~160 lines) become two composable calls + destructures (**−156
  net**); the `memoryLocation`/`geocode`/`memoryCover`/`useMapConfig` and the cover-only `media` imports
  (`openBlob`/`uploadCoverBlob`/`deleteCoverBlob`) move out; the now-unused `locale` is dropped from `useI18n()`.
  **Templates unchanged** — every destructured binding keeps the name the markup already uses;
  `reconcileAutoLocation` still runs at the same settle points.
- **Behaviour-preserving, verified:** `MemoryDetail.test.ts` (asserts the sealed location PATCH via
  `lastPatchedLocation`) + `memoryLocation.test.ts`/`.e2e` + `memoryCover.test.ts`/`.e2e` stay green; `vue-tsc`
  clean; the live dev app at `veyq.localhost:8081` mounts with no console errors after HMR.
- **Deferred (rest of #77):** `useMediaGalleryLive` + `<MemoryTrashTab>` — the object-URL/gallery parts overlap
  #74's deferred thumbnail-pipeline dedup, best done together (the cover object-URL handling extracted here is a
  candidate consumer of #74's future `useObjectUrls`); plus the further `media.ts` seams.
- **Verify:** `cd web && npm run check` (lint + typecheck + test + build).

## Phases

- [x] 1 web — add `useMemoryLocation.ts` + `useMemoryCover.ts`; replace the two inlined blocks with composable
  calls; move the location/geocode/mapconfig/cover imports out; move the upload-cover unmount cleanup into the
  cover composable. · **Done:** `npm run check` green — eslint clean, `vue-tsc` clean, **581 tests pass**, build
  OK; live app mounts clean.

## Definition of done

`npm run check` green; the location + cover slices live in their own composables; `MemoryDetail.vue` is ~156 lines
lighter with its templates untouched and the upload-cover object-URL lifecycle (incl. unmount) owned by the cover
composable; the location + cover tests still pass. Operator-blind unaffected (seal/PATCH unchanged).

## Retrospective

- **Lifecycle travels with its slice.** The cover's upload object-URL `watch` + revoke moved into the composable
  (which registers its own `onBeforeUnmount`), so the component's unmount hook shed a concern instead of
  reaching back into the composable — the clean way to extract a slice that owns a resource.
- **Same getter-context seam three times.** date/location/cover all extracted on the identical `() => props.x`
  contract, so the component now reads as a thin host wiring three self-contained slices — the intended shape.
- **Stopped at the gallery boundary.** `useMediaGalleryLive` + the trash tab own the heavy object-URL/thumbnail
  pipeline that overlaps #74; left for that combined CR rather than half-cutting it here.
