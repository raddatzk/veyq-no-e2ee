---
id: cr-0064
title: Sealed media metadata — capturedAt + EXIF (2-tier), lightbox details (web/backend)
status: planned
satisfies: [uc.memory.upload, uc.memory.gallery]
adrs: ["0012", "0013"]
invariants: [operator-blind-e2ee]
---

## Goal

Seal each media's **capture time + EXIF** at upload, operator-blind, in a **2-tier** shape so the gallery stays
cheap while the lightbox can show rich detail:

- **Tier 1 `wrappedLayout`** — generalizes cr-0059's `wrappedDimensions` to `{ v, w, h, capturedAt }`, sealed
  under `K_mem`, returned with **every** `listMedia`. Drives the justified grid today and the chronological
  timeline / `auto`-date modes later (inbox #47).
- **Tier 2 `wrappedExif`** — the full bundle (camera, lens, aperture, ISO, GPS, …), sealed under `K_mem`,
  **lazy-loaded** per item via a slim meta endpoint, shown in the lightbox. Keeps `listMedia` constant-small.

This is the media-metadata strand that inbox #47's `auto`/derived date+place modes depend on; those stay their
own later CRs (this one only seals + displays).

## Context & constraints

- **Operator-blind (ADR 0012).** `capturedAt`, GPS and EXIF are "sensitive metadata (capture time, location)"
  → sealed under **`K_mem`** (context `media-meta`), opaque to the server. Both tiers are ciphertext the server
  stores + returns verbatim, never inspects. GPS never leaves the device in the clear.
- **Extraction lib: `exifr`** (actively maintained, zero-deps, HEIC, auto GPS DMS→decimal, fast) — added to
  `web`. Best-effort at upload: unreadable/absent EXIF → no metadata (grid/lightbox degrade, never fail).
- **Tier-1 rename.** `wrappedDimensions` → `wrappedLayout` everywhere (contract field, `veyq_media` column,
  web `media.ts` seal/unseal, the justified-grid reader). The JSON gains `capturedAt`; `{w,h}` unchanged.
  Migration renames the column (H2 ∩ MariaDB subset); only smoke data exists, so no backfill.
- **Tier-2 is lazy.** `listNodeMedia` returns `wrappedLayout` but **not** `wrappedExif`; a new
  `GET /api/scopes/{scopeId}/media/{mediaId}/meta` returns `{ wrappedExif }` on demand (opaque base64). The
  lightbox fetches + unseals it only when an item opens.
- **Client EXIF shape** (inside `wrappedExif`, versioned `v`): `capturedAt`, `make`, `model`, `lens`,
  `focalLength`, `fNumber`, `exposureTime`, `iso`, `gpsLat`, `gpsLng`, `orientation`, `w`, `h` — all optional;
  the schema is client-side only, so it can grow without a contract change (operator never learns the fields).
- **Scope.** Web + backend. **Deferred (own CRs):** the aggregated chronological gallery + `auto`-date/place
  modes (inbox #47), place autosuggestion (Photon), native (apple/android) parity, a map from GPS.

## Phases

- [ ] 1 contract + backend — rename `wrappedDimensions`→`wrappedLayout` on `MediaRequestItem`/`MediaSummary`;
  add opaque `wrappedExif` to `MediaRequestItem` (input on complete); add `GET …/media/{mediaId}/meta` →
  `{ wrappedExif }`. Migration `V15__media_metadata.sql` (rename `wrapped_dimensions`→`wrapped_layout`, add
  `wrapped_exif TEXT NULL`); thread both through `MediaEntity`/store/handler/resource verbatim; regenerate. ·
  Done when: `./gradlew check` green; a slice test round-trips `wrappedLayout` + `wrappedExif` (complete →
  list returns layout, not exif; the meta endpoint returns exif; both opaque, never inspected).
- [ ] 2 web upload — add `exifr`; `addMedia` extracts capture time + GPS + camera fields (best-effort) and
  seals **both** tiers under `K_mem` (`wrappedLayout` = `{v,w,h,capturedAt}`, `wrappedExif` = full);
  `wrappedDimensions`→`wrappedLayout` rename in `media.ts` (the justified-grid reader keeps working off
  `layout.{w,h}`). · Done when: `npm run check` green; `media.test.ts` round-trips a sealed layout (with
  capturedAt) + exif and tolerates a no-EXIF source (original-only, no metadata).
- [ ] 3 web lightbox — a `loadMediaExif(scopeId, mediaId, K_mem)` that fetches the meta endpoint + unseals;
  the lightbox shows the detail (date, camera, lens, exposure, coordinates) lazily when an item opens. i18n. ·
  Done when: `npm run check` green; a lightbox test renders decoded EXIF for an item + tolerates none.
- [ ] 4 coverage + verify — update
  [uc.memory.upload.yaml](../docs/coverage/features/memory/use-cases/uc.memory.upload.yaml) (seal metadata) +
  [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml) (lightbox EXIF);
  live browser smoke: upload a photo with EXIF → capturedAt sealed in layout, EXIF sealed; open the lightbox →
  camera/date/coords shown; the `listMedia` response carries no exif (only layout). · Done when: the three
  trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Sealed capturedAt + EXIF (2-tier) + lightbox details | Status |
|---|---|---|
| backend / contracts | `wrappedLayout` + `wrappedExif` + the lazy meta endpoint (opaque) | ◻ this CR |
| web | exifr extraction, seal both tiers, lazy lightbox detail | ◻ this CR |
| apple / android | data layer can carry both tiers; native EXIF extraction + detail UI is their own CR | ◻ **separate (native)** |

## Definition of done

Each uploaded media seals its capture time + EXIF under `K_mem` in two tiers — `wrappedLayout` (with
`capturedAt`) always listed, `wrappedExif` lazy via the meta endpoint — and the lightbox shows the decoded
detail; the server holds only ciphertext and never returns EXIF in the list. `wrappedDimensions` is renamed to
`wrappedLayout` end-to-end. `npm run check` + `./gradlew -p backend check` + the three guards green; the smoke
confirmed. The `auto`-date/place modes + chronological gallery build on this later (inbox #47).

## Retrospective

_(to be written at close)_
