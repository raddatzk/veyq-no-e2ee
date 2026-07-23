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
| `openOriginal` / the full-blob decrypt path | kept — **retained as the no-SW / stale-SW fallback** (see Slice 2 §5d), and the page's per-chunk `ChunkProvider` calls the same `openFrame`; the SW itself never decrypts |
| **the play binding** `videoSrc = URL.createObjectURL(typedBlob(await openOriginal(...)))` | on the SW path becomes register-with-SW + `src="/__media/<handle>"`; the full-blob binding **stays as the fallback**, not deleted |

So the only Slice-1-specific code is one `src` binding over a permanent fetch path — and at Slice 2 that full-blob
path is **kept as the no-SW / stale-SW fallback**, not thrown away (the earlier "one line swapped, eager fetch
deleted" framing was too sharp; a fallback for a missing or old SW is a necessity, not throwaway code). **"Limited
to small videos" is the honest UX of that interim, not a coded gate** — the cap is gone, so a large video simply
takes as long as its
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

### Slice 2 — the Service-Worker range layer (refined 2026-07-22; build on explicit "go")

Refined against the code; all seven design forks decided: **(1)** delegation — the SW holds no key; **(2)** edit
ADR 0047, not a new one; **(3)** include the share viewer via the provider seam; **(4)** keep the Slice-1 full-blob
path as the no-SW fallback; **(5)** whole-blob presign, subset deferred; **(6)** raw module-SW entry, no new dep;
**(7)** moderate prefetch as a re-tunable UX dial.

**Corrected architecture (supersedes ADR 0047's original mechanism).** The SW holds **no key and no auth**: it maps
the demuxer's plaintext byte-range to the covering ciphertext chunk indices and requests those chunks' **decrypted
plaintext** from the owning page over a `MessagePort`. The page runs the existing presign→`openFrame` path in its
own context and transfers plaintext back; the SW slices the window and answers `206`. The synthetic URL carries an
**opaque per-playback handle**, never `blobId` and never a credential.

**Why the URL form changed — this is the finding that turned the design.** `/__media/<blobId>` assumes the SW can
presign a chunk GET from `blobId` alone. It cannot: presign is bearer-authed for the internal app
(`presignBlobDownloads`, token in page memory + `withRefresh`) and share-token-authed for the anonymous **share
viewer** (`resolveShareBlob`, `shareId` + guest `pwToken`), both whole-blob only. A SW keyed on `blobId` has
neither credential and cannot tell the routes apart — it breaks first and most visibly at the share viewer. So the
URL form was *wrong*, not merely incomplete.

Phase order: **5a (ADR edit) → 5b → 5c → 5d → 5e.** 5a lands the ADR revision *before* any code, so the
trust-chain validates the mechanism that is actually built.

- [x] **5a docs — revise ADR 0047 to the delegation mechanism (do FIRST).** Apply the revision spelled out under
      *"ADR 0047 revision proposal"* below: rewrite the Decision, redraw the mermaid diagram, retitle the SW as
      key-free, and append a dated Revision note whose two load-bearing points are **prominent, not footnotes** —
      (i) **keys never leave the page**, the key-in-SW variant deliberately rejected; (ii) the plaintext cache is
      **in-memory + size-bounded only, never Cache Storage / IndexedDB.** Done when: the ADR reads delegation
      (page presigns/decrypts, SW key-free, URL = handle) and `for f in scripts/validate_*.py; do python3 "$f";
      done` stays green (doc-only edit; touches no coverage/contract anchors).
      **DONE:** Decision/mermaid/handle-note rewritten + dated Revision note appended (both load-bearing points
      prominent); trust-chain 14/14 green after the doc-only edit.

- [x] **5b web — the page-side `ChunkProvider` seam.** A per-chunk fetch+decrypt over the existing presign
      plumbing, factored out of the whole-blob path.
      Sites:
      - `web/src/features/memory/media.ts:729` (`openBlobFromUrls`) — extract `getPlaintextChunk(scopeId,
        memoryKey, ref, chunkIndex): Promise<Uint8Array>`: lazily presign the **whole** blob once
        (`presignBlobDownloads`, `media.ts:741`), cache the `PresignedUrl[]` by index + issue time, fetch the one
        covering chunk (`getChunk`, `media.ts:776`), `symUnwrap`→K_blob (cache per blob), then
        `openFrame(blobKey, chunkIndex, **ref.chunkCount**, frame)`. **AAD trap:** pass the full `ref.chunkCount`
        as `total`, never the range subset — `openFrame` binds `index‖total` in the AAD (`v1.ts:196`), so a subset
        `total` fails every open. Re-presign the whole array on a `403`/expiry (TTL = 600 s,
        `application.properties:87`), then retry.
      - `web/src/features/memory/share.ts:549` (`openShareBlob`) — the same seam for the anonymous viewer via
        `resolveShareBlob({shareId, blobId, xSharePassword})`, same `openFrame` core, `pwToken` from the share
        session. Yields a **share-context** `getPlaintextChunk` (this is what makes fork 3 free — the SW never
        learns which route).
      Done when: `npm run check` green; unit tests for `getPlaintextChunk` cover the whole-blob-presign cache, the
      `chunkCount`-as-`total` AAD path (a subset-`total` case must go red), and 403→re-presign→retry.
      **DONE:** `makeChunkProvider` (shared core) + `chunkProvider` (authed) in `media.ts`; `shareChunkProvider`
      in `share.ts`; `openBlob` refactored onto a shared `presignAllChunks`. `media.test.ts` covers the round-trip,
      the presign-once cache, the **AAD bite** (provider opens with `total = chunkCount`; a subset-`total` `openFrame`
      is asserted to throw), 403→re-presign→retry, and the out-of-range guard; `share.test.ts` covers the anonymous
      provider forwarding the guest `pwToken`.

- [x] **5c web — the Service Worker (raw module entry, key-free).**
      Sites:
      - **New `web/src/sw/range.ts`** (pure, no SW globals — unit-tested directly): parse `Range` (first probe
        `bytes=0-1`, open `bytes=N-`, suffix `bytes=-N`, no-Range → `200` + `Accept-Ranges`), compute
        `plaintextTotal = byteSize − 28*chunkCount`, the covering indices, `Content-Range`/`Content-Length`, and
        the window slice. (open: confirm `<video>` switches to ranges after a no-Range `200`+`Accept-Ranges`.)
      - **New `web/src/sw/mediaSw.ts`** (SW entry, `self`-based, `{type:'module'}`, `skipWaiting`+`clients.claim`):
        `fetch` handler for `/__media/<handle>` **only** (everything else falls through — no precache). Holds
        `Map<handle,{port,chunkCount,byteSize}>` + a **bounded in-memory plaintext LRU (never Cache Storage /
        IndexedDB)**; requests missing chunks over the registered port.
      - **New `web/src/sw/register.ts`** (main thread): lazy `navigator.serviceWorker.register('/sw.js',
        {type:'module', updateViaCache:'none'})` on first play; a **versioned handshake** (protocol version in the
        register message → mismatch means fall back to Slice-1); `registerPlayback(handle, meta, provider)` wires a
        `MessageChannel`, `revokePlayback(handle)` tears it down.
      - Vite build: emit the SW as its own input at a **stable, ungehasht `/sw.js`** (update detection needs a
        stable name). (open: exact Vite config for a single-SW output + dev serving.)
      Done when: `npm run check` green; `range.ts` unit tests cover every range case + `plaintextTotal`; the SW
      fetch/port RPC is tested against a mocked port + `range.ts`.
      **DONE:** `sw/range.ts` (pure, unit-tested), `sw/mediaSw.ts` (key-free entry, in-memory LRU only,
      streaming `206` body with cancel + read-ahead dial), `sw/register.ts` (lazy register, versioned handshake,
      per-playback port), `sw/protocol.ts` (shared wire types). Vite emits a stable **unhashed `dist/sw.js`**
      (verified in the build output); dev serves `/sw.js` via a `configureServer` middleware with
      `Service-Worker-Allowed: /`. **Open point resolved:** the SW typechecks under the app's DOM lib (no WebWorker
      lib, no separate tsconfig) by declaring the SW scope locally — see the `mediaSw.ts` header. `range.test.ts`
      (21 cases) + `mediaSw.test.ts` (LRU, port RPC, 200/206/416, routing, handshake/register/revoke) green.

- [x] **5d web — the switchover (keep the Slice-1 fallback).**
      Sites:
      - `web/src/features/memory/components/MediaLightbox.vue:256` (`playVideo`) — on play, if a controlling SW
        answered the handshake: open a channel, `registerPlayback(handle, {chunkCount, byteSize},
        getPlaintextChunk)`, set the video src to `/__media/<handle>`. **No SW / stale SW / registration failure →
        fall back to the Slice-1 full-blob path** (`originalOf` → typed `blob:`), unchanged. Revoke on item switch
        / close. **Click-to-play stays** (no auto-fetch on lightbox open).
      - ShareView: the `getPlaintextChunk` provider passed from `ShareView.vue:266` closes over the share context
        (`shareId` + `pwToken`), so the SW path covers the anonymous viewer without knowing the route. `readonly`
        still gates the mediaType backfill (`MediaLightbox.vue:294`), unchanged.
      - **Prefetch:** a moderate read-ahead default (a **named constant marked as a UX dial to re-tune after
        live-verify**, not a frozen value). Not a privacy parameter (ADR 0047 seek-leak is accepted).
      Done when: `npm run check` green; `MediaLightbox.test.ts` covers the SW-path play (handle src +
      register/revoke) **and** the no-SW fallback to the Slice-1 blob path; SW-intercept playback + seek
      live-verified manually on `veyq.localhost` (see #102 note).
      **DONE (code + unit):** `MediaLightbox.vue` tries `registerPlayback` first (src = `/__media/<handle>`, no
      whole-blob fetch) and **falls back** to the Slice-1 typed blob on no/stale SW; revokes on switch/close/unmount;
      the SW gets the sealed `mediaType` for the response `Content-Type`. `ShareView.vue` injects a
      `videoChunkProvider` closing over the share context (per-item `K_mem` + `pwToken`). Prefetch = the
      `READ_AHEAD_CHUNKS` UX dial in `mediaSw.ts`. `MediaLightbox.test.ts` covers both the SW-handle path (no
      original fetch + revoke) and the no-SW fallback. **Still pending:** the manual SW-intercept + seek
      live-verify on `veyq.localhost` — needs a seeded video + a running stack, so it rides #102 (see note); not
      yet run.

- [x] **5e coverage + docs.** `docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml` gains:
      `sw-range-streaming` (range→chunk + 206), `sw-key-free-delegation` (page decrypts, SW keyed on handle,
      plaintext in-memory only), `sw-presign-expiry` (403 re-presign), `sw-share-viewer` (anonymous path via the
      provider), `sw-no-sw-fallback` (Slice-1 fallback) — each with its web unit ref. Update
      [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) (§3 now *streams*, not full-blob)
      and the [security-model.md](../docs/crosscutting/security-model.md) line (the accepted intra-media
      access-pattern of a *playing* medium — ADR 0047 consequence). Done when: `validate_coverage.py` +
      `for f in scripts/validate_*.py; do python3 "$f"; done` all green.
      **DONE:** five criteria added (`sw-range-streaming`, `sw-key-free-delegation`, `sw-presign-expiry`,
      `sw-share-viewer`, `sw-no-sw-fallback`), each with its web unit ref. `uc.memory.gallery.md` §3 + the EN/DE
      plain-language section now say *streams*, not full-blob. The `security-model.md` accepted-access-pattern line
      already reads client-generic ("which chunks a playing client fetches") — still correct under delegation (the
      **page** fetches), so left as-is. `validate_coverage.py` + trust-chain 14/14 green.

**#102 (no e2e orchestrator) note.** 5b/5c/5e are fully unit-testable without an e2e orchestrator; that is where
the real logic lives. The end-to-end proof — a real SW intercepting a `<video>` that Safari drives with byte
ranges — needs browser e2e and rides the #102 debt like everything else. Until #102 lands it is **manual
live-verify only** (browser preview on `veyq.localhost`); do **not** record it as automated coverage.

### ADR 0047 revision proposal (executed by phase 5a — not yet applied)

Edit [`docs/decisions/0047-inline-video-playback-range-streaming.md`](../docs/decisions/0047-inline-video-playback-range-streaming.md).
The **decision** (option C over A/B: a SW range shim, native demuxer drives ranges, no MSE/remux) and the
Context/seek-leak/sealed-`mediaType` sections stay. Only the **mechanism** is corrected.

**(a) Replace the Decision body (current lines 26–35)** with:

> **Play video through a Service Worker that intercepts a synthetic in-origin media URL and answers `206 Partial
> Content`, while the page — not the SW — presigns, fetches and decrypts.** The browser's own `<video>` demuxer
> drives the byte ranges; there is no MSE and no remux. The SW holds **no key material and no auth**: it maps the
> demuxer's plaintext byte-range to the covering ciphertext chunk indices and requests the **decrypted plaintext**
> of those chunks from the owning page over a `MessagePort`; the page runs the existing presign→`openFrame` path
> in its own context and transfers the plaintext back. The SW slices the requested window and responds with
> `Content-Range` + `Accept-Ranges: bytes`.
>
> Concretely: before playback the page **registers** it with the SW (`postMessage`: an opaque per-playback handle
> + `{chunkCount, byteSize}` + a `MessagePort`), then sets `<video src="/__media/<handle>">`. For a `Range:
> bytes=A-B` request the SW computes the covering chunk indices `[⌊A/CHUNK⌋ … ⌊B/CHUNK⌋]` from the registered
> `{chunkCount, byteSize}`, asks the page for those chunks' plaintext over the port (with a small in-memory
> read-ahead cache), assembles the window and answers `206`. **No key, no auth token and no plaintext-at-rest ever
> enters the SW; the URL carries only an opaque handle, never a credential.** `K_blob`/`K_mem` stay exactly where
> they live today — in the page.

**(b) Replace the mermaid diagram (current lines 51–65)** with:

> ```mermaid
> sequenceDiagram
>   participant V as native video demuxer
>   participant SW as Service Worker (in-origin, key-free)
>   participant P as page (holds K_blob + auth)
>   participant B as backend (blind, presign)
>   participant O as object store (blind)
>   Note over P,SW: before play — page registers {handle, chunkCount, byteSize, MessagePort}
>   V->>SW: GET /__media/&lt;handle&gt;  Range bytes=A-B
>   SW->>SW: chunks = overlap(A..B, CHUNK_SIZE)
>   SW->>P: (port) need plaintext for chunk indices
>   P->>B: presign GET (bearer / share-token) — whole blob, cached
>   B-->>P: presigned URLs
>   P->>O: GET the ciphertext chunks (direct)
>   O-->>P: AEAD frames
>   P->>P: openFrame(K_blob, index, total = chunkCount)
>   P-->>SW: (port) plaintext chunks
>   SW->>SW: slice A..B, assemble 206
>   SW-->>V: 206 Partial Content, Content-Range, Accept-Ranges bytes
> ```

**(c) Fix the "keys only on blobId" note (current lines 97–99):** the synthetic route now keys on an **opaque
per-playback handle** (not `blobId`) plus range offsets; the "exposes nothing new about *what* a blob is" reasoning
holds a fortiori — a random handle reveals even less than the `id`-class `blobId`.

**(d) Append a dated Revision note** — with these two points **prominent**, plus the *why*:

> ## Revision — 2026-07-22 (before build)
>
> Refining Slice 2 (the SW slice) against the code showed the original mechanism is not buildable as written and
> that a strictly better design exists. The **decision** (SW range shim; option C over A/B) is unchanged; the
> **mechanism** is corrected as above.
>
> - **Why it changed — `blobId` alone cannot carry the auth context.** `/__media/<blobId>` assumed the SW could
>   presign a chunk GET from `blobId`. It cannot: presigning is bearer-authed for the internal app
>   (`presignBlobDownloads`, token in page memory + `withRefresh`) and share-token-authed for the anonymous
>   **share viewer** (`resolveShareBlob`, `shareId` + guest `pwToken`), and both are whole-blob only. A SW keyed on
>   `blobId` has neither credential and cannot tell the routes apart — it breaks first, and most visibly, at the
>   share viewer. The corrected route carries an **opaque per-playback handle**; the auth context lives behind the
>   registered `MessagePort`, in the page.
> - **Keys never leave the page — the key-in-SW variant is deliberately rejected.** Because presign+auth must stay
>   in the page anyway, the page also keeps the keys and does the decrypt; the SW receives **plaintext**, never key
>   material. Handing a key to the SW — even a per-blob `K_blob` — was considered and rejected: it adds a second
>   key-bearing context whose lifetime must be managed and whose key must be purged on vault-lock, for no benefit.
>   **The safest way to clean up a key in the SW is to never put one there.** `K_mem` in the SW is strictly worse
>   (it opens every blob of the Memory) and was never an option. This makes the trust-tier note above *stronger*,
>   not weaker: the SW is operator-served code that now holds no confidential key.
> - **The plaintext cache is in-memory and size-bounded only — never Cache Storage, never IndexedDB.** The SW
>   keeps at most a small read-ahead window of decrypted chunks in memory, evicted by size and dropped when
>   playback ends. Persisting decrypted media to disk would be a real regression against today (no plaintext is
>   ever stored) and is forbidden here explicitly — it is the shortcut a later change is most tempted to take.

## Definition of done

**Slice 1 (this session):** `npm run check` green (707 pass, 4 skipped); every `scripts/validate_*.py` green (the
trust-chain glob, 14/14); `docs/coverage/` updated for `uc.memory.gallery` with a test per new criterion.
**Structural invariant — `mediaType` stays sealed:** proven by *the absence of a contract change* +
[ADR 0044](../docs/decisions/0044-field-visibility-declared-at-the-field.md)'s already-permanent
`validate_field_visibility.py`; no new guard is added because the standing one already fails on any server-visible
reintroduction (phase 1 recorded the empty `contracts/` diff as the one-shot proof).

**Slice 2 (this session):** all of 5a–5e landed; `npm run check` green (752 pass, 4 skipped) with the SW emitted
as a stable unhashed `dist/sw.js`; trust-chain 14/14 green; `uc.memory.gallery` coverage gained the five `sw-*`
criteria. **The one remaining item is a manual live-verify** — a real Service Worker intercepting a `<video>` that
the browser drives with byte ranges, plus a seek, on `veyq.localhost` — which needs a seeded video + a running
stack and so **rides #102** (no e2e orchestrator). It is deliberately **not** recorded as automated coverage. Once
that live-verify passes, the CR can be deleted per the repo convention (its lessons already live in ADR 0047 + the
in-code comments) — until then it stays the live work order for that last check.

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

## Retrospective (Slice 2)

- **The refine paid off — clean-room build, zero design rework.** Every one of the seven forks was already decided
  in the CR, so building was mechanical: 5a→5e in order, no stop-and-ask on architecture. The one place I *added*
  to the plan (a shared `sw/protocol.ts` for the page↔SW wire types) was a supporting module, not a fork — worth
  noting that a refine listing "three new files" can grow a fourth glue file without it being a design change.
- **Open point #1 (Vite single-SW output) resolved better than feared.** `rollupOptions.input` with a per-chunk
  `entryFileNames` gave a stable unhashed `dist/sw.js` on the first try. The bundler even inlined the SW-only
  `range.ts` and left only a 0.03 kB shared `protocol` chunk that `sw.js` imports by a root-relative path — still
  update-safe (a protocol change re-hashes that import, so `sw.js` bytes change and the browser detects the
  update). No new dependency, as required.
- **Open point resolved by *typing*, not tooling (the real time-saver).** The module-SW dev-loop friction the CR
  flagged is fundamentally the WebWorker-lib-vs-DOM-lib `self` collision. Rather than a second tsconfig +
  `/// <reference lib="webworker" />` (which fragments the typecheck), the whole SW typechecks under the app's DOM
  lib by declaring the sliver of the SW scope locally (`MediaSwScope`) and self-installing behind a
  `globalThis.skipWaiting` guard. One file, one `npm run typecheck`. This is the pattern to reuse for any future
  worker entry.
- **A `*/` inside a JSDoc string is a real parse trap.** `` `bytes */total` `` in a doc comment closed the comment
  early and produced a parse error three lines down — a good reminder that oxc/esbuild will bite on `*/` in
  comment prose even when it's describing a string literal. Cheap to fix once spotted, cheap to avoid (don't put
  `*/` in comment text).
- **The AAD bite (5b) is the load-bearing test and it held.** Writing "provider opens with `total = chunkCount`
  (green) + the subset-`total` `openFrame` throws (red)" as one test makes the trap self-documenting: a future
  editor who "optimises" to pass the range subset as `total` gets an immediate red, not a silent decrypt failure
  at playback.
- **What's genuinely unproven and named as such:** the end-to-end SW intercept (a browser driving a real
  `<video>` with byte ranges + a seek) and the best-effort dev `/sw.js` middleware are **not** unit-testable and
  were not live-verified (no stack started, per the no-parallel-quarkusDev rule). They ride #102. Everything where
  the real logic lives — range math, port RPC, 206 assembly, the provider crypto/AAD/retry, the switchover +
  fallback — is unit-covered; the tail is the wiring only a browser can exercise.
