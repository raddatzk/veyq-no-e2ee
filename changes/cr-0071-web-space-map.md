---
id: cr-0071
title: Web space map — a "Karte" view mode plotting the current folder's Memories as pins (place slice 2c)
status: done
satisfies: [uc.memory.place, uc.memory.browse]
adrs: ["0012", "0018"]
invariants: [operator-blind-e2ee]
---

## Goal

Make a space's places **visible at a glance**. Add a third **view mode** next to Raster/Liste — **Karte** — that
renders the current level as a **read-only map**: every Memory/Chapter **in the current folder's subtree**
(recursively; the whole space at the scope root) that carries a sealed `location` with a position is dropped as a
**pin**; a pin shows the node's name + place name and, clicked, **navigates to that Memory**. This is the "tenant
map" slice deferred from [cr-0069](cr-0069-web-set-location-medoid-name.md) (uc.memory.place criterion
`tenant-map-via-listnodes`), scoped **per-folder-subtree** rather than always space-wide (more contextual). Adding
a **third view mode** is also a browse affordance ([uc.memory.browse](../docs/features/memory/use-cases/uc.memory.browse.md)):
the grid/list toggle shipped with [cr-0063](cr-0063-card-grid-list-toggle.md) but was never pinned as an acceptance
criterion — nor was it ever written into the browse use-case **doc** (which still says the level shows "as
cards"). This CR fixes that gap at the source: it updates the `uc.memory.browse` doc (the level is shown as
**grid / list / map** via one app-wide display toggle) **and** adds the matching coverage criterion — homing the
display-mode affordance in its natural use-case rather than inventing a separate "switch view" use-case (a display
toggle is a facet of browsing, not a standalone goal).

Operator-blind by construction ([en.memory.node-meta](../docs/features/memory/enablers/en.memory.node-meta.md)):
**no locations API**. The client already holds every node's opaque `wrappedMeta` from `listNodes` (the loaded
`treeStore`); the map unseals each node's `location` under its own `K_mem` **on device** and plots what it can
open. The blind server never sees a coordinate — a dedicated locations endpoint would hand the operator the
coordinates, so there is none. Web-only (native parity is web-first, ADR 0010).

## Context & constraints

- **Per-folder-subtree scope, not always the whole space.** At the scope root the map shows the whole space; inside
  a folder it shows that folder + everything nested below it (recursively). The `treeStore.nodes` flat list carries
  `parentId`, so the subtree is a walk-up test against the current folder id (null = root ⇒ all nodes).
- **Which nodes get a pin.** Any content node (MEMORY **or** CHAPTER) in the subtree whose sealed `location` has a
  `derived` `{lat,lng}`. A **pending `auto`** location (armed at creation, no media yet) has **no `derived`** →
  **no pin** until its first geotagged upload materializes it. Folders never carry a location.
- **Reuse the seal + key chain, add no crypto.** Each node's `K_mem` is resolved with the existing
  `nodeContentKey(scopeKey, tree, nodeId)` (walk `K_SC` → the recursive `K_mem` chain, ADR 0012), then
  `unsealMemoryLocation(nodeKey, node)`. Best-effort: a node whose key/meta won't open is silently skipped (never a
  broken map). No contract, backend, or DB change.
- **Tiles behind the same personal opt-in (ADR 0018).** The map needs basemap tiles → it is gated by the maps
  `mapsAvailable` consent from [useMapConfig](../web/src/shared/state/useMapConfig.ts) (public OSM default,
  client-direct, never the veyq operator). Not consented ⇒ the Karte view shows the same two-stage opt-in prompt as
  the editor, not a broken map. **No geocoding** here — names are already sealed; the map only reads them.
- **A separate read-only map component.** [MemoryMap](../web/src/features/memory/components/MemoryMap.vue) is
  single-pin + editor-shaped (crosshair, frame events, pan→fixed). The space map is **N read-only clickable pins**,
  a different archetype → a new **`SpaceMap.vue`** that reuses only the `useMapConfig` tile/consent seam (a thin
  Leaflet wrapper: a marker per pin with a name tooltip, `fitBounds` to frame them all, click → `open`).
- **Navigation.** A pin click emits the node up; the host navigates via the existing `#`-fragment path
  (`hashForNode(tree, nodeId)` + `router.push`), the same mechanism a card click uses — so a pin lands on the
  Memory's detail exactly like opening its card.
- **Scope.** Web only. **Deferred (own slices/CRs):** the **group consent state-machine** + tenant language for
  shared names (ADR 0016, groups CR); native map parity (web-first, ADR 0010); an on-device blind gazetteer.

## Phases

- [x] 1 model — new `spaceMap.ts`: `collectLocatedPins(scopeKey, tree, rootFolderId | null) → { node, location }[]`
  (recursive subtree of `rootFolderId`, or the whole tree at root; MEMORY/CHAPTER only; unseal each `location`
  under its `nodeContentKey`; keep those with a `derived` position; best-effort skip on any key/meta failure).
  · Done when: `npm run check` green; unit tests cover a nested tree (a top Memory + a nested chapter, both located,
  both pinned), the subtree filter (a sibling folder's Memory is excluded when rooted at another folder), a
  pending-auto (no `derived` ⇒ no pin), a locked/wrong-key node (skipped), and root (`null` ⇒ all located nodes).
- [x] 2 component — `SpaceMap.vue` (read-only Leaflet, reuses `useMapConfig`): a marker per pin (name tooltip),
  `fitBounds` to frame all pins (single pin ⇒ a sensible zoom), click a pin ⇒ `emit('open', nodeId)`. Gated on
  `mapsAvailable`: not consented ⇒ a "Karten aktivieren" prompt (reusing the two-stage opt-in copy), consented +
  no pins ⇒ a "Noch keine Orte" empty state. i18n EN+DE (`memory.viewMap`, `memory.map.*`). · Done when:
  `npm run check` green; tests (stubbed Leaflet): N pins render + a click emits the right nodeId; the consent-off
  prompt shows and enabling it reveals the map; the empty state shows with zero pins.
- [x] 3 wire the view mode — `useViewMode` gains `'map'`; `ViewModeToggle` a third **Karte** button;
  `MemoryBrowse` renders `SpaceMap` (subtree pins for the current `folder`, or root) instead of the card grids when
  `viewMode === 'map'`, mapping a pin `open` → the existing `openMemory` navigation; the folder header + toggle stay
  so the user sees which subtree is mapped and can switch back. · Done when: `npm run check` green; a MemoryBrowse
  test: in `map` mode the card grids are replaced by the map with the subtree's located pins, and a pin click emits
  `openMemory` with the right node; grid/list modes are unchanged.
- [x] 4 docs + coverage + verify — update **both** use-cases (doc **and** coverage).
  `uc.memory.place`: the `tenant-map-via-listnodes` coverage criterion carries web test refs (now covered), so the
  file drops `status: planned` (all acceptance criteria have refs — the group/gazetteer items remain prose gaps,
  not unref'd criteria). `uc.memory.browse`: **the doc** gains the display-mode affordance (main flow / quality
  notes: the current level shows as **grid / list / map** via one app-wide persisted toggle — grid/list ported
  cr-0063, map this CR), and **the coverage** gains a matching `view-modes` acceptance criterion carrying the
  existing `ViewModeToggle.test` / `MemoryBrowse.test` refs plus this CR's map-mode test (retroactively pinning the
  cr-0063 toggle that shipped undocumented). Live
  smoke (re-seeded instance, a Memory with a set location): switch to **Karte** → the pin shows on the map at the
  sealed coordinate with the sealed name → click → lands on that Memory; plus the consent-off prompt and the empty
  state. · Done when: the three trust-chain guards green; the smoke confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | "Karte" view — subtree Memories as pins from the sealed `location` (no locations API) | Status |
|---|---|---|
| backend / contracts | none — `listNodes` already returns `wrappedMeta`; the map unseals client-side | ✅ n/a (no change) |
| web | `spaceMap.ts` (subtree pins) + `SpaceMap.vue` (read-only Leaflet) + a third `view mode` in the browse | ✅ done |
| apple / android | native place map is their own later CR (web-first, ADR 0010) | ◻ **separate (native)** |

## Definition of done

A space's places are browsable on a map: a **Karte** view mode plots every located Memory/Chapter in the current
folder's subtree as a clickable pin (name + place), from the sealed `location` that `listNodes` already returns,
unsealed on device — operator-blind (no coordinate crosses; no locations endpoint), behind the personal tiles
opt-in, reusing the ADR 0018 seam. Clicking a pin opens that Memory. `npm run check` + the three trust-chain guards
green; the live smoke confirmed. This closes the `tenant-map-via-listnodes` slice of uc.memory.place.

## Retrospective

Landed clean across four slices. **Model** (`spaceMap.ts` · `collectLocatedPins`): a walk-up subtree test over
the flat `treeStore.nodes` + `nodeContentKey` → `unsealMemoryLocation`, keeping only nodes with a `derived`
position. One design correction surfaced in the first test run: `nodeContentKey`/`openContentKey` **throws** on a
wrong key (the AEAD ghash tag), it does not return `null` as its docstring implies — so a single locked node was
aborting the whole collection. The fix (a `try/catch` around the per-node key resolve + unseal) is what makes the
"best-effort, never a broken map" guarantee real; the unit test pins it with a node whose `K_mem` is wrapped under
a foreign key. **Component** (`SpaceMap.vue`): a genuinely different archetype from the editor's `MemoryMap`
(N interactive pins + `fitBounds`, vs one crosshair), so a fresh thin Leaflet wrapper reusing only the
`useMapConfig` tile/consent seam was the right call — no shared-abstraction contortion. Tooltips are built via
`textContent` (not an HTML string) so a user's node/place name can't inject markup. **Wiring**: the one real
judgement was the shared `ViewModeToggle` — it also drives the Chapters-tab grid where a map is meaningless, so the
third button is gated behind a `show-map` prop rather than shown unconditionally; `map` degrades to the card grid
in contexts that don't check for it. `useViewMode`'s stored-value read was hardened to validate against the known
set instead of a bare `=== 'list'`.

The **live smoke** was the most informative step: the seeded instance had *zero* located Memories — "E2E place"
carries only a date (no location), "E2E prearm" is an auto place whose medoid never materialized (`derived`
undefined) — so the map correctly showed the **empty state**, which incidentally proved the pending-auto exclusion
end-to-end. Seeding one fixed coordinate the way the editor's save would (programmatically, then `patchNodeMeta`)
rendered a single pin on real OSM tiles at Köln, the tooltip showing node + place name, and a click landing on the
Memory's detail — then reverted so the seed keeps its pending-auto demo intact. `npm run check` (301 web tests
passing) + the four trust-chain guards green.

Deferred as designed (own CRs, web-first per ADR 0010): native place-map parity; the group-consent state-machine +
tenant language for shared names (ADR 0016); an on-device blind gazetteer. This closes the
`tenant-map-via-listnodes` slice of [uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md) and
retroactively pins the grid/list toggle (cr-0063) that shipped without a browse acceptance criterion.

**Follow-up (same branch, post-review).** The first pass drew one small `pi-map-marker` glyph per node — barely
visible when zoomed out, and nothing grouped nodes that overlapped on screen. Mirroring the legacy media map's
pixel-distance grouping (`TrackMap.vue`, plain Leaflet, no cluster plugin), `spaceMap.ts` gained a pure
`clusterPins(pins, project, distancePx)` (fixed-anchor greedy grouping → centroid, unit-tested), and `SpaceMap.vue`
now renders a bold teardrop pin for a lone node and a round count **badge** for a group. Because a space-map pin is
navigational (a click opens *one* Memory, unlike the legacy lightbox), a badge **spiderfies** on demand — desktop
**hover**, touch **tap** — fanning members onto a ring on thin legs, each a clickable leaf; collapse is delayed
~260 ms so the pointer can travel from the badge to a member without it snapping shut, and a map-background tap
closes it (touch). Re-clustered on every `zoomend/moveend`.

Still not eye-catching enough at a glance (a themed glyph washes out on busy tiles even with a white halo), so —
mirroring the legacy media map once more — a pin now shows the Memory's **cover photo**. New `pinCover.ts`:
`pickCoverMedia` (pure, unit-tested) chooses the derivable media **nearest the pin coordinate** (the photo taken
there), else the first; `loadPinCover` reuses the gallery's operator-blind decrypt path (`listMedia` → `openGrid`
THUMBNAIL tier → object URL). `MemoryBrowse` owns the loads (it holds `scopeKey`/`scopeId`), resolving each
`nodeContentKey` **off** the reactive proxy so K_mem bytes never enter reactivity, and revokes the URLs on unmount;
`SpaceMap` renders a round framed-photo pin and swaps icons **in place** as covers stream in, so a photo appearing
never collapses an open fan.

To pick the pin's shape I rendered a four-variant mockup panel (photo-pin+label, mini info-card, polaroid, quiet
pin-with-hover-card). The user first tried the "quiet" one, then settled on the **mini info-card (variant B): the
pin *is* the card** — cover (or the memory-card placeholder, since no real covers exist yet), **title**, and the
sealed **date range** (reusing the card's `formatClosedRange`/`memory.date.since`), always visible, anchored on a
tail. Density stays sane because the clustering still collapses close pins to a badge; the card is a zero-size
Leaflet wrapper with the visible card lifted + centred by CSS, so its width grows with the title without breaking
the anchor. A spiderfied cluster's fan is **ordered by start day, newest-first** (same convention as the card grid)
via a pure `sortPinsByStartDate`, with a wider fan radius so the cards don't stack; `collectLocatedPins` now unseals
each node's `date` alongside its `location` (same K_mem, same blob) so the card and the ordering are free.
`npm run check` (311 web tests: +4 clusterPins, +4 pinCover, +2 sortPinsByStartDate) green.

Live re-capture of these visuals was **not** possible from my side: forcing the secret-key login form invalidated
the restored dev preview session and a fresh seed login returns 401 (unresolved) — the cluster/cover/date-order math
is unit-covered and the render pipeline was proven in the prior live smoke, but the pin/card/spiderfy visuals were
verified by the user on their own `veyq.localhost` instance via HMR rather than captured here.
