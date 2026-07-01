---
id: cr-0064
title: Sealed media metadata — capturedAt + EXIF (2-tier), lightbox details (web/backend)
status: done
satisfies: [uc.memory.upload, uc.memory.gallery]
adrs: ["0012", "0013"]
invariants: [operator-blind-e2ee]
---

## Goal

Seal each media's **capture time + EXIF** at upload, operator-blind, in a **2-tier** shape so the gallery stays
cheap while the lightbox can show rich detail:

- **Tier 1 `wrappedLayout`** — generalizes cr-0059's `wrappedDimensions` to the versioned geometry tier
  `{ v, w, h }`, sealed under `K_mem`, returned with **every** `listMedia`. Kept minimal (geometry only) — it
  drives the justified grid. (Capture time is **not** here; it lives in Tier 2. A later timeline / `auto`-date
  mode, inbox #47, reads `capturedAt` via a batch meta fetch.)
- **Tier 2 `wrappedExif`** — the full bundle (**capture time**, camera, lens, aperture, ISO, GPS, …), sealed
  under `K_mem`, **lazy-loaded** per item via a slim meta endpoint, shown in the lightbox. Keeps `listMedia`
  constant-small.

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

- [x] 1 contract + backend — rename `wrappedDimensions`→`wrappedLayout` on `MediaRequestItem`/`MediaSummary`;
  add opaque `wrappedExif` to `MediaRequestItem` (input on complete); add `GET …/media/{mediaId}/meta` →
  `{ wrappedExif }`. Migration `V15__media_metadata.sql` (rename `wrapped_dimensions`→`wrapped_layout`, add
  `wrapped_exif TEXT NULL`); thread both through `MediaEntity`/store/handler/resource verbatim; regenerate. ·
  Done when: `./gradlew check` green; a slice test round-trips `wrappedLayout` + `wrappedExif` (complete →
  list returns layout, not exif; the meta endpoint returns exif; both opaque, never inspected).
- [x] 2 web upload — add `exifr`; `addMedia` extracts capture time + GPS + camera fields (best-effort) and
  seals **both** tiers under `K_mem` (`wrappedLayout` = `{v,w,h,capturedAt}`, `wrappedExif` = full);
  `wrappedDimensions`→`wrappedLayout` rename in `media.ts` (the justified-grid reader keeps working off
  `layout.{w,h}`). · Done when: `npm run check` green; `media.test.ts` round-trips a sealed layout (with
  capturedAt) + exif and tolerates a no-EXIF source (original-only, no metadata).
- [x] 3 web lightbox — a `loadMediaExif(scopeId, mediaId, K_mem)` that fetches the meta endpoint + unseals;
  the lightbox shows the detail (date, camera, lens, exposure, coordinates) lazily when an item opens. i18n. ·
  Done when: `npm run check` green; a lightbox test renders decoded EXIF for an item + tolerates none.
- [x] 4 coverage + verify — update
  [uc.memory.upload.yaml](../docs/coverage/features/memory/use-cases/uc.memory.upload.yaml) (seal metadata) +
  [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml) (lightbox EXIF);
  live browser smoke: upload a photo with EXIF → capturedAt sealed in layout, EXIF sealed; open the lightbox →
  camera/date/coords shown; the `listMedia` response carries no exif (only layout). · Done when: the three
  trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Sealed capturedAt + EXIF (2-tier) + lightbox details | Status |
|---|---|---|
| backend / contracts | `wrappedLayout` + `wrappedExif` + the lazy meta endpoint (opaque) | ✅ done |
| web | exifr extraction, seal both tiers, lazy lightbox detail | ✅ done |
| apple / android | data layer can carry both tiers; native EXIF extraction + detail UI is their own CR | ◻ **separate (native)** |

## Definition of done

Each uploaded media seals its capture time + EXIF under `K_mem` in two tiers — `wrappedLayout` (with
`capturedAt`) always listed, `wrappedExif` lazy via the meta endpoint — and the lightbox shows the decoded
detail; the server holds only ciphertext and never returns EXIF in the list. `wrappedDimensions` is renamed to
`wrappedLayout` end-to-end. `npm run check` + `./gradlew -p backend check` + the three guards green; the smoke
confirmed. The `auto`-date/place modes + chronological gallery build on this later (inbox #47).

## Retrospective

Shipped in four phases; `wrappedDimensions` is renamed to `wrappedLayout` end-to-end and capture time + EXIF are
sealed under `K_mem` in two tiers.

**Contract + backend.** `media.openapi.yaml`: `wrappedDimensions`→`wrappedLayout` on `MediaRequestItem`/
`MediaSummary` (JSON generalized `{w,h}`→`{v,w,h,capturedAt}`); a new opaque `wrappedExif` (input-only on
complete, cap 16 KiB) on `MediaRequestItem`; a new `GET /api/scopes/{scopeId}/media/{mediaId}/meta` →
`MediaMetaResponse { wrappedExif }` (operationId `getMediaMeta`). Migration `V15__media_metadata.sql` renames the
column (`RENAME COLUMN`, in the H2 2.x ∩ MariaDB 10.5.2+ common subset — no backfill, smoke-data only) and adds
`wrapped_exif TEXT NULL`. Threaded verbatim through `MediaEntity` → `MediaStore.attach`/`MediaRow`/new
`findMedia` → `MediaHandler` (new `mediaMeta` outcome) → `MediaResource` (new `getMediaMeta` override). The blind
server stores + echoes ciphertext and never inspects it; `listNodeMedia` returns `wrappedLayout` but **not**
`wrappedExif` (the meta endpoint is the only read path for the EXIF tier).

**Web upload.** Added `exifr` (7.1.3, zero-deps, HEIC, GPS DMS→decimal). `media.ts`: `wrappedDimensions`→
`wrappedLayout` throughout; `sealMetadata` seals both tiers best-effort under `K_mem` — the layout tier `{v,w,h}`
(context `media-layout`, geometry only) and the full EXIF bundle incl. `capturedAt` (context `media-exif`, a
client-only versioned shape so it can grow without a contract change). `unsealDimensions`→`unsealLayout`
(returns `{v,w,h}`, drives the justified grid off `.{w,h}`). **Two distinct AAD contexts** (not the prose's
single `media-meta`) so a layout blob can't be opened as an EXIF blob — a small tier-isolation hardening.
(Follow-up refinement, same strand: `capturedAt` was initially also duplicated into the layout tier for a cheap
timeline read, then trimmed out — the layout tier stays geometry-only, and a timeline reads capture time from
the EXIF tier via a batch meta fetch, inbox #47. See cr-0065.)

**Web lightbox.** `loadMediaExif(scopeId, mediaId, K_mem)` fetches `getMediaMeta` + unseals lazily. Ported the
design-source (`frontend-noe2ee`) metadata detail panel (`media-lightbox-details`/`-metadata` dl, all CSS
already present) — shown only when an item opens; renders captured date (locale-aware), camera, lens, focal
length, aperture (`f/1.8`), exposure (`1/125 s`), ISO, dimensions, and GPS **coordinates as text** (a map is a
deferred CR, and a text-only coord avoids leaking GPS to a third-party map on click). New i18n keys (EN+DE).

**Verification.** `./gradlew -p backend check` (11 MediaSliceTest incl. the layout+exif round-trip, the meta
endpoint, and `media_not_found` 404) + `npm run check` (media seal/unseal both tiers + list-exclusion; lightbox
renders decoded EXIF + tolerates none) + the three trust-chain guards all green.

**Live smoke (re-seeded MariaDB + Garage store, driven in the preview): confirmed end-to-end.** Restarted the
dev backend so Flyway applied V15 (`now at version v15`; the column rename + `wrapped_exif` add verified in the
DB). Uploaded a generated **1200×800 JPEG with real EXIF** (Canon EOS R6, RF 50mm F1.8, 1/125 s, ISO 200,
DateTimeOriginal 2021-05-01, GPS Munich) into the Memory. The new `veyq_media` row carries a sealed
`wrapped_layout` (108 B — larger than the pre-existing cr-0059 rows' 64 B, consistent with the added
`capturedAt`) **and** a sealed `wrapped_exif` (320 B), with **0 plaintext leaks** (no `Canon`/coords/date/dims in
the clear). Opening the lightbox rendered every decoded field (`Canon EOS R6`, `RF 50mm F1.8 STM`, `50 mm`,
`f/1.8`, `1/125 s`, `200`, `1200 × 800 px`, `48.137400, 11.575000`, `01.05.2021, 10:00`); paging prev→next fired
two lazy `GET …/media/{mediaId}/meta` → 200 with **no** `listNodeMedia` refetch — proving the EXIF tier is
fetched per-item on open, never bundled in the list.

**Notes / deferred (own CRs, inbox #47 + others):** the aggregated chronological gallery + `auto`-date/place
modes (which `wrappedLayout.capturedAt` now enables), place autosuggestion (Photon), a map from the sealed GPS,
and native (apple/android) parity — the native data layer can carry both tiers after regeneration; native EXIF
extraction + detail UI is their own CR. The browser direct-to-store path needs the presigned `public-endpoint`
signed for the web origin so Vite's same-origin `/veyq` proxy avoids CORS (`veyq.blob-store.public-endpoint=
http://127.0.0.1:5173`) — an infra/live-smoke setup detail, not a code change.
