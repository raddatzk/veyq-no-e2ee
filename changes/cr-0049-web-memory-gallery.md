---
id: cr-0049
title: Web Memory gallery — grid / lightbox / download over the derivative tiers (web)
status: done
satisfies: [uc.memory.gallery]
adrs: ["0013", "0012", "0010"]
invariants: [operator-blind]
---

## Goal

Turn the placeholder **Memory detail view** into the real **gallery** on the web client: open a Memory and see
its media as a **thumbnail grid**, open any item in a **lightbox** (preview, prev/next), **download** an
original, and toggle the Memory **favorite** — every byte decrypted **on device**. The three-tier data layer
this rides on is already done on all surfaces ([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)
— cr-0045 web / cr-0047 apple / cr-0048 android), and the web favorites client + a 67-line `MemoryDetail.vue`
**placeholder** (renders a "detail coming" note) already exist; this slice replaces the placeholder by porting
the gallery/lightbox presentation from the pre-E2EE design source onto the ready data layer.

**Web only** (ADR 0010 — web-first; the apple/android galleries are their own later CRs). **No new contracts,
backend, or crypto** — `features/memory/media.ts` already exposes `listMedia` + `gridRef`/`lightboxRef`/
`downloadRef` + `openGrid`/`openLightbox`/`openOriginal`, and `features/memory/favorites.ts` the favorite set.

## Context & constraints

- **Mirrors the refined use-case** [uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)
  — open → grid (thumbnail) → lightbox (preview, prev/next) → download (original); header with title, item
  count, favorite toggle. Honor its **Scope of this slice** verbatim.
- **Design source = `legacy/frontend-noe2ee/`** (the pre-E2EE original; see its `_PROVENANCE.md`) — port the
  gallery grid + lightbox **presentation** (`src/styles/memory.css` and the memory-detail views), **swap only
  the data layer** ([docs/ai/README.md](../docs/ai/README.md)). Never port its non-blind data flow
  (server-computed navigation / plaintext media metadata); re-derive everything client-side.
- **Replace, don't add:** the slice builds out the existing
  [`web/src/features/memory/components/MemoryDetail.vue`](../web/src/features/memory/components/MemoryDetail.vue)
  placeholder (the host view already owns the open hook + dialogs from cr-0030/0031). The favorite toggle wires
  the existing [`favorites.ts`](../web/src/features/memory/favorites.ts) / `app/favoritesStore.ts`.
- **Operator-blind read path.** Every tile fetches its **thumbnail** as ciphertext via presigned GET and
  decrypts on device (`openGrid`); the lightbox fetches the **preview** (`openLightbox`), download the
  **original** (`openOriginal`). The server sees only ciphertext fetches — no pixel, filename, or content key.
  Decrypted bytes become **object URLs** for `<img>`; **revoke them on unmount / lightbox close** (no leak, no
  lingering plaintext blob URLs).
- **Per-item fetch, no aggregate endpoint.** A grid of N items = N presigned-GET + decrypt round-trips
  (parallelized); there is no batching beyond what `media.ts` already does. Acceptable at this scope; lazy/
  virtualized loading is a later optimization, not part of this slice.
- **No per-item filename** is stored (operator-blind; only node names are sealed, cr-0028). The **download**
  uses a derived name (e.g. the decrypted **Memory name** + an index + the tier's media type suffix), not a
  real original filename — the `<original-name>` convention in en.memory.derivatives is deferred with
  per-item names.
- **Scope (from the use-case):** **In** — open; thumbnail grid; lightbox (preview, prev/next); download
  original; header (title, item count, favorite toggle); media attached **directly to the Memory node**;
  non-image/video → **icon + name** tile (E1); empty Memory → empty state (E3); locked/closed → unavailable
  (E2). **Out (own CRs):** journal, chapters + chapter-nested media aggregation, map/tracks, playlists,
  per-uploader buckets + mine/group filter, participants, a chosen cover, sharing; and the **apple/android**
  galleries.
- **Verify:** `cd web && npm run check` (typecheck + test + build); the web crypto conformance test stays
  green; a **manual live browser smoke** against a seeded `127.0.0.1` tenant (an image **and** a video Memory)
  with 1–2 screenshots — the same done-gate cr-0030 used.

## Phases

- [x] 1 grid + header — replace the `MemoryDetail.vue` placeholder with the **gallery grid**: `listMedia`
  the open Memory, fetch each item's **thumbnail** (`openGrid`) → object URL → render a grid under a header
  (decrypted title, item count, **favorite toggle** wired to `favoritesStore`). Non-image/video → **icon +
  name** tile (E1); empty Memory → empty state (E3); object URLs revoked on unmount. · Done when: `npm run
  check` green; a component test round-trips a listed Memory → grid tiles + header (real v1 crypto, mocked
  `mediaApi` + blob transport), incl. the empty + non-derivable-tile cases.
- [x] 2 lightbox — clicking a tile opens a **lightbox** that fetches that item's **preview** (`openLightbox`),
  displays it full-view, and **prev/next** page through the grid order (wrap or clamp — pick one, document it);
  Esc/close revokes the preview object URL. · Done when: `npm run check` green; a component test opens →
  pages prev/next → closes, asserting it fetches the **preview** tier (not the original) and revokes URLs.
- [x] 3 download original — an explicit per-item **download** fetches the **original** (`openOriginal`) and
  saves it via a Blob object URL + `<a download>` using the derived filename. · Done when: `npm run check`
  green; a test asserts `openOriginal` is called and a download is triggered with the derived name.
- [x] 4 coverage — flip the **web** entries on
  [uc.memory.gallery.yaml](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml): add the gallery
  **render** test to `thumbnail-grid` / `lightbox-preview` / `download-original` / `operator-blind-fetch`
  (alongside the existing `media.test.ts` data-layer ref) and give the so-far-uncovered **`favorite-toggle`**
  a web ref; apple/android UI render stays a gap. · Done when: `python3 scripts/validate_coverage.py`,
  `validate_changes.py`, `validate_doc_links.py` all green.
- [x] 5 verify (browser smoke) — live stack (MariaDB + `quarkusDev` with the bundled Garage store + `npm run
  seed:dev` on `127.0.0.1` + Vite): open an **image** Memory and a **video** Memory and drive **grid
  (thumbnails) → lightbox (preview, prev/next) → download original → favorite toggle**, every byte decrypted
  on device, server blind. · Done when: the round-trip is confirmed with 1–2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Memory gallery: grid / lightbox / download / favorite | Status |
|---|---|---|
| web | grid + lightbox + download + favorite over the derivative tiers | ◻ this CR |
| apple / android | the native Memory gallery (over the ready deriver data layer) | ◻ **deferred (web-first; own CRs)** |
| later | journal · chapters (+ chapter-nested media) · map · playlists · buckets · sharing | ◻ **own use-cases** |

## Definition of done

On the web, an unlocked member opens a Memory and sees its media as a thumbnail grid (each thumbnail fetched as
ciphertext and decrypted on device), opens any item in a lightbox showing the **preview** with prev/next, can
**download** an item's pristine original, and can **favorite** the Memory — operator-blind end to end (the
server sees only ciphertext fetches). `npm run check` green + the conformance test + the three trust-chain
guards; the live browser image+video smoke confirmed with screenshots. The detail-view siblings (journal,
chapters, map, playlists, sharing) and the apple/android galleries are explicitly deferred to their own CRs.

## Retrospective

Done. The `MemoryDetail.vue` placeholder is replaced by the real gallery: a `MemoryDetail` (header — decrypted
title, item count, favorite/rename/delete — + a uniform square-tile thumbnail grid) hosting a new
`MediaLightbox` (preview tier, prev/next clamped at the ends, Esc/backdrop/close, download the original). All
over the ready three-tier data layer; no new contracts/backend/crypto.

**What landed beyond a straight port.** The new client already carried the design source's `media.css`
(`.media-lightbox*`, `.lightbox-nav*`) + `memory.css`, so the lightbox is a near-verbatim port. Two honest
deviations from the legacy `MediaGrid`, both documented in-code: (1) the grid uses a **uniform square CSS grid**
instead of the legacy absolute-positioned **justified-rows layout engine** — the justified layout needs each
image's natural dimensions and a JS layout pass, which is out of this slice (the CR explicitly defers lazy/
virtualized loading); (2) the lightbox ships **viewer-only** (the metadata sidebar / highlight / captured-at /
delete / fullscreen-original are later CRs).

**Operator-blind, no media type.** `MediaSummary` carries **no MIME** (only opaque metadata + wraps), so the UI
can't tell image from video — both render their JPEG **preview** as `<img>` (a video's preview is its
first-frame poster), and a media **without derivative tiers** (E1) is an icon tile. The download name is derived
on device: Memory name + index + an extension **sniffed from the decrypted original's magic bytes** (new
`download.ts`, fallback `.bin`) — no stored filename ever leaves the client.

**Lightbox nav gotcha.** First pass wrapped the prev/next chevrons in PrimeVue `<Button>`; the theme's
`.p-button{position:relative}` beat the `.lightbox-nav{position:absolute}` edge-positioning (equal specificity,
later source order), so both buttons collapsed into the flow at the top-left. Caught in the browser smoke →
switched to plain `<button>` (matching the legacy markup). Also added a `max-width:640px` header stack so the
title keeps the full row width on narrow viewports instead of breaking mid-word.

**Verify.** `cd web && npm run check` green (typecheck + test + build; **16 new tests** across `download.test.ts`
+ `MemoryDetail.test.ts` + `MediaLightbox.test.ts`, plus the existing data-layer + conformance suites). The
three trust-chain guards (`validate_coverage`/`validate_changes`/`validate_doc_links`) green. Browser smoke
against a live `127.0.0.1` tenant (MariaDB + `quarkusDev` signing presigned URLs for the Vite origin → bundled
Garage, same-origin via the `/veyq` proxy): an **image** Memory (4 tiles → lightbox preview → prev/next clamp →
download presign → favorite toggle + `/favorites` PUT), a **video** Memory (first-frame poster as thumbnail +
preview, single-item clamp), and an **empty** Memory (E3 state) — every byte fetched as ciphertext via
presigned GET and decrypted on device. Screenshots captured in-session (web convention — not committed, per
cr-0030/cr-0045).

**Deferred (own CRs):** journal, chapters + chapter-nested media aggregation, map/tracks, playlists,
per-uploader buckets + mine/group filter, participants, a chosen cover, sharing; the lightbox metadata sidebar;
and the **apple/android** native galleries.

**Drive-by:** flagged a pre-existing flaky assertion in `favorites.test.ts` (`not.toContain('m1')` over random
ciphertext base64) for a separate fix.
