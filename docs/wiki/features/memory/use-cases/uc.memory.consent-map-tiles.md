# UC: Consent to external map tiles

- **ID:** uc.memory.consent-map-tiles
- **Primary actor:** Memory Curator
- **Goal:** Decide, per device, whether a Memory's map may load external tiles — the one place
  plaintext-derived (coarse) location would cross an origin boundary to a tile provider.
- **Preconditions:** Viewing a Memory/track with a map; tiles are not yet consented on this device.
- **Postconditions (success):** Tiles load only after an explicit opt-in; the choice is stored **locally**
  (per origin), never synced to the operator-blind server; opting out stops further tile requests.

## Why it matters
Memory content (including GPS) is end-to-end encrypted, but rendering a map requires fetching **tiles**
from a third party (OpenStreetMap on web/Android, Apple on iOS) keyed to the (coarse) location. That
request is the **single point** where plaintext-derived data leaves the origin, so it is gated behind
explicit, local, revocable consent.

## Main flow
1. The curator opens a map (e.g. a GPS track) on a device that has **not** consented.
2. The map shows a **consent prompt** instead of loading tiles.
3. The curator **opts in**; tiles load and the choice is **persisted locally** (per origin).
4. On later visits the map loads tiles directly — consent is remembered **on this device only**.

## Alternate / exception flows
- A1: The curator **opts out** (or never opts in) → no tiles are requested; the map degrades to a
  no-tile view.
- A2: A **different device / origin** → consent is asked again (never synced).
- E1: The consent store is unavailable → default to **not** loading tiles (privacy-safe default).

## Quality notes
- *Confidentiality (paramount):* the consent flag lives only on the device (localStorage), never reaches
  the operator-blind server; tiles load only with consent.
- *Privacy-safe default:* absent an explicit opt-in, tiles do **not** load.
- *Per-origin / per-device:* the choice does not follow the account across devices.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (origin boundary),
  [Your Privacy, in Plain Language](../../../crosscutting/privacy-in-plain-language.md)
- Related use-cases: `uc.memory.add-map-tracks`
