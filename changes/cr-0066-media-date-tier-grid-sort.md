---
id: cr-0066
title: Sealed capturedAt tier + date-sorted gallery grid (web/backend)
satisfies: [uc.memory.gallery]
status: done
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Sort a Memory's gallery **by capture time** (newest first) — which needs each media's **effective** capturedAt
available cheaply **at grid-build time**, for *every* listed media. Today the date lives only in the lazy
`wrappedExif` (cr-0064/0065), so promote it to its own **always-listed** tier, split by access pattern:

- **`wrappedLayout` `{v,w,h}`** — geometry (grid layout). Unchanged, always listed.
- **`wrappedCapturedAt` `{v, capturedAt?, capturedAtOverride?}`** — the capture time, **new always-listed** tier
  (small). `capturedAt` = the derived original (EXIF `DateTimeOriginal`, else the **file's creation date** =
  `File.lastModified` at upload); `capturedAtOverride` = the user's correction (cr-0065 moves here from
  `wrappedExif`). **Effective** = `override ?? capturedAt`.
- **`wrappedExif`** — the heavy/rare bundle (camera, lens, aperture, ISO, GPS, dims). Stays **lazy** via
  `getMediaMeta`; loses the date fields.

The grid sorts by effective capturedAt (**newest first** default, a direction toggle), media without any date
last. The lightbox now shows the date **instantly** (from the listed tier) and still edits it; correcting a date
re-seals only the small `wrappedCapturedAt` tier. This is the media half of inbox #47's `auto`-date timeline.

## Context & constraints

- **Operator-blind (ADR 0012).** All three tiers are ciphertext sealed under `K_mem`; the server stores/echoes
  them verbatim, never inspects. `wrappedCapturedAt` gets its own AAD context (`media-capturedat`), distinct from
  layout/exif (tier isolation).
- **Effective, override-aware sort.** The grid sorts by `override ?? capturedAt` so corrections reorder tiles.
  Editing a date updates `wrappedCapturedAt` (in the list) → the lightbox emits the new tier so `MemoryDetail`
  updates its item and re-sorts without a refetch.
- **File-creation fallback.** `addMedia` gets the source file's `lastModified` (threaded from the upload queue);
  when EXIF carries no `DateTimeOriginal`, `capturedAt` = that mtime. So a date is almost always present; a media
  with genuinely none sorts to the end (stable by list order).
- **Endpoint move.** cr-0065's `PUT …/media/{mediaId}/meta` (which overwrote `wrappedExif`) is **replaced** by
  `PUT …/media/{mediaId}/captured-at` `{ wrappedCapturedAt }` — the only user-editable tier is the date now, and
  it lives in the list, not the lazy meta. `GET …/meta` stays (lazy `wrappedExif`, now date-free). No feature
  updates `wrappedExif` after upload, so its write path is dropped.
- **Migration.** `V16__media_captured_at.sql` adds `wrapped_captured_at TEXT NULL` (the `wrapped_exif` column
  stays; only the client-side JSON shape moves the date out of it). Smoke data only → no backfill (old media
  keep their date inside `wrapped_exif`; they simply lack `wrapped_captured_at` and sort last / show no date
  until re-uploaded — acceptable, pre-release).
- **Scope.** Web + backend. **Deferred (own CRs):** native parity (data layer can carry the tier after
  regen); the Memory-level date modes / timeline grouping (inbox #47, which builds on this effective capturedAt).

## Phases

- [x] 1 contract + backend — add opaque `wrappedCapturedAt` to `MediaRequestItem` (input on complete) +
  `MediaSummary` (output on list); replace `updateMediaMeta` (PUT …/meta) with `updateMediaCapturedAt`
  (`PUT …/media/{mediaId}/captured-at`, body `MediaCapturedAtUpdate { wrappedCapturedAt }` → 200). Migration
  `V16__media_captured_at.sql` (`wrapped_captured_at TEXT NULL`); thread through entity/store/handler/resource;
  regenerate. · Done when: `./gradlew check` green; a slice test round-trips `wrappedCapturedAt` on complete→list
  and PUT→list, + `media_not_found`.
- [x] 2 web data — split the sealed metadata: `wrappedCapturedAt` (context `media-capturedat`) carries
  `{v,capturedAt?,capturedAtOverride?}`, `wrappedExif` loses the date; `AddMediaSource` gains `lastModified`,
  `addMedia` derives `capturedAt` = EXIF date ?? file mtime; `MediaItem` gains an unsealed `capturedAt`
  (effective) + `capturedAtOverride`; `setMediaCapturedAt` re-seals + PUTs the capturedAt tier; thread
  `lastModified` from the upload queue. · Done when: `npm run check` green; `media.test.ts` round-trips the date
  tier (EXIF date, mtime fallback, override set/clear) + the exif tier is date-free.
- [x] 3 web grid sort + lightbox — `MemoryDetail` sorts tiles by effective capturedAt (**newest first**, a
  direction toggle in the grid header; no-date last); the lightbox reads the date from the listed tier (instant)
  and, on edit, updates the tier + emits it so the grid re-sorts. · Done when: `npm run check` green; a
  MemoryDetail test sorts a mixed set newest-first + toggles; a lightbox test edits→reorders.
- [x] 4 coverage + verify — extend the gallery coverage (date-sorted grid + capturedAt tier); live browser
  smoke: upload two photos with different EXIF dates → grid orders newest-first; a no-EXIF file sorts by its
  file date; correct a date → the tile re-sorts; `wrappedCapturedAt` sealed (no plaintext date). · Done when:
  the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Sealed capturedAt tier + date-sorted grid | Status |
|---|---|---|
| backend / contracts | `wrappedCapturedAt` tier + `updateMediaCapturedAt` (opaque) | ✅ done |
| web | split date tier, file-mtime fallback, newest-first sort | ✅ done |
| apple / android | data layer can carry `wrappedCapturedAt`; native sort/edit UI is their own CR | ◻ **separate (native)** |

## Definition of done

A Memory's gallery sorts by effective capture time (newest first, toggleable), driven by a sealed always-listed
`wrappedCapturedAt` tier (EXIF date or file-creation fallback, override-aware) — the server holding only
ciphertext. The lightbox shows + edits the date from that tier; `wrappedExif` stays lazy and date-free. `npm run
check` + `./gradlew -p backend check` + the three guards green; the smoke confirmed.

## Retrospective

Shipped in four phases. The capture time is now its own **always-listed** sealed tier, so the gallery sorts by
date client-side while the server stays blind — the third tier in a clean split by access pattern (geometry for
the grid · date for sort/display/edit · heavy EXIF lazy for the lightbox detail).

**Design (with the user).** cr-0064/0065 had put capturedAt in the lazy `wrappedExif`; sorting the grid needs it
for *every* listed media, so a per-item or batch fetch would be needed. Instead we promoted it to a dedicated
`wrappedCapturedAt {v,capturedAt,capturedAtOverride}` tier (context `media-capturedat`), keeping `wrappedLayout`
geometry-only. The override (cr-0065) **moved out of `wrappedExif`** into this tier, so a correction re-seals only
the small date blob and `wrappedExif` is never rewritten after upload (its write path dropped). Undated media use
the **file creation date** (`File.lastModified`, threaded from the upload queue) as the `capturedAt` fallback, so
a date is almost always present; genuinely undated media sort last.

**Contract + backend.** `wrappedCapturedAt` added to `MediaRequestItem` (in) + `MediaSummary` (out); cr-0065's
`PUT …/meta` replaced by `PUT …/media/{mediaId}/captured-at` (`updateMediaCapturedAt`, `MediaCapturedAtUpdate` →
`MediaCapturedAtResponse`); `GET …/meta` kept (lazy, date-free `wrappedExif`). Migration `V16` adds
`wrapped_captured_at TEXT NULL`. Threaded verbatim through entity/store (`setCapturedAt`)/handler
(`updateCapturedAt`, new `CapturedAtOutcome`)/resource. 13 MediaSliceTest green (complete→list + PUT→list
round-trip, `media_not_found`).

**Web.** `readExif` now splits its result into `{ capturedAt, exif }` (exif date-free); `sealMetadata` seals
three tiers, `capturedAt = EXIF date ?? file mtime`. `MediaCapturedAt` type + `unsealCapturedAt` +
`effectiveCapturedAt(tier)`; `setMediaCapturedAt` re-seals the date tier, PUTs it, and returns **both** the
unsealed tier and the sealed blob. `MemoryDetail` sorts a `sortedItems` computed by effective capturedAt (newest
first, a header toggle → oldest; undated last); the lightbox reads the date instantly from the listed tier, and on
edit emits the new sealed blob so `MemoryDetail` patches the item + re-sorts (the item object is shared with the
open lightbox's snapshot, so reopening stays consistent — no stale date, no refetch). i18n `sortNewest`/
`sortOldest` (EN+DE). Tests: `media.test.ts` (date/exif split, mtime fallback, override set/clear),
`MemoryDetail.test.ts` (newest-first + toggle), `MediaLightbox.test.ts` (edit→corrected→reset).

**Verification.** `./gradlew -p backend check` + `npm run check` (213 web tests) + the three trust-chain guards
green.

**Live smoke (re-seeded MariaDB + Garage, driven in the preview): confirmed end-to-end.** Restarted the backend so
Flyway applied V16 (`now at version v16`; the edited V15 comment needed a one-off `-Dquarkus.flyway.repair-at-
start=true` to realign the checksum — a local-DB detail). In a fresh Memory, uploaded three files: a **2023** EXIF
photo, a **2018** EXIF photo, and a **no-EXIF PNG** with a 2020 file date. The grid ordered them **newest first —
2023 → 2020 → 2018** (the no-EXIF one placed by its file date, confirming the fallback); the header toggle flipped
to oldest-first. Correcting a photo's date in the lightbox **re-sorted the grid live without a refetch** (the
corrected tile jumped position) and **reopening it showed the fresh corrected date** (the item was patched, so no
staleness). The three `veyq_media.wrapped_captured_at` values were sealed ciphertext (84 B date-only / 132 B with
an override) with **0 plaintext leaks** — the server never saw a timestamp.

**Notes / deferred (own CRs):** native (apple/android) parity — the data layer carries `wrappedCapturedAt` after
regen; native sort/edit UI is their own CR. Pre-existing media from earlier smokes keep their date inside
`wrapped_exif` and lack `wrapped_captured_at`, so they sort last until re-uploaded (acceptable, pre-release). The
Memory-level date modes + chronological timeline grouping (inbox #47) build on this effective capturedAt.
