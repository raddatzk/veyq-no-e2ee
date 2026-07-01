---
id: cr-0069
title: Web set-location — one sealed named place (medoid + manual name, blind) (place slice 2b)
status: planned
satisfies: [uc.memory.place]
adrs: ["0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Give a Memory a **place**: one sealed `location` in the node's existing `wrappedMeta` blob — a **map position**
derived as the **medoid** of the Memory's own photo GPS, plus a **manually-typed name**
([uc.memory.place](../docs/features/memory/use-cases/uc.memory.place.md)). Web-only, deliberately **minimal**:
**no map** and **no geocoding** yet (slices 3 + 4). The GPS source is the always-listed media `wrappedMeta` tier
that landed in [cr-0068](cr-0068-media-wrapped-meta-merge-gps.md) — the medoid is computed client-side over the
already-listed media, no per-item fetch, operator-blind.

The sealed shape (canonical in [en.memory.node-meta](../docs/features/memory/enablers/en.memory.node-meta.md)):

```
location: { name, nameSource: 'manual', derived?: { lat, lng }, derivedMode?: 'auto' }
```

Slice 2b produces **`nameSource: 'manual'`** and **`derivedMode: 'auto'`** only — a typed name plus the computed
medoid. `nameAnchor` + `geocoded` names come with reverse-geocoding (slice 4); moving the pin to `fixed` needs a
map (slice 3).

## Context & constraints

- **The central risk: the node-meta seal is destructive.** `memoryDate.ts`'s `sealMemoryDate` seals a **fresh**
  `{v, date}` and PATCHes the whole `wrappedMeta` — it does **not** preserve siblings. Adding a location the same
  way would **wipe the date** (and vice-versa). So this CR first **refactors the node-meta seal to
  read-modify-write**: unseal the current `{v, date?, location?}`, merge the changed sub-object, re-seal — used by
  both `setMemoryDate` and the new `setMemoryLocation`. This is the regression to guard hardest (a location save
  must leave the date intact, proven by a test).
- **Name is mandatory; no empty/unnamed place.** A location exists only once the user gives it a **name**
  (`en.memory.node-meta`: "no name ⇒ no location"). Clearing the name clears the location.
- **Medoid, not mean.** `derived` is the **medoid** of the media GPS — the actual photo point **minimizing the
  summed Haversine distance** to the others (robust to an outlier, never the sea, never the arithmetic mean).
  Computed client-side from `unsealMeta(memoryKey, item).gpsLat/gpsLng` over the listed media (already in
  `MemoryDetail`'s `items`). No GPS at all ⇒ a valid **name-only** location (shown in the header, later not on the
  map — E3).
- **`derivedMode: 'auto'`** — re-medoids as media are added (barely moves). A manual pin move → `fixed` is
  deferred (needs the map, slice 3), so 2b never writes `fixed`.
- **Deliberate, never silent.** A location is created only by an explicit **"set location"** act on an open
  Memory. (Pre-arm at Memory creation — the use-case's second entry point — is **deferred** to a follow-up; 2b is
  the open-Memory flow.)
- **Operator-blind (ADR 0012).** The whole `location` is sealed under the node's `K_mem` (AAD context `node-meta`,
  same as the date) and PATCHed as opaque base64 via the existing `updateNode`. **No contract, backend, or DB
  change** — `wrappedMeta` already threads verbatim (verified: `content.openapi.yaml` `UpdateNodeRequest`). No
  geocoding request leaves the device in 2b (that's slice 4's consented boundary).
- **Naming.** `web/src/features/memory/location.ts` is **already taken** (URL-fragment navigation, cr-0031) — the
  place model lives in a **new** `memoryLocation.ts`, mirroring `memoryDate.ts`.
- **Scope.** Web only. **Deferred (own CRs/slices):** the tenant **map** (slice 3, `derivedMode: fixed` pin move
  + Leaflet + tile seam), **reverse-geocoding** (slice 4, `nameSource: geocoded` + `nameAnchor` + ADR 0016),
  **pre-arm at creation**, and native parity (web-first, ADR 0010).

## Phases

- [ ] 1 web model — refactor the node-meta seal to **read-modify-write** (a shared `unsealNodeMeta`/`sealNodeMeta`
  over the full `{v, date?, location?}` blob; `setMemoryDate` now preserves an existing `location`). New
  `web/src/features/memory/memoryLocation.ts`: `MemoryLocation` type; `unsealMemoryLocation(nodeKey, node)`;
  `setMemoryLocation(scopeId, nodeId, nodeKey, location | null)` (preserves the date, PATCHes via `updateNode`);
  a pure `computeMedoid(points): {lat,lng} | null` (**min summed Haversine**, tie → lowest index; null on empty).
  · Done when: `npm run check` green; unit tests cover medoid (a 3-point set, an outlier that the mean would pull
  to the sea but the medoid resists, single point, empty→null), the read-modify-write (**set location keeps the
  date; set date keeps the location**; clearing name ⇒ no location), and a name-only location (no GPS).
- [ ] 2 web UI — a minimal **`MemoryLocationEditor.vue`** (name input — mandatory; a read-only derived-coordinate
  line when GPS exists, "name only, no map position" note when it doesn't; save/clear) mirroring
  `MemoryDateEditor.vue`; a **location chip** (pin icon) in the `MemoryDetail` header showing the name (or a
  "set location" affordance when none); wire `locationEditorOpen` + `onLocationSave` → `setMemoryLocation` →
  `emit('metaUpdated', { nodeId, wrappedMeta })`; the medoid is computed at editor-open from `items`. i18n EN+DE
  (`memory.place.*`). · Done when: `npm run check` green; a MemoryDetail/editor test sets a location (medoid from
  two geotagged items + a typed name), shows the chip, and asserts the emitted `wrappedMeta` still unseals the
  **date** (no regression); a name-only path (no GPS) sets a name and shows the chip.
- [ ] 3 coverage + verify — update `docs/coverage/features/memory/use-cases/uc.memory.place.yaml`: the
  `deliberate-set-location`, `medoid-position`, and `name-manual-or-reverse-geocoded` (manual half) criteria now
  carry web test refs; the tenant map + reverse-geocoding stay gapped (slices 3/4). Live smoke (re-seeded
  instance): open a Memory with two geotagged photos → "set location" → typed name + a derived medoid pin →
  the node's `wrappedMeta` seals the `location` (DB `veyq_node.wrapped_meta` opaque, **zero plaintext** name or
  coordinate, **date still present** in the same blob). · Done when: the three trust-chain guards green; the smoke
  confirmed.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | One sealed named `location` (medoid + manual name) in the node `wrappedMeta` | Status |
|---|---|---|
| backend / contracts | none — `wrappedMeta` already threads `location` verbatim via `updateNode` | ✅ n/a (no change) |
| web | node-meta read-modify-write; `memoryLocation.ts` (medoid + manual name); editor + header chip | ◻ planned |
| apple / android | native place UI is their own later CR (web-first, ADR 0010) | ◻ **separate (native)** |

## Definition of done

A Memory carries one sealed named `location` — a manually-typed name plus the **medoid** of its photos' GPS —
in the same node `wrappedMeta` blob as its date, set by a deliberate "set location" act, operator-blind (the
server holds only opaque ciphertext; the date is never clobbered by a location save). `npm run check` + the three
trust-chain guards green; the live smoke confirmed (sealed location in `veyq_node.wrapped_meta`, zero plaintext,
date preserved). No map, no geocoding (slices 3/4).

## Retrospective

_(to be written at close — planning + execution + process/collaboration misses, both directions, root cause, one
concrete "next time".)_
