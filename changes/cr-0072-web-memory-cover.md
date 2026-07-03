---
id: cr-0072
title: Web Memory cover — a deliberately chosen, cropped cover shown everywhere (card · detail · map)
status: planned
satisfies: [uc.memory.cover]
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Give a Memory a **cover**: the user picks **one of the Memory's own media** and **crops** it, and that one cover is
shown **everywhere the Memory appears** — the browse **card**, the **detail** header, and the **map pin** (cr-0071).
**No auto-cover** — until the user sets one, every surface shows the **placeholder** (the existing soft-accent
`pi pi-images` tile). This ports the pre-E2EE design source's cover flow (`legacy/frontend-noe2ee` —
`MemoryCoverDialog` + `MemoryCoverImage` + the `CoverCrop` shape) onto the E2EE data layer, and **retires the
cr-0071 stopgap** where the map pin auto-picked the photo nearest the place (`pinCover`): the pin now shows the same
explicit cover as the card.

Operator-blind by construction: the cover is **not** a new blob or endpoint. It's a `cover { mediaId, crop }`
sub-object sealed on the node's `wrappedMeta` under `K_mem` (ADR 0012), beside the `date` (cr-0067) and `location`
(cr-0069), set via the same partial `updateNode` (read-modify-write). The **crop is normalized 0..1** against the
source image, so one stored crop renders identically at every size — no per-surface crop, no cropped-image upload;
the display just expands the decrypted thumbnail inside an `overflow:hidden` box and offsets it. The server only
ever sees ciphertext. Web-only (native parity is a later CR, ADR 0010).

## Context & constraints

- **Source = the Memory's own media (this CR).** The picker lists the Memory's decrypted media (the tiers the
  gallery already loads); the cover is one of them, referenced by `mediaId`. The **cover can also be a brand-new
  image that is *not* a Memory media** — but that means a **standalone cover blob** (contract + backend: commit +
  serve + GC a blob attached to a node with no `veyq_media` row), so it's split into a **follow-up CR (cr-0073)**
  that reuses this CR's display + crop dialog and just adds the upload source. This CR stays **web-only** over
  existing media.
- **Sealed in `wrappedMeta`, read-modify-write.** `cover { mediaId, crop{x,y,width,height} }` joins the
  `{ v, date?, location?, cover? }` blob; `setMemoryCover` unseals, swaps only `cover` (or drops it for remove),
  re-seals — never clobbering the sibling date/location. No contract, backend, or DB change.
- **One normalized crop, applied at display.** `crop` is `0..1` vs the source image (the legacy `CoverCrop`).
  `coverImageStyle(crop)` gives the `<img>` styles (`width:100/width%`, `left:-(x/width)·100%`, …, `object-fit`);
  the same helper drives the card, the detail header, and the map pin, so a crop looks identical everywhere.
- **Crop editor = hand-rolled, no library.** A fixed **16:9** viewport with **drag-to-pan** (pointer events) +
  a **zoom slider (1–3×)**; base-scale fills the viewport, pan is clamped so no empty corners; save normalizes the
  pan/zoom back to the `0..1` rect (legacy math). No new dependency (matches *no deprecated/heavy deps*).
- **No auto-cover.** Unset ⇒ placeholder on every surface. (Deliberate: a cover is a curation choice, like the
  location — it never appears from nothing.)
- **Retire `pinCover`.** The map pin's cover source switches from "nearest media" to the node's explicit
  `cover.mediaId`; `pinCover.ts` (+ its test) is removed and the map/browse cover-load path resolves the chosen
  cover instead. Pins/cards with no cover show the placeholder.
- **Scope.** Web only, cover **from existing media**. **Deferred:** a cover **from a new upload** (the standalone
  cover-blob path — contract + backend + web) → **cr-0073**, reusing this CR's display + crop dialog; native cover
  parity (web-first, ADR 0010); per-surface distinct crops (one 16:9 crop is reused everywhere).

## Phases

- [x] 1 model — new `memoryCover.ts`: `CoverCrop`/`MemoryCover` types, `normalizeCoverCrop`/`normalizeMemoryCover`
  (clamp 0..1, keep the rect on-image), `unsealMemoryCover`, `setMemoryCover` (RMW, preserves date+location),
  `sameMemoryCover`, and the pure `coverImageStyle`; add `cover?` to `nodeMeta`'s `RawNodeMeta`. (The use-case
  `uc.memory.cover.md` + its `status: planned` coverage are already refined; Phase 4 fills the web refs + drops
  `planned`.) · Done when: `npm run check` green; unit tests cover the clamp/normalize, an unseal round-trip,
  `setMemoryCover` preserving a sibling date+location (and remove), and the `coverImageStyle` formula (full-frame vs
  a real crop).
- [x] 2 display — `MemoryCoverImage.vue`: given a decrypted source URL + crop, render the cropped view at any
  container size (`<img>` in `overflow:hidden` via `coverImageStyle`); no src ⇒ a placeholder (default tile, or a
  `placeholder` slot). · Done when: `npm run check` green; tests: a crop yields the expected `<img>` styles; no src
  shows the placeholder.
- [x] 3 picker + crop — `MemoryCoverDialog.vue` (ported): a media picker over the Memory's decrypted media, then a
  16:9 crop viewport with drag-pan + zoom-slider; **Save** emits the normalized `{ mediaId, crop }`, **Remove**
  emits `null`; reopening an existing cover restores its pan/zoom. i18n EN+DE (`memory.cover*`, `memory.zoom`). ·
  Done when: `npm run check` green; tests (stubbed pointer/img): picking a media + saving emits a normalized cover;
  remove emits null; a preset crop round-trips through open→save.
- [x] 4 wire + verify — `MemoryDetail`: a **"Cover festlegen"** header button opens the dialog (source = the
  already-loaded media), shows the cover in the header via `MemoryCoverImage`, and `setMemoryCover` →
  `patchNodeMeta`. `MemoryCard`: show the cover (src+crop from the host) else the placeholder. `MemoryBrowse`:
  resolve each memory's `cover.mediaId`, load that media's thumbnail on device, and pass src+crop to the card **and**
  to `SpaceMap`; the pin applies the crop; **remove `pinCover`**. Close `uc.memory.cover` coverage; add the cover
  refs to `uc.memory.place` (the pin now uses the explicit cover). Live smoke (re-seeded instance, a Memory with
  media): set a cover + crop → the same crop shows on the card, the detail header, and the map pin; remove → the
  placeholder returns. · Done when: `npm run check` + the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | A chosen, cropped cover in `wrappedMeta` (no blob/endpoint), shown on card · detail · map | Status |
|---|---|---|
| backend / contracts | none — the cover rides the opaque node `wrappedMeta` (`updateNode`/`listNodes`) | ✅ n/a (no change) |
| web | `memoryCover.ts` + `MemoryCoverImage.vue` + `MemoryCoverDialog.vue`; wired into detail · card · map | ◻ planned |
| apple / android | native cover picker/crop + display is their own later CR (web-first, ADR 0010) | ◻ **separate (native)** |

## Definition of done

A Memory can be given a cover: pick one of its media, crop it (16:9 pan/zoom), and that single sealed
`cover { mediaId, crop }` — operator-blind, read-modify-write beside the date/location — renders identically on the
browse card, the detail header, and the map pin, via one shared crop formula. No cover ⇒ the placeholder
everywhere; the cr-0071 nearest-media pin stopgap is retired. `npm run check` + the three trust-chain guards green;
the live smoke confirmed.

## Retrospective

Delivered web-only in four phases, each `npm run check`-green. The model (`memoryCover.ts`) mirrors the
`memoryDate`/`memoryLocation` siblings: a normalized `0..1` crop clamped on-image, a read-modify-write
`setMemoryCover` that preserves the sibling date+location, and one pure `coverImageStyle` formula (ported from
the design source's `MemoryCoverImage`) that drives **all three** surfaces. `MemoryCoverImage.vue` +
`MemoryCoverDialog.vue` port the legacy display + hand-rolled 16:9 pan/zoom crop verbatim onto the E2EE data
layer (no crop library, no new dependency). Wiring: a header cover-preview button on `MemoryDetail`, the cover
on `MemoryCard`, and `MemoryBrowse` resolving each node's `cover.mediaId` via the new `loadNodeCover` and passing
`{url,crop}` to both the card and `SpaceMap` (whose pin now applies the same formula). The cr-0071 `pinCover`
nearest-media stopgap + its test were removed; `uc.memory.place` coverage was repointed to the explicit cover.

**Verification.** `npm run check` + all four trust-chain guards green. A gated live e2e
(`memoryCover.e2e.test.ts`, `VEYQ_E2E=1`) proves the operator-blind round-trip through the **real** backend:
create → setDate → setLocation → setCover (each a real `updateNode`) → `listNodes` returns all three sub-objects
intact, and remove drops only the cover. A **browser live smoke** (freshly re-seeded local instance) then
confirmed the visual path end-to-end: uploaded media → set a 2× center crop → the header preview and the browse
card render **byte-identical** `coverImageStyle` output (`width:200%; left:-50%; …`) with date+location chips
intact → **Cover entfernen** returns the placeholder on both. The map pin was not driven through the browser
(needs the maps consent opt-in) but shares the identical helper the card/header proved.

**Gotcha resolved.** The seed-login `401` noted in the cr-0071 session (thought "unresolved") was **seed/DB
drift** — the running DB had been set up by a different seed run than the one that wrote `dev-seed.json`, so even
the Server-Owner login 401'd. A clean `docker compose … down -v && up -d` + **backend restart** (so Flyway
migrates the fresh DB) + `npm run seed:dev` fixes it; browser upload then works when the backend's
`VEYQ_BLOB_STORE_PUBLIC_ENDPOINT` matches the browse origin.

**Deferred (own CRs):** a cover from a **new upload** (standalone cover blob — contract + backend + web, cr-0073,
reusing this CR's display + crop dialog); native cover parity (web-first, ADR 0010); per-surface distinct crops.
Hence `uc.memory.cover` coverage stays `status: planned` until cr-0073 + native land.
