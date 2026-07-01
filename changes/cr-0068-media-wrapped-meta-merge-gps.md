---
id: cr-0068
title: Consolidate the listed per-media tiers into wrapped_meta + promote GPS (place slice 2a)
status: done
satisfies: [uc.memory.place]
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Give the medoid its GPS source **at list-level**, and clean up the per-media metadata tiers while we're there.
Today a media carries **two** always-listed sealed tiers (`wrapped_layout {v,w,h}` + `wrapped_captured_at
{v,capturedAt,…}`) plus a **lazy** `wrapped_exif` that *also* holds GPS. The Memory's place medoid
([uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md)) needs every listed media's GPS cheaply
at set-location time — a per-item EXIF fetch was rejected (an N-fetch on every `auto` re-medoid).

So **fold the two listed tiers into one** `wrapped_meta` blob (mirroring the node's `wrapped_meta`, cr-0067) and
**promote GPS into it**, moving GPS *out* of the lazy `wrapped_exif` — exactly the way cr-0066 moved `capturedAt`
out of `wrapped_exif`, and the way cr-0064 renamed `wrapped_dimensions → wrapped_layout` when the tier outgrew
its name. End state per row:

- **`veyq_media.wrapped_meta`** `{v, w, h, capturedAt?, capturedAtOverride?, capturedAtSource?, gpsLat?, gpsLng?}`
  — the single **always-listed** per-media blob (geometry for the grid · date for sort/edit · GPS for the medoid).
- **`veyq_media.wrapped_exif`** — the heavy/rare bundle, still **lazy**, now **GPS-free** (camera, lens, aperture,
  ISO, orientation, dims).
- **`veyq_node.wrapped_meta`** `{v, date{…}, location{…}}` — unchanged (per Memory; slice 2b writes `location`).

This is **place slice 2a** — the GPS groundwork the web set-location CR (2b) builds on. No medoid, no map, no
geocoding here.

## Context & constraints

- **Operator-blind (ADR 0012).** Both media tiers stay ciphertext sealed under `K_mem`; the server stores/echoes
  them verbatim, never inspects. The listed blob gets its **own AAD context `media-meta`** (distinct from
  `media-exif` and from the node's `node-meta` → tier isolation preserved). No coordinate or timestamp ever
  crosses in cleartext.
- **No GPS override.** Unlike the date (which has `capturedAtOverride`), GPS is **not** user-editable at the media
  level — `gpsLat/gpsLng` are derived-only. The only editable field in `wrapped_meta` remains the date correction.
- **GPS moves, not copies.** `readExif` routes `gpsLat/gpsLng` into `wrapped_meta`; `wrapped_exif` **loses** its
  GPS fields (no duplication) — the exact precedent of cr-0066 moving `capturedAt` out of `wrapped_exif`.
- **Merge migration, no backfill.** `V18__media_meta.sql`: `RENAME COLUMN wrapped_layout → wrapped_meta` then
  `DROP COLUMN wrapped_captured_at` (both in the H2 2.x ∩ MariaDB 10.5.2+ common subset, per cr-0064's V15
  precedent). Smoke data only → **no backfill**: pre-existing rows keep whatever sat in the renamed column and
  lose their separate captured-at blob; they re-seal the merged shape on next upload. Acceptable, pre-release
  (same stance as cr-0064/0066).
- **Endpoint naming follows the merge.** cr-0066 had `PUT …/captured-at` (date edit) + `GET …/meta` (lazy exif).
  Now the editable listed blob *is* `wrapped_meta`, so: **`PUT …/media/{mediaId}/meta`** (`updateMediaMeta`, body
  `MediaMetaUpdate { wrappedMeta }` — re-seals the whole small listed blob on a date correction) **replaces**
  `PUT …/captured-at`; and the lazy read **`GET …/media/{mediaId}/exif`** (`getMediaExif` → `MediaExifResponse
  { wrappedExif }`) **replaces** `GET …/meta` (the `/meta` path now belongs to the listed blob, not exif).
- **Contract cap.** `wrappedMeta` carries more than either old tier (w,h + date trio + GPS pair) → bump its
  `maxLength` above the old `wrappedCapturedAt` 256 (e.g. 512); still tiny, still listed with every media.
- **Touches [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)'s tiers** — the grid's
  layout + date-sort now read one blob. Must not regress: geometry still lays out the justified grid, effective
  `capturedAt` (`override ?? capturedAt`) still sorts newest-first, the lightbox still shows+edits the date.
- **Scope.** contracts + backend + web. **Deferred (own CRs):** the web **set-location** UI + medoid (slice 2b);
  the **tenant map** (3); **reverse-geocoding** (4). **Native parity (apple/android):** their media data layer
  does **not** reference these fields/ops today (verified), so they are untouched here; they regenerate against
  the new `wrapped_meta` shape + renamed ops when they build their own media-metadata CR (per ADR 0010 web-first).

## Phases

- [x] 1 contracts — in `contracts/api/media.openapi.yaml`: replace `wrappedLayout` + `wrappedCapturedAt` with a
  single `wrappedMeta` (opaque base64, `maxLength: 512`) on `MediaRequestItem` (in, at complete) + `MediaSummary`
  (out, on list); drop GPS from the `wrappedExif` description (still opaque). Replace op `updateMediaCapturedAt`
  (`PUT …/captured-at`, `MediaCapturedAtUpdate/Response`) with `updateMediaMeta` (`PUT …/media/{mediaId}/meta`,
  `MediaMetaUpdate { wrappedMeta } → MediaMetaResponse { wrappedMeta }`); rename `getMediaMeta` (`GET …/meta`) to
  `getMediaExif` (`GET …/media/{mediaId}/exif`, response `MediaExifResponse { wrappedExif }`). Regenerate. ·
  Done when: `./gradlew -p backend check` compiles the regenerated models; the old `wrappedLayout`/
  `wrappedCapturedAt`/`captured-at` symbols are gone from generated code.
- [x] 2 backend — migration `V18__media_meta.sql` (`RENAME COLUMN wrapped_layout → wrapped_meta`; `DROP COLUMN
  wrapped_captured_at`). `MediaEntity`: one `wrapped_meta` column replaces `wrapped_layout` + `wrapped_captured_at`
  (`wrapped_exif` stays). Thread verbatim through store (`attach(wrappedMeta)`, `setMeta`, `listNodeMedia`),
  handler (`CompleteItem.wrappedMeta`, `MetaOutcome`, `updateMeta`), resource (`updateMediaMeta`, `getMediaExif`).
  · Done when: `./gradlew -p backend check` green; a slice test round-trips `wrappedMeta` on complete→list and
  `PUT …/meta`→list, `getMediaExif` returns `wrappedExif`, + `media_not_found`.
- [x] 3 web — `web/src/features/memory/media.ts`: one `MediaMeta {v,w,h,capturedAt?,capturedAtOverride?,
  capturedAtSource?,gpsLat?,gpsLng?}` (context `media-meta`) replaces the layout + capturedAt types; `readExif`
  routes `gpsLat/gpsLng` into the meta tier and drops them from `MediaExif`; `sealMetadata` seals **two** tiers
  (meta + exif); `MediaItem` carries `wrappedMeta` (+ its unsealed geometry/date/gps); `setMediaMeta` re-seals +
  `PUT …/meta`; the lazy loader uses `GET …/exif`. `MemoryDetail` reads geometry + effective `capturedAt` from
  the one tier (grid layout + newest-first sort unchanged); the lightbox shows+edits the date by re-sealing the
  meta blob. · Done when: `npm run check` green; `media.test.ts` round-trips the merged tier (geometry, date via
  EXIF + mtime fallback, override set/clear, GPS present) and asserts `wrappedExif` is date- **and** GPS-free;
  `MemoryDetail.test.ts` (layout + newest-first sort) and `MediaLightbox.test.ts` (date edit) stay green.
- [x] 4 coverage + verify — update `docs/coverage/features/memory/use-cases/uc.memory.place.yaml` (the
  `medoid-position` criterion's **GPS-into-the-listed-tier** half now backed by backend + web tests; the medoid
  computation + set-location stay gapped for slice 2b) and `uc.memory.gallery.yaml` (retarget the layout/date-tier
  test refs to the merged `wrapped_meta`; no regression). Live browser smoke: upload a geotagged photo → its
  `wrapped_meta` lists with sealed GPS (no plaintext coord/timestamp in `veyq_media`); the grid still lays out +
  sorts newest-first; a date correction re-seals the merged blob and re-sorts; `getMediaExif` still serves the
  (now GPS-free) EXIF in the lightbox. · Done when: the three trust-chain guards green
  (`validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py`); the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Merged `wrapped_meta` listed tier + GPS promoted, GPS-free lazy `wrapped_exif` | Status |
|---|---|---|
| backend / contracts | one `wrapped_meta` tier; `updateMediaMeta` + `getMediaExif`; V18 merge migration | ✅ done (`./gradlew check` green) |
| web | merged seal/unseal, GPS→meta, grid layout+sort unchanged, date edit via `/meta` | ✅ done (`npm run check`, 241 tests) |
| apple / android | data layer untouched (no ref to these fields/ops); regen at their own media-metadata CR | ◻ **separate (native)** |

## Definition of done

A media carries **one** always-listed sealed `wrapped_meta` blob (geometry + effective capture date + GPS) and a
**lazy, GPS-free** `wrapped_exif` — the server holding only ciphertext under `K_mem`. The gallery grid lays out +
sorts exactly as before; the lightbox shows+edits the date via `PUT …/meta` and reads EXIF via `GET …/exif`. The
listed GPS is the source the place medoid (slice 2b) will read without a per-item fetch. `./gradlew -p backend
check` + `npm run check` + the three trust-chain guards green; live smoke confirmed on a re-seeded MariaDB +
Garage — V18 applied, a geotagged upload sealed GPS into `wrapped_meta`, and the stored ciphertext leaked zero
plaintext coordinate/date.

## Retrospective

Code + trust chain + the live smoke all landed in one session. Live proof (re-seeded MariaDB + Garage,
`quarkusDev` against the store): Flyway applied **V18 on real MariaDB** (`now at version v18`; `veyq_media` ends
with `wrapped_meta` + `wrapped_exif`, the old `wrapped_layout`/`wrapped_captured_at` **dropped**); the existing
`media.e2e` round-tripped bytes through real presigned PUT/GET on the cr-0068 contract; and a focused live e2e
uploaded two **geotagged** JPEGs through the real data layer → their `wrapped_meta` unsealed to the right GPS +
capture date, while the stored MariaDB ciphertext (180/164 B) carried **zero plaintext** coordinate or year
(operator-blind confirmed at the DB). What was rough:

- **The CR under-scoped itself in its own title.** It opened as "promote GPS into the capture tier" and only
  during refinement (with the user) grew into "consolidate the two listed tiers into `wrapped_meta` + move GPS
  out of exif". The plan doc caught up, but the *first* framing (slice 2a = a thin GPS add) mis-set the size —
  the real change touched three per-media tiers, two endpoints, and eight web files. **Root cause:** I proposed
  the slice boundary before mapping the existing tier wiring. **Next time:** run the wiring scout (which I did do
  here, via the Explore agent) *before* writing the slice list, not after — the merge opportunity was only
  visible once the `dimensions→layout` rename precedent surfaced.

- **A silent `gaps:` deletion slipped through.** The place-coverage edit's `old_string` began at `gaps:` but the
  `new_string` didn't restore it, so the key vanished and the YAML broke (`expected <block end>, found '?'`). The
  guard caught it immediately, but it cost a reproduce-and-fix loop. **Root cause:** anchoring an Edit on a
  structural key without re-including it. **Next time:** when an Edit spans a mapping key, keep the key in both
  sides of the replacement.

- **Stale test refs rippled past the satisfied use-case.** Renaming the `MediaSliceTest` methods + the web
  describe blocks broke coverage refs in *three* use-cases (`gallery`, `upload`, plus `place`) — not just the one
  the CR `satisfies:`. Expected (the CR-README warns unverified claims propagate), but a reminder that renaming a
  shared test method is a cross-use-case change. **Next time:** grep `docs/coverage` for a test method name before
  renaming it, the same way we grep source.

- **The browser smoke fought back; the headless path won.** Driving login via the PrimeVue form through the
  preview tools stalled — a native `.value` set doesn't reach the `Password` component's v-model, and even with
  proper input events the submit didn't fire a challenge; each retry cost a full network-dump read. Switching to
  a **headless e2e harness** (reusing `media.e2e`'s login→unlock→addMedia path with a geotagged fixture + a
  FileReader polyfill so exifr's Blob path runs under node) proved the GPS-sealing end-to-end far more cheaply and
  reliably, and a direct `docker exec` DB query gave the operator-blind proof. **Next time:** for a data-path
  smoke, reach for the headless e2e harness first and reserve browser-driving for genuinely visual/layout checks —
  it matches the memory note's "cheap diagnostics before expensive ones."

- **Process honored:** conversed in German throughout, delegated the surface-wide read to an Explore agent (per
  the memory note), kept docs/code in English, no deprecated deps, migration in the H2 ∩ MariaDB subset, and ran
  the live smoke to completion rather than claiming done on unit tests alone.
