---
id: cr-0069
title: Web set-location — editor map, medoid pin, pan/zoom→geocoded name, fixed/auto (place slice 2b)
status: planned
satisfies: [uc.memory.place]
adrs: ["0012", "0016", "0018"]
invariants: [operator-blind-e2ee]
---

## Goal

Give a Memory a **place** through the real "set location" experience
([uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md)): an **editor map** opens on the
**medoid** of the Memory's own photo GPS; the user **pans/zooms** to frame the spot, and the framed centre + zoom
(the `nameAnchor`) drive a **reverse-geocoded name** ([ADR 0016](../docs/decisions/0016-geocoding-provider-neutrality.md));
the name stays editable, and the user chooses to **freeze** the position (`fixed`) or let it **auto-update**
(`auto`, re-medoids as new media arrive). The result — one sealed `location` — lands in the node's existing
`wrappedMeta` blob beside the date, operator-blind. Web-only.

Sealed shape (canonical in [en.memory.node-meta](../docs/features/memory/enablers/en.memory.node-meta.md)):

```
location: { name, nameSource: 'geocoded' | 'manual', derived?: { lat, lng },
            derivedMode: 'auto' | 'fixed', nameAnchor?: { lat, lng, zoom } }
```

The GPS the medoid reads is the always-listed media `wrappedMeta` tier from
[cr-0068](cr-0068-media-wrapped-meta-merge-gps.md) — computed client-side over the already-listed media, no
per-item fetch.

## Context & constraints

- **The map is the UI — not a modal (correcting an earlier mis-scope).** Set-location is a slippy-map
  interaction: a basemap with the medoid pin, pan/zoom to reframe, a name that follows the frame. It is **not**
  the date editor's shape. Basemap tiles come from a provider-neutral, self-host-or-opt-in-public tile seam
  ([ADR 0018](../docs/decisions/0018-map-tiles-provider-neutrality.md)) rendered by **Leaflet** (BSD-2, actively
  maintained, vendor-neutral — a new web dep; no deprecated/locked map client).
- **Set-location goes through the map — no name-only-without-map path in this slice.** A location is placed on
  the map from the medoid; the **medoid** gives the coordinate (min summed **Haversine** distance over the media
  GPS — never the mean, robust to an outlier). If a Memory has **no photo GPS** (no medoid) or **no tiles**
  configured/consented, "set location" is **not offered yet** (deferred) — we do **not** fall back to a bare
  name-only field for now. The **name** comes from reverse-geocoding the framed `nameAnchor` (`geocoded`,
  editable) **or**, when geocoding is off/unreachable, a **typed string** (`manual`) **still entered on the map**
  (the pin/medoid works without geocoding). A **name is mandatory** — no unnamed place.
- **`fixed` vs `auto` — where the default lives.** `derivedMode` is a field in the sealed `location` blob,
  written by `setMemoryLocation`. Default **`auto`** (the pin sits on the computed medoid, untouched). It becomes
  **`fixed`** either by an **explicit editor toggle** ("keep this position current" ↔ "freeze here") **or** by
  **dragging the pin by hand** (which sets the toggle to `fixed` — the deliberate correction wins). An `auto`
  location **re-medoids** `derived` as media are added — the self-heal runs in `MemoryDetail` on open + after an
  upload, mirroring the date's `reconcileAuto`; a `fixed` one is frozen. A `manual` name is frozen; a `geocoded`
  name may refresh when its `nameAnchor` moves.
- **Reverse-geocoding per ADR 0016** — the seam is `reverse(lat, lng, zoom, lang) → name`, **client-direct** to
  the provider (never through the veyq backend), **identity-free** (a bare coordinate), **opt-in** (off until an
  endpoint is configured + the user consents), reference **Nominatim**. `zoom` = the map's framed zoom →
  name granularity; `lang` = the UI locale. Opt-in is standing consent; manual names need no request.
- **Tiles per ADR 0018** — a configurable `{z}/{x}/{y}` tile-URL seam, **client-direct**, identity-free (bare
  viewport), attribution shown; **self-host = blind**, **public OSM = opt-in** (same consented boundary as
  geocoding). No tiles configured/consented ⇒ no map surface ⇒ **set-location is not offered** in this slice (no
  bare name-only fallback for now).
- **The central risk: the node-meta seal is destructive.** `memoryDate.ts`'s `sealMemoryDate` seals a **fresh**
  `{v, date}` and PATCHes the whole `wrappedMeta` — it does not preserve siblings. Writing a `location` the same
  way would **wipe the date**. So this CR first **refactors the node-meta seal to read-modify-write** (unseal the
  current `{v, date?, location?}`, merge the changed sub-object, re-seal) — shared by `setMemoryDate` and the new
  `setMemoryLocation`. Guard it with a test: a location save must leave the date intact, and vice-versa.
- **Operator-blind (ADR 0012).** The whole `location` (name + coordinate + anchor) is sealed under the node's
  `K_mem` (AAD `node-meta`) and PATCHed as opaque base64 via the existing `updateNode`. **No contract, backend, or
  DB change** — `wrappedMeta` already threads verbatim. The only things leaving the device are the consented,
  identity-free tile + geocode requests (ADR 0018 / 0016), never to the veyq operator.
- **Naming.** `web/src/features/memory/location.ts` is already taken (URL-fragment nav, cr-0031) — the place
  model lives in a **new** `memoryLocation.ts`.
- **Scope.** Web only, one open-Memory "set location" flow. **Deferred (own slices/CRs):** the **tenant map** (all
  Memories as pins across the tenant root — a *different*, read-only map, reusing this tile seam); **pre-arm at
  Memory creation**; native parity (web-first, ADR 0010); an **on-device coarse gazetteer** (fully-blind names,
  ADR 0016).

## Phases

- [ ] 1 web model — refactor the node-meta seal to **read-modify-write** (shared `unsealNodeMeta`/`sealNodeMeta`
  over `{v, date?, location?}`; `setMemoryDate` preserves an existing `location`). New `memoryLocation.ts`:
  `MemoryLocation` type; `unsealMemoryLocation`; `setMemoryLocation(scopeId, nodeId, nodeKey, location | null)`
  (preserves the date); pure `computeMedoid(points) → {lat,lng} | null` (**min summed Haversine**, tie→lowest
  index, null on empty). New `geocode.ts`: the `reverse(lat,lng,zoom,lang) → name` seam (ADR 0016), client-direct,
  off-until-configured. · Done when: `npm run check` green; unit tests cover the medoid (3-point set, sea-outlier
  the mean would pull but the medoid resists, single, empty→null), the **read-modify-write** (set location keeps
  the date; set date keeps the location; clearing the name ⇒ no location), `derivedMode` default `auto` vs an
  explicit/pin-move `fixed`, and the reverse seam (absent endpoint ⇒ no request, manual name stands).
- [ ] 2 web UI — add **Leaflet**; a `MemoryMap`/tile-layer wrapper honoring the ADR 0018 tile seam (configurable
  URL, attribution, self-host vs opt-in public). A **`MemoryLocationEditor.vue`**: map centred on the medoid pin;
  pan/zoom updates `nameAnchor` → (opt-in) reverse-geocode → fills the editable name; when geocoding is off the
  name is **typed manually on the map** (pin still from the medoid); an explicit **`auto`/`fixed` toggle** (and a
  pin-drag ⇒ `fixed`); save requires a name. "Set location" is only offered when the Memory has a medoid **and**
  tiles (no name-only-without-map path this slice). A **location chip** (pin icon) in the `MemoryDetail` header
  showing the name (or a "set location" affordance when eligible). Wire `locationEditorOpen` + `onLocationSave` →
  `setMemoryLocation` → `emit('metaUpdated', { nodeId, wrappedMeta })`; the medoid is computed at open from
  `items`. i18n EN+DE (`memory.place.*`, transparency notes for tiles + geocoding). · Done when: `npm run check`
  green; tests: set a `geocoded` location (medoid from two geotagged items + a stubbed reverse lookup) shows the
  chip and the emitted `wrappedMeta` still unseals the **date**; a pin drag flips to `fixed`; the explicit toggle
  sets `fixed`/`auto`; geocoding-off falls back to a typed name on the map.
- [ ] 3 coverage + verify — update `docs/coverage/features/memory/use-cases/uc.memory.place.yaml`: the
  `deliberate-set-location`, `medoid-position`, `name-manual-or-reverse-geocoded`, and
  `reverse-only-veyq-blind-geocoding` criteria carry web test refs; the `tenant-map-via-listnodes` criterion stays
  gapped (its own slice). Live smoke (re-seeded instance, a configured Nominatim + OSM tiles): a Memory with two
  geotagged photos → "set location" → the map centres on the medoid → pan → a geocoded name appears → save →
  `veyq_node.wrapped_meta` seals the `location` (**DB opaque, zero plaintext** name/coord, **date preserved**);
  plus a geocoding-off run (typed name on the map). · Done when: the three trust-chain guards green; the smoke
  confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Editor map + medoid + pan/zoom→geocoded name + fixed/auto, sealed in node `wrappedMeta` | Status |
|---|---|---|
| backend / contracts | none — `wrappedMeta` already threads `location` verbatim via `updateNode` | ✅ n/a (no change) |
| web | node-meta read-modify-write; `memoryLocation.ts` + medoid; Leaflet map + tile/geocode seams; editor + chip | ◻ planned |
| apple / android | native place map/UI is their own later CR (web-first, ADR 0010) | ◻ **separate (native)** |

## Definition of done

A Memory carries one sealed named `location` set through a map: the medoid of its photos' GPS as the pin, a
name reverse-geocoded from the framed anchor (editable; a typed manual name on the map when geocoding is off),
and a fixed/auto choice —
sealed in the same node `wrappedMeta` as the date, operator-blind (the server holds only opaque ciphertext; the
date is never clobbered; tiles + geocoding are consented, identity-free, client-direct third-party boundaries per
ADR 0018 / 0016). `npm run check` + the three trust-chain guards green; the live smoke confirmed. The tenant map
is a separate later slice.

## Retrospective

_(to be written at close — planning + execution + process/collaboration misses, both directions, root cause, one
concrete "next time".)_
