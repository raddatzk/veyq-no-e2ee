# ADR 0018 — Map tiles: raster via Leaflet, provider-neutral, veyq-blind (OSM reference)

- Status: Accepted
- Date: 2026-07-02

## Context

The **set-location** editor ([uc.memory.place](../features/memory/use-cases/uc.memory.place.md)) shows a slippy
map with the Memory's medoid pin; the user pans/zooms to frame the place, and the framed centre + zoom drive the
reverse-geocoded name ([ADR 0016](0016-geocoding-provider-neutrality.md)). A later **tenant map** plots every
Memory as a pin. Both need **map tiles** — the imagery under the pins — and those tiles come from a **tile
server**, the second part of the location story (besides geocoding) that reaches outside the device. The tensions
mirror ADR 0016:

- **Operator-blindness is about the *veyq* operator.** A tile request reveals the **viewport** (roughly where the
  user is looking) + the client IP to the **tile server** — a *different*, non-veyq trust boundary. The hard
  invariant is that the **veyq operator** stays blind (tiles carry no user content, no Memory context, no
  coordinate the operator could read); a client-direct request to a tile server is acceptable.
- **Raster or vector?** **Raster** (`{z}/{x}/{y}.png`) with **Leaflet** is the simplest to render and to
  self-host (a plain tile directory or a standard OSM tile server); **vector** (MapLibre GL) is prettier but needs
  a style + fonts + sprites pipeline and a heavier client. The place UI needs a readable basemap, not cartographic
  polish — raster + Leaflet wins on self-host simplicity.
- **Self-hostable by default.** Public tile servers are rate-limited and policy-bound (the OSMF tile usage
  policy); a self-hostable-by-default product must not hard-depend on one vendor's hosted tiles.

Considered and rejected:

- **Commercial tile vendors (Google / Mapbox / Maptiler keys).** Vendor lock-in **and** an API-key/identity leak
  to a data-broker — rejected on both *self-hostable* and *privacy* grounds.
- **Proxying tiles through the veyq backend.** Would hide the user IP from the tile server, but burdens the
  operator with tile bandwidth **and** gives the operator the viewport stream — no net privacy win, and it breaks
  the "server serves only ciphertext" shape. Rejected; tiles go client-direct.
- **Vector tiles / MapLibre GL now.** Rejected for this slice — the style/font/sprite self-host pipeline is more
  than the place UI needs. The seam below does not preclude a vector provider later.
- **Bundling a full planet raster set.** Planet tiles are hundreds of GB — not a sane default to ship. Self-host
  stays *possible* (an operator's own tile server) but is not the bundled default.

## Decision

**Map tiles are raster, rendered by Leaflet, behind a provider-neutral tile-URL seam. OSM is the reference; the
tile URL is operator-configurable, and contacting a public tile server is a consented, client-direct,
veyq-blind third-party boundary.**

- **The seam is a tile-URL template** `https://…/{z}/{x}/{y}.png` (+ attribution + max-zoom). Leaflet's
  `TileLayer` consumes it; swapping providers is a URL + attribution change, no code fork. veyq ships **no**
  vendor-locked map client and **no** API key.
- **veyq-blind by construction.** Tiles are fetched **client-direct** by the browser from the tile URL, **never
  through the veyq backend** (proxying would hand the operator the viewport stream and the bandwidth). A tile
  request carries **no veyq identity, no Memory context, no pin coordinate** — just the `{z,x,y}` of the viewport.
  This keeps the **veyq operator** blind while the *user* consents to the tile server seeing a viewport + their IP.
- **Operator-configurable; self-host or a permitted public instance.** The reference is the **OSM standard raster
  tile server** (ODbL data). Operators either point at their **own** tile server (fully blind, no third party) or
  at a permitted public one; the public OSM tiles come with the **OSMF usage policy** (required attribution, no
  bulk/heavy use) — honored with visible attribution and lazy, viewport-only tile loads.
- **Opt-in + transparent, consistent with ADR 0016.** Contacting a **public** tile server is the same consented
  third-party boundary as public geocoding: gated by the same opt-in with a transparency note ("a map service
  provides the map imagery; your approximate view + IP reach it"). A **self-hosted** tile URL is blind and needs
  no opt-in. With no tile URL configured and no opt-in, the map surface is simply **absent** — set-location falls
  back to the name-only path (a manual name still needs no map and no external call).
- **Attribution is mandatory** wherever OSM tiles render (© OpenStreetMap contributors), per ODbL + the usage
  policy.

## Consequences

- **The veyq operator never sees the map viewport or a pin coordinate** — the invariant holds even with an
  external tile server, because tiles are client-direct and identity-free and no coordinate is ever sent to the
  veyq backend.
- **No vendor lock-in.** Switching tile providers (self-host ↔ public OSM ↔ a future vector provider) is a tile-URL
  (or a thin layer-factory) change behind the same seam.
- **Self-host is first-class, not bundled.** An operator running their own tile server gets a fully-blind map with
  no third party; the bundled default doesn't ship planet tiles, so out-of-the-box the map uses the opt-in public
  OSM tiles (consented) — the same off-until-consented posture as geocoding.
- **Pairs with ADR 0016.** Tiles draw the basemap; geocoding turns the framed pin into a name. Both are
  client-direct, identity-free, opt-in third-party boundaries — documented together in
  [security-model](../crosscutting/security-model.md), not hidden.
- **Limitation (honest).** With public tiles enabled, the tile server **does** learn the viewport tiles + client
  IP. That is a *consented, non-veyq* boundary, distinct from the operator-blind invariant.

> Lands with the set-location slice of `uc.memory.place` (the editor map with the medoid pin) and is reused by the
> later tenant map. This ADR governs where the basemap imagery comes from; ADR 0016 governs turning the pin into a
> name.
