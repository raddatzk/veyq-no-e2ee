# ADR 0016 — Geocoding: reverse-only, provider-neutral, veyq-blind (Nominatim reference)

- Status: Accepted
- Date: 2026-07-01

## Context

A Memory can carry a **named location** ([uc.memory.place](../features/memory/use-cases/uc.memory.place.md)):
a map position derived from the Memory's own photo GPS, plus a **name**. Turning a coordinate into a human name
needs a **geocoding service** — and that is the one part of the location story that reaches outside the device.
The tensions we had to resolve during refinement:

- **Operator-blindness is about the *veyq* operator.** A coordinate leaving the device to a third-party map
  service is a *different* trust boundary than the veyq server seeing it. The hard invariant is that the **veyq
  operator** stays blind; a **consented, client-direct** request to a geocoder is acceptable.
- **Which direction?** The location flow is **coordinate-first** (photos, or a marker set on the map) → name.
  There is never a "type a place name to search" step; the manual fallback is a **free-typed string**, not a
  geocoder search. So veyq needs **reverse** geocoding only, never forward autocomplete.
- **Which provider?** The earlier inbox framing named **Photon** — chosen for *forward search-as-you-type*, which
  we no longer need. Photon's reverse is nearest-feature and its public instance is limited to `de/en/fr` and
  **hard-errors** on other languages. **Nominatim** (OSM's canonical geocoder) instead offers reverse with a
  **`zoom`** parameter (0–18 → country … building) that maps a chosen resolution to a name granularity
  (`Atlantik → Kanaren → Teneriffa`), and localizes via `accept-language` with **graceful fallback** to the local
  name (never a hard error).
- **Self-hostable by default.** Public geocoder instances are rate-limited and policy-bound; a
  self-hostable-by-default product must not hard-depend on one vendor's hosted service.

Considered and rejected:

- **Commercial geocoders (Google / Mapbox / HERE).** Vendor lock-in **and** the request is a leak to a
  data-broker — rejected on both *self-hostable* and *privacy* grounds.
- **Proxying the geocode through the veyq backend.** Would make the request cacheable/simple, but the **operator
  would then see the coordinate** — breaks the invariant. Rejected.
- **Hard-binding to a single provider (Photon *or* Nominatim).** Rejected — veyq binds to no vendor; the provider
  is a seam.
- **Forward autocomplete.** Not needed by the coordinate-first flow; it was Photon's only advantage, so dropping
  it removes the reason to prefer Photon.

## Decision

**Geocoding is reverse-only, opt-in, client-direct, and behind a provider-neutral seam. Nominatim is the
reference provider; the endpoint is operator-configurable and off until configured.**

- **The seam is `reverse(lat, lng, zoom, lang) → name`.** `zoom` selects the name's **granularity** (the map's
  framed extent → centre + zoom = the stored `nameAnchor`); `lang` = the UI locale. Providers map these as best
  they can — Nominatim uses `zoom` + `accept-language` natively; another OSM geocoder maps to its own shape.
- **veyq-blind by construction.** The request goes **client-direct to the provider, never through the veyq
  backend** (proxying would reveal the coordinate to the operator). It carries **no veyq identity and no Memory
  context** — a bare coordinate. This keeps the **veyq operator** blind while the *user* consents to the
  provider seeing a coordinate + their IP.
- **Opt-in + transparent; opt-in is standing consent.** Off by default; enabling it is an explicit choice with a
  transparency note ("a map service turns the pin into a name; a coordinate leaves your device"). Once enabled it
  may run **automatically** — e.g. a reverse lookup on the first geotagged media of a `geocoded` auto-location, at
  a default zoom — because enabling it is the consent to those requests; it never fires without opt-in. Manual
  name entry is always available and needs no external call.
- **Operator-configurable, off-until-configured.** The reference provider is **Nominatim** (OSM data, ODbL /
  GPL, actively maintained, vendor-neutral). Operators point at their own instance (self-hostable) or a permitted
  public one; with no endpoint configured, the reverse-lookup feature is simply **absent** (manual names still
  work).
- **The resolved name is stored, not the request repeated.** The name is sealed into the shared `wrappedMeta`
  ([ADR 0012](0012-content-key-hierarchy.md)); other members **see** it without their device geocoding. Stored
  names are **single frozen strings** in the resolver's language — no per-locale storage, no auto-retranslation
  (the blind server can't, and it would mean a fresh request); `nameAnchor` is kept so an opted-in viewer can
  **re-resolve in their own language** on demand.

## Consequences

- **The veyq operator never sees a coordinate or a place name** — the invariant holds even with an external
  geocoder, because the request is client-direct and identity-free and the stored result is sealed under `K_mem`.
- **No vendor lock-in.** Swapping Nominatim for another OSM-based geocoder is an endpoint + a thin adapter behind
  the same `reverse()` seam; veyq ships no provider-locked client.
- **Multilingual "for free" at lookup time, frozen at rest.** `accept-language` + OSM `name:<lang>` fallback
  cover de+en well and degrade (never break) for further locales; the stored name is one string, re-resolvable
  via `nameAnchor`.
- **A blind, leak-free path remains open.** Manual names need no request at all; a future **on-device coarse
  gazetteer** (a bundled place dataset resolving `lat/lng → country/region/city` on-device) can sit behind the
  same seam as a fully-blind coarse-name provider — deferred for its per-locale data cost, not excluded.
- **Native caveat.** The OS geocoders (`CLGeocoder`, Android `Geocoder`) call their vendors' servers, so they are
  **not** a blind option; native "blind" naming means the bundled dataset, not the platform API.
- **Limitation (honest).** With an external provider enabled, the geocoder **does** learn a bare coordinate + the
  client IP. That is a *consented, non-veyq* boundary — documented in
  [security-model](../crosscutting/security-model.md), not hidden.

> Lands with the reverse-geocoding slice of `uc.memory.place` (place Slice 3). The location model + "set
> location" (manual names, no geocoding) and the tenant map precede it; this ADR governs the moment a coordinate
> is turned into a name.
