---
id: cr-0065
title: Correct a media's capture time — sealed capturedAt override in the EXIF tier (web/backend)
status: done
satisfies: [uc.memory.gallery]
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a user **correct (or set) the capture time** of a single media from the lightbox — the camera clock was
wrong, or a scanned photo has no EXIF date. The correction is sealed under `K_mem` (operator-blind, the server
never sees the date) and lives **inside the EXIF tier** (`wrappedExif`, cr-0064) next to the extracted original:

- `wrappedExif.capturedAt` stays the **immutable extracted original** (what the camera recorded).
- a new `wrappedExif.capturedAtOverride` holds the **user's correction** (epoch-ms), or is absent.
- **effective** capture time = `capturedAtOverride ?? capturedAt`; the lightbox shows it and flags **"corrected"**
  when an override is set; **reset** removes the override (back to the original, or to none).

Nothing lands in `wrappedLayout` — that tier stays geometry-only (`{v,w,h}`, cr-0064 follow-up). A later
timeline / `auto`-date mode (inbox #47) reads the effective capture time from the EXIF tier via a batch meta
fetch.

## Context & constraints

- **Operator-blind (ADR 0012).** The correction is sealed under `K_mem` (context `media-exif`, same as the
  bundle). Persisting it = **re-sealing the whole `wrappedExif` bundle** with the override added/removed and
  storing the new opaque ciphertext verbatim. The server stores + echoes it, never inspects it.
- **Re-seal, don't patch fields.** The client already has the decoded EXIF in hand (the lightbox lazy-loaded it
  via `getMediaMeta`), so it adds/removes `capturedAtOverride`, re-seals the **entire** bundle, and `PUT`s it.
  No field-level server merge (the server can't read the bundle). A media with no EXIF at all (`wrappedExif`
  null) → a fresh `{ v, capturedAtOverride }` bundle is sealed (a manual set).
- **Backend = one opaque write.** A new `PUT /api/scopes/{scopeId}/media/{mediaId}/meta` with `{ wrappedExif }`
  overwrites `veyq_media.wrapped_exif` verbatim (the column exists since cr-0064 — **no migration**). Mirrors the
  cr-0064 `GET …/meta`. Guards: the caller holds a grant for the scope; unknown media → `media_not_found`.
- **Permission.** Any scope member holding `K_mem` may correct the time (same capability as upload — anyone who
  can unlock + add media can fix its metadata). Finer per-uploader/role gating is deferred (no roles yet).
- **UX (ported from `frontend-noe2ee`).** The lightbox detail panel's "⋮" menu gains **Aufnahmezeit bearbeiten**
  → a native `<input type="datetime-local">` with Save / Cancel / **Reset** (back to original). A "corrected"
  status row shows when an override is set. i18n EN+DE. The date is naive wall-clock (EXIF has no timezone),
  stored as epoch-ms.
- **Scope.** Web + backend. **Deferred (own CRs):** native (apple/android) parity; the Memory-level date modes
  + chronological timeline (inbox #47, which consumes the effective capturedAt); finer edit permissions.

## Phases

- [x] 1 contract + backend — add `PUT /api/scopes/{scopeId}/media/{mediaId}/meta` (operationId
  `updateMediaMeta`) with body `MediaMetaUpdate { wrappedExif }` → 200 `MediaMetaResponse`; thread through
  `MediaResource` → `MediaHandler` (new `updateMediaExif` outcome) → `MediaStore` (`setMediaExif`). No
  migration (`wrapped_exif` exists). Regenerate. · Done when: `./gradlew check` green; a slice test round-trips
  a PUT-then-GET of `wrappedExif` (opaque, never inspected) + `media_not_found` on an unknown id.
- [x] 2 web data — `media.ts`: `MediaExif` gains optional `capturedAtOverride`; a `setMediaCapturedAt(scopeId,
  mediaId, K_mem, exif, overrideMs | null)` that re-seals the bundle (override set/cleared) and `PUT`s it,
  returning the updated `MediaExif`. · Done when: `npm run check` green; `media.test.ts` round-trips setting +
  clearing the override (re-seal opaque; a wrong K_mem can't open it) incl. the no-EXIF (fresh bundle) case.
- [x] 3 web lightbox — port the edit UX: a "⋮"-menu **edit captured time** entry → datetime-local form with
  Save / Cancel / Reset; the "Taken" row shows the **effective** time (`override ?? original`) with a
  **corrected** indicator; save/reset call `setMediaCapturedAt` and update local state. i18n EN+DE. · Done when:
  `npm run check` green; a lightbox test edits the time (shows corrected + effective), resets it, and handles a
  no-EXIF item.
- [x] 4 coverage + verify — extend the `lightbox-exif-detail` criterion in
  [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml) (editable,
  operator-blind capturedAt); live browser smoke: correct a photo's time → re-sealed `wrapped_exif` (ciphertext,
  no plaintext date), the lightbox shows the corrected time + badge, reset returns to the original. · Done when:
  the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Sealed capturedAt override + edit UI | Status |
|---|---|---|
| backend / contracts | `updateMediaMeta` (opaque `wrappedExif` overwrite) | ✅ done |
| web | re-seal override in `wrappedExif`, lightbox edit UX | ✅ done |
| apple / android | data layer can carry `capturedAtOverride`; native edit UI is their own CR | ◻ **separate (native)** |

## Definition of done

A user can correct or set a media's capture time from the lightbox; the correction is sealed under `K_mem`
inside `wrappedExif` (original kept alongside), the server holding only ciphertext it re-stores verbatim. The
lightbox shows the effective time with a "corrected" indicator and can reset to the original. `npm run check` +
`./gradlew -p backend check` + the three guards green; the smoke confirmed.

## Retrospective

Shipped in four phases. A user can correct (or set) a media's capture time from the lightbox; the correction is
sealed under `K_mem` **inside the EXIF tier**, keeping the extracted original alongside — the server only ever
holds opaque ciphertext.

**Design (with the user).** The correction lives in `wrappedExif` (Tier 2), **not** `wrappedLayout` — that tier
stays geometry-only (`{v,w,h}`, a same-strand cr-0064 refinement done first: `capturedAt` was dropped from the
layout tier since the grid ignores it and the timeline can batch-fetch it). Model: `wrappedExif.capturedAt` =
immutable extracted original; new `wrappedExif.capturedAtOverride` = the correction (epoch-ms); **effective** =
`override ?? capturedAt`. No separate server field/flag (the legacy `captured_at_override` boolean) — "corrected"
is simply "override present", and reset removes it. Persisting = **re-sealing the whole bundle** client-side and
PUTting it (the blind server can't field-merge what it can't read).

**Contract + backend.** A new `PUT /api/scopes/{scopeId}/media/{mediaId}/meta` (operationId `updateMediaMeta`)
with `MediaMetaUpdate { wrappedExif }` → 200 `MediaMetaResponse`, mirroring cr-0064's GET. Threaded through
`MediaResource.updateMediaMeta` → `MediaHandler.updateMediaExif` (reuses the `MediaMetaOutcome` Found/NoMedia) →
`MediaStore.setMediaExif` (transactional overwrite). **No migration** — `wrapped_exif` exists since cr-0064.
Null clears the tier. Slice tests: a PUT→GET round-trip (set + clear, opaque) and `media_not_found` on an unknown
id (13 MediaSliceTest total, green).

**Web.** `media.ts`: `MediaExif` gains `capturedAtOverride`; `effectiveCapturedAt(exif)` and
`setMediaCapturedAt(scopeId, mediaId, K_mem, exif, overrideMs | null)` — the latter re-seals the bundle (override
added/removed) and PUTs it, dropping the tier to null when nothing but `v` is left, returning the new bundle for
local state. Lightbox: a pencil "edit capture time" action in the detail-panel header opens a native
`<input type="datetime-local">` (prefilled with the effective time) with Save / Cancel / Reset (reset shown only
when corrected); the "Taken" row shows the effective time and a "Status: Corrected" row appears when overridden;
a media with **no** EXIF can still have a date set (a fresh bundle). i18n EN+DE (`common.save`, `media.status`,
`editCapturedAt`, `resetCapturedAt`, `capturedAtCorrected`, `capturedAtSaveFailed`). Tests: `media.test.ts`
round-trips set/clear/clear-to-empty/no-EXIF (re-seal opaque, wrong key can't open); `MediaLightbox.test.ts`
drives the edit→corrected→reset flow + the no-EXIF set (23 + 9 tests, green).

**Verification.** `./gradlew -p backend check` + `npm run check` + the three trust-chain guards green.

**Live smoke (re-seeded MariaDB, driven in the preview): confirmed end-to-end.** On the cr-0064 EXIF photo
(Canon EOS R6, taken 2021-05-01): opened the lightbox, corrected the time to **2019-08-15 08:30** → the panel
showed the corrected time + a **"Korrigiert"** row, and `wrapped_exif` was re-sealed (`AvJz…`/320 B → `ApKt…`/
368 B, the +48 B consistent with the added override) with **0 plaintext leaks** (no `2019`, no
`capturedAtOverride`, no `Canon` in the clear). **Reset** → the panel returned to **2021-05-01 10:00**, the badge
cleared, and `wrapped_exif` re-sealed back to 320 B (`AhLb…`, override removed, fresh nonce). `wrapped_layout`
stayed **108 B untouched** throughout — the correction lives entirely in the EXIF tier, as designed.
(Flyway note: editing the already-applied V15 comment in the cr-0064 fix changed its checksum, so the dev DB
needed a one-off `-Dquarkus.flyway.repair-at-start=true` to realign — a local-DB detail, the schema was
identical.)

**Notes / deferred (own CRs):** native (apple/android) parity — the data layer can carry `capturedAtOverride`
after regeneration; native edit UI is their own CR. Finer edit permissions (per-uploader/role) once roles exist.
The Memory-level date modes + chronological timeline (inbox #47) consume the effective capture time from the
EXIF tier via a batch meta fetch.
