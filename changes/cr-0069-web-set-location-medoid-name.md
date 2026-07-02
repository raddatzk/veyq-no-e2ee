---
id: cr-0069
title: Web set-location — editor map, medoid pin, pan/zoom→geocoded name, fixed⟷auto, pre-arm at creation (place slice 2b)
status: done
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
(`auto`, re-medoids as new media arrive) — reversibly, and removable. The same choice is offered **up front at
Memory creation** (none / manual-fix / auto + a name). The result — one sealed `location` — lands in the node's
existing `wrappedMeta` blob beside the date, operator-blind. Web-only.

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
  the date editor's shape. Basemap tiles come from a provider-neutral, **third-party** tile seam
  ([ADR 0018](../docs/decisions/0018-map-tiles-provider-neutrality.md)) rendered by **Leaflet** (BSD-2, actively
  maintained, vendor-neutral — a new web dep; no deprecated/locked map client).
- **Location is a deliberate per-Memory choice — "does this Memory happen at *one* place?"** Not every Memory
  has one, and a location is **never auto-created from GPS presence**: a broad "Climbing" Memory spread across
  many gyms (its sessions are chapters, each possibly with its **own** location) sensibly has **no** location,
  even though its media carry GPS. The user opts the Memory in ("set location") or leaves it out (the default).
- **When present, a location is always a position + a name together — never one without the other; no
  "name-only".**
  - **Position** comes from either the **medoid** of the media GPS (min summed **Haversine** distance — never the
    mean, robust to an outlier; `auto`) **or** a **manually-dropped pin** (`fixed`). So an opted-in Memory with
    **no photo GPS** can still get a location — the user places the pin by hand; there is no name-only substitute.
  - **Name** is either **reverse-geocoded** from the framed `nameAnchor` (`nameSource: geocoded`, editable) **or**
    **typed** (`nameSource: manual`) — but **always** alongside the position (the geocoding-off case = a manual
    name on an active location, *not* a name without a position).
  - Set-location needs a **map (tiles)** to place/show the position; **without tiles it is unavailable** (a
    location can't exist without a position, and the position is set on the map).
- **`fixed` ⟷ `auto` is a user choice, not a forced default — and fully reversible.** `derivedMode` is a field in
  the sealed `location` blob, written by `setMemoryLocation`. When the media carry GPS, `auto` is
  **pre-selected** (the pin sits on the computed medoid); with no media GPS there's nothing to medoid, so the user
  drops a pin → `fixed`. The user may keep `auto`, or go to **`fixed`** via an
  **explicit toggle** ("keep current" ↔ "freeze here") **or** by **dragging the pin** (which sets `fixed`). The
  toggle is **bidirectional** — a `fixed` location can be switched **back to `auto`** anytime (it drops the frozen
  coordinate and re-medoids). An `auto` location **re-medoids** `derived` as media are added — the self-heal runs
  in `MemoryDetail` on open + after an upload, mirroring the date's `reconcileAuto`; a `fixed` one is frozen. A
  `manual` name is frozen; a `geocoded` name may refresh when its `nameAnchor` moves.
- **Remove the location entirely.** The editor can **clear** a Memory's location (back to no place) — a `null`
  through `setMemoryLocation` that drops the `location` sub-object while **preserving the date** in the same blob.
  (Clearing the mandatory name is the same as removing the location.)
- **Tiles + geocoding are ONE two-stage consent, third-party-only (ADR 0016 / 0018).** Both go **client-direct**
  to a **third party** (public OSM tiles / Nominatim), **never through the veyq backend and never an
  operator-run instance** (an operator-run one would see the viewport/coordinate → not blind), **identity-free**
  (bare `{z,x,y}` / bare coordinate). The consent is **off** ⇒ no map ⇒ no location feature; **on** ⇒ tiles +
  reverse-geocoding (`reverse(lat,lng,zoom,lang)→name`; `zoom` = framed zoom → name granularity; a name may still
  be typed manually). **For this slice (personal space) it is simply the user's own two-stage toggle**; the group
  model (owner soft-enable, per-member notice/consent, unanimity-binds, leave-to-withdraw) and the **tenant
  language** for shared names are **deferred to the groups CR** (ADR 0016). `lang` in a personal space = the
  user's locale.
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
- **Two entry points (both in scope).** (a) **Set location on an open Memory** — the map editor with the medoid
  pre-selected. (b) **Pre-arm at Memory creation** — in the create dialog the user chooses **none / manual-fix a
  pin / auto**, plus a **mandatory name**. At creation there is **no media yet**, so `auto` carries **no `derived`
  until the first geotagged media** re-medoids it (the `MemoryDetail` self-heal), while **manual-fix** drops a pin
  now (`fixed`). Both reuse the same model + editor + `setMemoryLocation`.
- **Scope.** Web only. **Deferred (own slices/CRs):** the **tenant map** (all Memories as pins across the tenant
  root — a *different*, read-only map, reusing this tile seam); the **group consent state-machine** (owner
  soft-enable, per-member notice/consent, unanimity-binds, leave-to-withdraw) + the **tenant language** for
  shared resolved names (ADR 0016); native parity (web-first, ADR 0010); an
  **on-device coarse gazetteer** (fully-blind names, ADR 0016).

## Phases

- [x] 1 web model — refactor the node-meta seal to **read-modify-write** (shared `unsealNodeMeta`/`sealNodeMeta`
  over `{v, date?, location?}`; `setMemoryDate` preserves an existing `location`). New `memoryLocation.ts`:
  `MemoryLocation` type; `unsealMemoryLocation`; `setMemoryLocation(scopeId, nodeId, nodeKey, location | null)`
  (preserves the date); pure `computeMedoid(points) → {lat,lng} | null` (**min summed Haversine**, tie→lowest
  index, null on empty). New `geocode.ts`: the `reverse(lat,lng,zoom,lang) → name` seam (ADR 0016), client-direct,
  off-until-configured. · Done when: `npm run check` green; unit tests cover the medoid (3-point set, sea-outlier
  the mean would pull but the medoid resists, single, empty→null), the **read-modify-write** (set location keeps
  the date; set date keeps the location; **removing the location** via `setMemoryLocation(null)` keeps the date),
  `derivedMode` `auto` vs `fixed` **and back** (`fixed`→`auto` drops the frozen coord + re-medoids), and the
  reverse seam (absent endpoint ⇒ no request, manual name stands).
- [x] 2 web UI — add **Leaflet**; a `MemoryMap`/tile-layer wrapper honoring the ADR 0018 tile seam (configurable
  URL, attribution, third-party public source; behind the personal two-stage opt-in). A
  **`MemoryLocationEditor.vue`**: map centred on the medoid pin;
  pan/zoom updates `nameAnchor` → (opt-in) reverse-geocode → fills the editable name; when geocoding is off the
  name is **typed manually on the map** (pin still from the medoid); a **bidirectional `auto`⟷`fixed` toggle**
  (pin-drag ⇒ `fixed`; switching back to `auto` re-medoids); a **remove-location** action (clears it, keeps the
  date); **save requires both a position and a name** (no name-only). Set-location is offered whenever **tiles**
  exist — the position is the medoid (media GPS) or a hand-dropped pin. A **location chip** (pin icon) in the
  `MemoryDetail` header shows the place **when the Memory has one**, else a "set location" affordance (when tiles
  are available). Wire `locationEditorOpen` + `onLocationSave` →
  `setMemoryLocation` → `emit('metaUpdated', { nodeId, wrappedMeta })`; the medoid is computed at open from
  `items`. i18n EN+DE (`memory.place.*`, transparency notes for tiles + geocoding). · Done when: `npm run check`
  green; tests: set a `geocoded` location (medoid from two geotagged items + a stubbed reverse lookup) shows the
  chip and the emitted `wrappedMeta` still unseals the **date**; a pin drag flips to `fixed` and the toggle
  switches back to `auto`; **removing** the location clears the chip while the date survives; geocoding-off falls
  back to a typed name on the map.
- [x] 3 web pre-arm at creation — the Memory create dialog (`NodeMutationDialog` / `createMemory`) gains an
  optional **location step**: **none** (default) / **manual-fix** (drop a pin on the map → `fixed`) / **auto**
  (no `derived` yet — re-medoids on the first geotagged media), each with a **mandatory name**. Reuses the phase-2
  editor/model; on create, the sealed `location` rides the same node `wrappedMeta` the date already uses. ·
  Done when: `npm run check` green; a test creates a Memory with (i) a manual-fixed pin + name → `fixed` location
  sealed at birth, and (ii) `auto` + name → a named `auto` location with no `derived`, which gains a medoid after
  an upload (the self-heal); no-location creation is unchanged.
- [x] 4 coverage + verify — update `docs/coverage/features/memory/use-cases/uc.memory.place.yaml`: the
  `deliberate-set-location`, `medoid-position`, `name-manual-or-reverse-geocoded`, and
  `reverse-only-veyq-blind-geocoding` criteria carry web test refs; the `tenant-map-via-listnodes` criterion stays
  gapped (its own slice). Live smoke (re-seeded instance, a configured Nominatim + OSM tiles): a Memory with two
  geotagged photos → "set location" → the map centres on the medoid → pan → a geocoded name appears → save →
  `veyq_node.wrapped_meta` seals the `location` (**DB opaque, zero plaintext** name/coord, **date preserved**);
  plus a geocoding-off run (typed name on the map), a **remove** (place gone, date intact), and a **create-time**
  pre-arm (a new Memory born with an `auto` + name that medoids after an upload). · Done when: the three
  trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Editor map + medoid + pan/zoom→geocoded name + fixed/auto, sealed in node `wrappedMeta` | Status |
|---|---|---|
| backend / contracts | none — `wrappedMeta` already threads `location` verbatim via `updateNode` | ✅ n/a (no change) |
| web | node-meta read-modify-write; `memoryLocation.ts` + medoid; Leaflet map + tile/geocode seams; editor + chip + create-dialog pre-arm | ◻ planned |
| apple / android | native place map/UI is their own later CR (web-first, ADR 0010) | ◻ **separate (native)** |

## Definition of done

A Memory carries one sealed named `location` set through a map: the medoid of its photos' GPS as the pin, a
name reverse-geocoded from the framed anchor (editable; a typed manual name on the map when geocoding is off),
and a reversible, removable fixed⟷auto
choice — offered both on an open Memory and up front at creation, sealed in the same node `wrappedMeta` as the
date, operator-blind (the server holds only opaque ciphertext; the date is never clobbered; tiles + geocoding are
consented, identity-free, client-direct third-party boundaries per ADR 0018 / 0016). `npm run check` + the three
trust-chain guards green; the live smoke confirmed. The tenant map is a separate later slice.

## Retrospective

**Outcome.** All four phases landed; `npm run check` green (280 unit/component tests + typecheck + build), the
three trust-chain guards green, and the live smoke confirmed end-to-end against a re-seeded instance + real OSM
tiles + real Nominatim.

**What went well.**
- The **read-modify-write refactor first** (a shared `nodeMeta.ts` envelope) was the right central move — once
  `setMemoryDate`/`setMemoryLocation` both merged into `{v, date?, location?}`, date-preservation fell out for
  free and was pinned by a cross-preservation test in both directions. The CR flagged this "destructive seal" as
  the central risk and tackling it in phase 1 paid off.
- **Stubbing Leaflet at the component seam** (a `MemoryMap` wrapper the editor drives via events) kept the whole
  editor unit-testable in happy-dom; the real Leaflet render was then confirmed once, live, in the browser smoke.
- The **live e2e mirrored `media.e2e.test.ts`** (seed → login → unlock → scope), so the operator-blind seal +
  RMW + remove + create-pre-arm + real geocode all ran as one gated test, and a MariaDB grep proved zero
  plaintext at rest.

**Misses / root causes.**
- **"Two-stage consent" was ambiguous** in the ADRs — it reads like it could be two independent toggles, but
  ADR 0018 forbids a tiles-on/geocoding-off middle stage. Resolved to one coupled boolean; asked the user only
  the genuinely-open question (endpoint defaults). Next time: pin coupled-vs-independent explicitly in the ADR.
- **`nameSource` initial value bug** — a fresh location defaulted to `manual`, which silently blocked the
  onMounted geocode; caught immediately by the editor test (name stayed empty). Root cause: conflating "no
  source yet" with "user typed". Fix: seed `geocoded` when geocoding is available, flip to `manual` on real input.
- **Browser-automation friction** — a plain `preview_click` on the submit button didn't fire the Vue form
  submit; `form.requestSubmit()` did. Cost a couple of round-trips. Next time: drive PrimeVue forms via
  `requestSubmit` from the start, and remember the seed tenant only resolves on the `127.0.0.1` Host.
- **Nominatim 403 on the node default UA** — a real usage-policy artifact, not a bug (the browser supplies its
  own UA; the seam correctly returned null and the manual name would stand). Injected a descriptive UA via the
  seam's `fetchImpl` for the node smoke.

**Next time (one concrete).** When a CR introduces a coupled consent/opt-in, write the composable + its unit
test in the same step as the ADR reading — the `mapsAvailable`/`geocodingAvailable` split only became crisp once
the test forced me to state exactly which knob gates which capability.

**Post-review redesign (same branch).** First-use feedback on the create dialog reframed the interaction, and it
was the right call — closer to ADR 0016's "pan/zoom → geocoded name" than the shipped click-to-drop-pin:
- **Position is framed, not pinned.** A `fixed` place is the map's **centre** (a crosshair); panning/zooming
  moves it — no dropped/dragged marker. An `auto` place shows the medoid pin. (`MemoryMap` lost its drag/click
  emits; gained a `crosshair` + a static `marker`.)
- **Name is auto-geocoded by default, with an explicit "name it myself" checkbox** (replacing the implicit
  type-to-go-manual), so the source is a visible toggle; geocoding-off forces manual.
- **`auto` without media is now truly deferred (pending).** It arms with **no name/position**; the first
  geotagged upload materializes both — the self-heal re-medoids *and* reverse-geocodes the name (ADR 0016 standing
  consent). The chip shows a "location pending…" placeholder meanwhile. This relaxed the enabler's "name
  mandatory" to "mandatory except a pending auto place" (doc updated). Model: `normalizeLocation` keeps a nameless
  `auto`+`geocoded` blob; `isLocationPending` drives the chip.
- **Root cause of the original miss:** I read "a manually-dropped pin (fixed)" in the CR literally as a click
  interaction, when the ADR's framing model was the better read all along. Lesson: when the ADR and a CR sentence
  seem to describe the same gesture differently, prototype the ADR's version first.

**Consolidation (same branch).** More first-use feedback: the create dialog had its **own** location UI (a
none/auto/fixed `SelectButton` + a duplicated map/geocode/name implementation) that diverged from the editor's
(a two-way auto/fixed toggle + a separate remove button). Collapsed to **one** control in **one** component: the
`MemoryLocationEditor` now owns the **3-way `SelectButton` (Kein Ort / Auto / Fest)** — `Kein Ort` folds in the
old remove button (Save with it clears the place) — and the create dialog reuses that same editor, opened as a
**second dialog** via an "Ort hinzufügen" affordance (a summary row with change/remove). All the duplicated
framing/geocode logic is gone. To make the editor context-free, it now supports `auto` **without a medoid** (a
pending place — no map, deferred name) and defaults to `auto`. Two iterations of feedback here (button-vs-tabs,
inline-vs-second-dialog) — the lesson: for a control the user will actually touch, mock the exact affordance and
confirm it before wiring, rather than inferring it from the prose. Build the shared component once and reuse it
from both entry points; the inlined "simpler" copy always drifts.

**Intent-first redesign (same branch, co-designed via mockups).** The tabbed editor still surfaced too much
mechanism. Reworked to hide it:
- **"Karte verschieben" (map-pan), not a dropped pin.** A fixed centre crosshair *is* the position (like
  Uber/Maps "set location"); the map opens on the medoid with faint **photo dots** + a "Folgt deinen Fotos" pill.
  Moving the map by hand takes over → `fixed`, with a one-tap "wieder folgen". No mode tabs, no drag target.
- **Name = the reverse-geocoded address *hierarchy*, not a single string.** The user's insight — position and
  *granularity* are two levers — became: move the map for *where*, pick the **level** (road…country) for *how
  specific*. The seam changed from `reverse(…zoom…)→name` to `reverse(…lang)→level[]`; `nameAnchor.zoom` became
  `nameAnchor.level` so an auto refresh keeps the chosen grain. ADR 0016 amended with a dated revision.
- **Create is now one line** ("Ort — folgt automatisch deinen Fotos" → a pending auto place); the heavy map/name
  live only on the open Memory, where photos give context. No second dialog, no name field, no privacy paragraph
  (a small ⓘ tooltip instead).
- **Process win:** comparing directions with inline **mockups before coding** cut the churn — the user picked
  "map-pan + level-picker" from a picture in one round, versus the several prose round-trips the earlier
  tabs/dialog decisions took. For a control the user will touch, mock the affordance first.
