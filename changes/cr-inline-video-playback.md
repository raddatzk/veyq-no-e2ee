---
id: cr-inline-video-playback
title: Inline video playback + a persisted video signal (mediaType)
status: in-progress
satisfies: [uc.memory.gallery]
adrs: ["0047", "0044", "0012", "0013", "0026"]
invariants: [operator-blind]
---

## Goal

A video in a Memory is a still after reload and a photo-lookalike on its tile (inbox #104/#105). This CR gives a
medium a **persisted, operator-blind video signal** — the full MIME type, sealed inside the existing `wrappedMeta`
tier under `K_mem`, never server-visible — and uses it to (a) draw a **play badge** on a video tile and (b) render
a **`<video>` branch** in the lightbox. The playback **strategy** is [ADR 0047](../docs/decisions/0047-inline-video-playback-range-streaming.md)
(Service-Worker range streaming); this CR builds **Slice 1** — the field, the detection, the badge, the `<video>`
element, a lazy backfill for pre-field media, and a **naïve interim playback** — and **defers the Service-Worker
range layer to Slice 2**. Slice 1 must stand and ship without Slice 2.

## Context & constraints

- **State this builds on (already written):** [ADR 0047](../docs/decisions/0047-inline-video-playback-range-streaming.md)
  (the playback strategy + the accepted seek-leak + the sealed-`mediaType` stance), [ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)
  (why `mediaType` is sealed, not a contract field), [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)
  (the lightbox + grid this extends), [en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)
  (the poster tier), [security-model.md](../docs/crosscutting/security-model.md) (the accepted access-pattern line).
- **Invariant at risk — operator-blind.** `mediaType` is the whole risk surface: it must be sealed inside
  `wrappedMeta` under `K_mem` and **never** become a contract/DB field. The standing guard is already in place —
  [ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)'s `validate_field_visibility.py`
  fails the build on any unclassified scalar in a contract body, so *adding no contract field* is exactly what
  keeps `mediaType` server-blind. No new guard is needed; this CR adds zero contract fields (verify below).
- **`wrappedMeta` has a 512-byte cap** (`MediaMetaUpdate.wrappedMeta`, `contracts/api/media.openapi.yaml`). The
  sealed tier today is a handful of numbers; a `"mediaType":"video/quicktime"` string is ~30 chars of plaintext.
  Confirm the sealed+base64 tier stays well under 512 (it will) — but the CR notes the cap so a future field-adder
  sees it.
- **Web check does not regenerate the API client** ([web/CLAUDE.md](../web/CLAUDE.md)). This CR changes **no
  contract**, so a green `npm run check` is honest here — but the contracts phase below exists precisely to
  *prove* the no-change claim, not to assume it.

## The Slice-1 playback question — how it stays clean without throwaway code

ADR 0047 rejects **full-blob playback (option A)** as the *strategy* and picks the Service Worker (option C). But
the SW is Slice 2. So how does Slice 1 play anything without writing the option-A glue the ADR discards?

**Resolution — the interim is the *naïve* full-blob path, and only the naïve one.** Slice 1's `<video>` plays
from a `blob:` URL of the **whole decrypted original**, fetched through the **existing** `openOriginal` path (the
same `K_blob`-unwrap → presign → `openFrame` the download button already uses). What ADR 0047 forbids is the
**engineering that would make full-blob playback good enough to keep** — the inline-size cap (dropped by decision),
a bespoke buffering/seek layer, MSE/fMP4 remuxing, a download-tuned progress UI. Slice 1 builds **none** of that.

Nothing is thrown away when Slice 2 lands:

| Slice 1 artifact | Fate at Slice 2 |
|---|---|
| the `<video>` element + poster/play-overlay markup | kept |
| sealed `mediaType` + `unsealMeta` allowlist + the sniffer | kept |
| the play badge (tile + lightbox) | kept |
| `openOriginal` (the decrypt path) | kept — the SW calls the same `openFrame` |
| **the one line** `videoSrc = URL.createObjectURL(typedBlob(await openOriginal(...)))` | **swapped** to `src="/__media/<blobId>"`; the eager fetch is deleted |

So the only Slice-1-specific code is one `src` binding over a permanent fetch path. **"Limited to small videos" is
the honest UX of that interim, not a coded gate** — the cap is gone, so a large video simply takes as long as its
full decrypt (the always-present **download** button is the escape hatch), and Slice 2 removes the wait. Playback
is **click-to-play**: the stage shows the poster + a play control; clicking fetches + decrypts the original and
swaps in the `<video autoplay controls>`. No auto-fetch of originals on mere lightbox open.

> This is the one design fork ADR 0047 left to the CR. If the owner prefers **no** inline playback in Slice 1
> (poster + badge only, "play" routes to download until the SW exists), only the `<video>`-wiring + interim-fetch
> parts below change; the field, badge, backfill, and sniffer are identical. Confirm before implementing.

## Phases

- [x] **1 contracts — prove the no-change claim.** No spec edit. `mediaType` rides the opaque `wrappedMeta`.
      Done when: `git diff --stat contracts/` is **empty** after the CR, and
      `python3 scripts/validate_field_visibility.py` is green (the standing guard that no server-visible
      `mediaType` snuck in). Record the `wrappedMeta` 512-byte headroom check here.
      **DONE:** `contracts/` diff empty; `validate_field_visibility.py` green; a full sealed tier
      (`{v,w,h,capturedAt,capturedAtSource,gpsLat,gpsLng,mediaType}`) seals to ~210 base64 chars — well under the
      512 cap on `MediaMetaUpdate.wrappedMeta`.
- [x] **2 web — the field.** Seal + read `mediaType` in the metadata tier, and a MIME sniffer for backfill.
      Sites (run, don't tally):
      - `grep -n "MediaMeta\|MEDIA_META_CTX\|unsealMeta\|sealMetadata\|setMediaMeta" web/src/features/memory/media.ts`
        — add `mediaType?: string` to the `MediaMeta` type, emit it in `sealMetadata` from `source.mediaType`,
        **add it to the `unsealMeta` allowlist** (an unlisted field is dropped on read), and add a write path that
        seals a backfilled `mediaType` into the tier (generalize `setMediaMeta`'s re-seal, or a focused sibling —
        it must not disturb `capturedAtOverride`).
      - `grep -n "sniffExtension\|ftyp\|RIFF" web/src/features/memory/download.ts` — add `sniffMediaType(bytes):
        string | undefined` returning a **full MIME** (JPEG/PNG/GIF/WebP/PDF/HEIC/MP4 from the existing table **+
        WebM/MKV via the EBML magic `1A 45 DF A3`**, DocType-read to pick `video/webm` vs `video/x-matroska`);
        refactor `sniffExtension` to derive off the shared table so the two never drift.
      Done when: `npm run check` green; new unit tests for seal/unseal of `mediaType`, the write path, and
      `sniffMediaType` (incl. a WebM fixture) pass.
- [x] **3 web — the video signal in the UI.** The `<video>` branch, the play badge, the lazy backfill.
      Sites:
      - `web/src/features/memory/components/MediaLightbox.vue` — a `<video>` branch beside the `<img>` at the
        stage: when the item's sealed `mediaType` is `video/*`, show the poster + a play control; on play, fetch
        the original via the existing `originalOf`, wrap it in a **typed** `Blob` (`{ type: mediaType }`), and set
        it as `<video autoplay controls>`. Reuse the object-URL lifecycle already there. **No size cap.**
      - `web/src/features/memory/components/MediaTile.vue` (+ its callers —
        `grep -rln MediaTile web/src/features/memory`) — add an `isVideo` prop and draw a **play-badge overlay
        over the poster** via the existing `#overlay` slot; thread it from `MemoryDetail.vue` / `MemoryBuckets.vue`
        off the item's sealed `mediaType`.
      - `web/src/features/memory/components/OptimisticTile.vue` + `useOptimisticTiles.ts` — #105's other half: an
        uploading video currently **replaces** its preview with a `pi-video` icon (`isVideo` already computed off
        `File.type`). Make it a **badge over the local preview**, matching the persisted tile, so the two states
        finally agree.
      - **Lazy backfill:** in the lightbox's `download` (and the play path), when the fetched original's item had
        **no** sealed `mediaType`, `sniffMediaType` the bytes and, if it yields one, write it back (phase-2 write
        path) + `emit('updated', …)` so the grid re-lists with the badge. **Accepted boundary (ADR 0047 /
        inbox #105):** a pre-field video that is never fetched stays badge-less — do not eagerly fetch originals to
        backfill.
      Done when: `npm run check` green; `MediaLightbox.test.ts` covers the video branch (poster→play→`<video>`) and
      the download-time backfill; the tile test covers the `isVideo` badge.
- [x] **4 coverage + docs.** `docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml` gains criteria:
      `sealed-media-type` (seal/unseal + no contract field), `lightbox-video-playback` (the `<video>` branch +
      interim full-original source), `video-play-badge` (tile + optimistic), `media-type-lazy-backfill` (sniff on
      original-fetch + the accepted boundary) — each with its web unit refs. Update
      [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) (main flow §3 gains the video
      branch; E1's *"neither image nor video"* stays, but the lightbox is no longer image-only) and a one-line note
      in [en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md) (the poster is still a
      still, but the original is now **playable**, not download-only). Done when: `python3 scripts/validate_coverage.py`
      green and `for f in scripts/validate_*.py; do python3 "$f"; done` all green. **DONE — all 14 green.**

### Slice 2 — deferred (do NOT build yet; this CR stays open for it)

- [ ] **5 web — the Service-Worker range layer** ([ADR 0047](../docs/decisions/0047-inline-video-playback-range-streaming.md)):
      a SW intercepting `/__media/<blobId>`, mapping the demuxer's plaintext byte-ranges to the covering
      ciphertext chunks, `openFrame`-decrypting them and answering `206 Partial Content`. Swaps the Slice-1 play
      binding (`<video src="blob:…full-original…">`) for `<video src="/__media/<blobId>">` and deletes the eager
      full-original fetch — the poster, badge, sealed `mediaType`, sniffer, and backfill all carry over unchanged.
      Carries the **accepted seek-leak** (ADR 0047) and a tunable read-ahead prefetch. **Its own slice — refine
      separately before building.**

## Definition of done

**Slice 1 (this session):** `npm run check` green (707 pass, 4 skipped); every `scripts/validate_*.py` green (the
trust-chain glob, 14/14); `docs/coverage/` updated for `uc.memory.gallery` with a test per new criterion.
**Structural invariant — `mediaType` stays sealed:** proven by *the absence of a contract change* +
[ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)'s already-permanent
`validate_field_visibility.py`; no new guard is added because the standing one already fails on any server-visible
reintroduction (phase 1 recorded the empty `contracts/` diff as the one-shot proof). **The CR is not `done` until
Slice 2 lands** — it stays the live work order for the service-worker range layer.

## Retrospective (Slice 1)

- **Planning miss (mine, caught cheap):** the refine framed `mediaType` as sealed "so the tier is only present
  when there's derivable metadata" — but sealing `mediaType` for *every* media inverted that: two existing tests
  encoded the old "no dims → no tier" contract and went red. The behavioural change was correct and intended, but
  the CR should have *named* it ("every typed media now carries a `wrappedMeta` tier; the empty-MIME case is the
  only null tier") so the test delta was expected, not a surprise at implement time. **Root cause:** the inventory
  listed the *sites* to touch but not the *behaviour that changes at them*. Cheap to fix (update the two tests +
  add the empty-MIME case), but it's the kind of thing the refine could have flagged.
- **Execution miss (mine):** `MemoryBuckets.test.ts` mocks `../media` with an explicit export list, so adding
  `isVideoMediaType` to the module broke its render until I added the export to the mock. A grep for
  `vi.mock('../media'` before adding a new `media.ts` export would have caught it in one step instead of via a red
  render trace. **Next time:** when adding an export to a module that has hand-listed `vi.mock` doubles, grep the
  test tree for those mocks *first* and extend them in the same edit.
- **Process (worked well, worth keeping):** stopping to confirm the Slice-1 playback fork before writing the CR's
  Slice-1 detail was the right call — the "one-line swap" framing was the actual decision, and getting an explicit
  "los" on it meant zero rework. The one thing I'd do earlier: state the *empty-MIME → null tier* edge in that same
  exchange, since it's the boundary that touched existing tests.
- **Next time:** in a refine that adds a field to an always-listed sealed tier, write one line on "which media now
  gain a tier they didn't have" — the listing-payload delta is exactly what existing round-trip tests assert on.
