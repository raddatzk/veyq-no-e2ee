# UC: Add map context and GPS tracks

- **ID:** uc.memory.add-map-tracks
- **Primary actor:** Memory Curator
- **Goal:** See geotagged media on a map and add GPX tracks with distance, duration and elevation.
- **Preconditions:** Authenticated; may edit the Memory; holds the keys; media may carry GPS EXIF.
- **Postconditions (success):** GPX tracks render as polylines with distance/duration/elevation;
  geotagged media cluster on the map; external map tiles and place names load only after consent; the
  server learns only that the Memory has tracks.

## Main flow
1. The curator uploads a **GPX** file; it is stored as an encrypted blob.
2. The **client parses** it — distance, duration and elevation from the GPX's embedded `<ele>` (no
   external lookup) — and renders the track as a **polyline**.
3. Geotagged media **cluster** on the map from their decrypted GPS EXIF; tracks and media are
   visually linked.
4. Before any tiles load, the curator **consents** to fetching **public map tiles** (OpenStreetMap)
   — and, if enabled, **reverse-geocoded place names** — from a third party. Place names are stored
   **encrypted**.
5. Each medium's **location** comes from its EXIF; the **uploader decides at upload time** whether to
   keep it or **strip** it for privacy (e.g. hide a home address). Post-upload per-media location
   editing is a **later** refinement.
6. **Any member** may set a **Memory-level location** (a representative place for the Memory),
   independent of per-media GPS.

## Alternate / exception flows
- A1: The curator labels a track.
- A2: A GPX **without** embedded elevation → distance/duration shown, **elevation omitted** (no DEM
  lookup in v1).
- E1: No media carry GPS and there are no tracks → the map is empty / hidden.
- E2: Tile/geocoding consent declined → **no external requests**; the map stays blank and places are
  not resolved.

## Quality notes
- *Third-party leak (the key risk):* our server is blind, but rendering tiles / resolving place names
  reveals the viewed **coordinates** to the tile/geocoding provider. This is **consent-gated**; v1 uses
  public OSM. A **self-hosted tile/geocoding endpoint** (zero third-party leak) is a later option.
  Outbound requests carry no veyq identifiers. (See [AD-27](../../../decisions/README.md).)
- *Confidentiality:* GPX blobs, polylines, GPS and place names are **encrypted**; the server sees only
  that the Memory has N tracks.
- *Privacy control:* a GPS **strip is an upload-time decision** by the uploader, so the stored original
  is already clean — no post-hoc rewrite. Post-upload strip and coarsening to city level are later
  refinements.
- *Permissions:* per-media location/GPS is decided by the **uploader at upload**; the **Memory-level
  location** and `takenAt` correction may be set by **any member** (see [curate](uc.memory.curate.md)).
- *Processing:* GPX parsing is lightweight and **client-side only** — no Agent-Mode job.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (encrypted vs. visible),
  [Platform Concepts](../../../crosscutting/platform-concepts.md) (consent)
- Decisions: [AD-02](../../../decisions/README.md) (E2E / blind server), [AD-27](../../../decisions/README.md) (external rendering calls)
- Related use-cases: `uc.memory.curate`, `uc.uploads.add-media`
