---
id: cr-0059
title: Justified gallery grid + E2EE-sealed media dimensions (web/backend)
status: done
satisfies: [uc.memory.gallery]
adrs: ["0012", "0013"]
invariants: [operator-blind-e2ee]
---

## Goal

Replace cr-0049's uniform square grid with the design source's **justified grid** (`frontend-noe2ee`
`MediaGrid` + `calculateJustifiedMediaLayout` — rows of aspect-preserving tiles justified to the container
width, Google-Photos style). The justified layout needs each media's **aspect ratio**, which the operator-blind
server can't provide — so the client **seals the original's `{w,h}` under `K_mem` at upload** and the gallery
unwraps it to lay out the grid (stable, no post-decode reflow).

## Context & constraints

- **Operator-blind dimensions.** A new opaque `wrappedDimensions` on the media = `symWrap(K_mem, {w,h})` (AAD
  context `media-dims`). Set at `completeMedia`, stored + returned verbatim by the blind server (ciphertext, like
  the `K_blob` wraps), unwrapped client-side. Absent for media with no decodable frame (icon tile / default
  aspect).
- **Where `{w,h}` comes from.** `addMedia` already decodes the image to derive tiers; probe the original's
  intrinsic size there (`createImageBitmap` width/height; video `videoWidth/Height`) — best-effort, null when
  undecodable. No extra server round-trip; the server never sees pixels or dimensions.
- **Port the layout math, not the data layer.** `mediaGridLayout.ts` (`calculateJustifiedMediaLayout`,
  `galleryDisplayAspectRatio`) is pure geometry — port it (+ its test) verbatim. Rework `MemoryDetail`'s grid to
  position tiles from the computed layout (container width via `ResizeObserver`, a target row height); items
  without dimensions fall back to the legacy default aspect.
- **Fallback.** A media without `wrappedDimensions` (older media, underivable, or decode failed) uses the
  default display aspect so the grid still lays out — never a broken row.
- **Scope.** Web only (the gallery is web; native galleries are their own CRs). New media seal dimensions;
  pre-existing media (none in practice yet) simply lack them and use the fallback aspect.

## Phases

- [x] 1 contract + backend — add optional opaque `wrappedDimensions` to `MediaRequestItem` (input on complete)
  + `MediaSummary` (output on list); regenerate. Migration `V14__media_dimensions.sql`
  (`veyq_media.wrapped_dimensions TEXT NULL`); `MediaEntity` + the store/handler/resource pass it through
  verbatim. · Done when: `./gradlew check` green; a slice test completes + lists back a media carrying
  `wrappedDimensions` (opaque, server never inspects).
- [x] 2 web data — `addMedia` probes the original's `{w,h}` (best-effort) and seals it under `K_mem` →
  `wrappedDimensions`; `listMedia`/`MediaItem` unwraps it → `dimensions?: { w, h }`. · Done when:
  `npm run check` green; `media.test.ts` round-trips sealed dimensions and tolerates an undecodable/absent case.
- [x] 3 web justified grid — port `mediaGridLayout.ts` (+ test) from `frontend-noe2ee`; rework `MemoryDetail`'s
  grid to the justified layout driven by each item's `dimensions` (with the default-aspect fallback) + a
  `ResizeObserver` for the container width. · Done when: `npm run check` green; the layout math test + a
  MemoryDetail render test pass.
- [x] 4 coverage + verify — update
  [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml) (the grid is now
  justified; dimensions are sealed) + the web part of
  [uc.memory.upload.yaml](../docs/coverage/features/memory/use-cases/uc.memory.upload.yaml); live browser smoke:
  upload a non-square image → the grid tile keeps its aspect ratio (justified row), dimensions sealed/unwrapped.
  · Done when: the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Justified grid + sealed dimensions | Status |
|---|---|---|
| backend / contracts | opaque `wrappedDimensions` on the media (sealed `{w,h}`) | ✅ done |
| web | seal at upload, unwrap + justified `MediaGrid` layout | ✅ done |
| apple / android | data layer can read `wrappedDimensions`; their gallery layout is their own CR | ◻ **separate (native)** |

## Definition of done

The web gallery lays media out in an aspect-preserving justified grid; each new media's dimensions are sealed
under `K_mem` at upload and unwrapped for layout, the server holding only ciphertext. Dimensionless media fall
back to a default aspect (no broken rows). `npm run check` + `./gradlew -p backend check` + the three guards
green; the smoke confirmed.

## Retrospective

Shipped in four phases. **Contract + backend:** an optional opaque `wrappedDimensions` on the media
(`MediaRequestItem` in, `MediaSummary` out), migration `V14__media_dimensions.sql`
(`veyq_media.wrapped_dimensions TEXT NULL`), threaded verbatim through `MediaEntity` →
`MediaStore.attach`/`MediaRow` → handler/resource — the blind server stores + echoes ciphertext and never
inspects it. **Web data:** a new best-effort, optional `MediaDeriver.probeDimensions` (image →
`createImageBitmap`, video → `<video>` metadata); `addMedia` seals the probed `{w,h}` under `K_mem` (AAD
context `media-dims`, via `symWrap`) → `wrappedDimensions`; `unsealDimensions` unwraps it on read (null on
absent/malformed/wrong-key). **Web grid:** `mediaGridLayout.ts` (`calculateJustifiedMediaLayout`,
`galleryDisplayAspectRatio`) ported verbatim from the design source; `MemoryDetail` now lays tiles out
absolutely-positioned from the computed layout, container width via a rAF-debounced `ResizeObserver`, target row
height 150/200 by width. Dimensionless media fall back to the bounded 4/3 default aspect.

`./gradlew -p backend check` (incl. the slice round-trip asserting `wrappedDimensions` lists back verbatim + is
null when omitted) + `npm run check` (193 web tests: media seal/unseal/best-effort, the layout math, and the
MemoryDetail render) + the three trust-chain guards all green.

**Live smoke (re-seeded instance, driven in the preview): confirmed end-to-end.** Restarted the dev backend so
Flyway applied V14 (`now at version v14`). Uploaded a canvas-generated **portrait 300×400** PNG into the open
Memory → probe sealed `{w:300,h:400}` under `K_mem` → on read it unwrapped to aspect 0.75 and the tile rendered
**100.8 × 134.4** (portrait, width < height) with its decrypted thumbnail, while the pre-existing dimensionless
media rendered **179.2 × 134.4** (the 4/3 fallback) — both justified into one row at equal height. No console
errors. This exercised both the sealed-dimension path and the fallback in the same grid.

**Operator-blindness preserved:** dimensions travel only as ciphertext sealed under `K_mem` (a wrong key can't
open them — asserted in `media.test.ts`); the server holds an opaque base64 blob it never reads.

**Notes / deferred:** `galleryDisplayAspectRatio` bounds the display aspect to [3/4, 4/3] (ported from the
design source) so one extreme photo can't dominate a row — the sealed dimensions are exact, only the *display*
ratio is clamped. Native (apple/android) gallery layout is their own later CR; their generated client already
carries `wrappedDimensions` after regeneration. Lazy/virtualized tile loading remains a later optimization.
