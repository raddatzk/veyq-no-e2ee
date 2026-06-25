# UC: Render the web views & navigation

- **ID:** uc.platform.web-views
- **Primary actor:** Memory Curator
- **Goal:** Browse spaces and Memories in the web client — clean-path routing, a node read-cache,
  justified gallery layout, and the home / Memory views — all rendering correctly on the blind client.
- **Preconditions:** Authenticated in the web app with a decrypted space tree.
- **Postconditions (success):** Clean space/Memory paths build and parse round-trip; the read-cache
  serves bursts and invalidates on mutations; galleries lay out justified; the home and Memory views
  render and navigate.

## Main flow
1. The client **builds** a clean path for the active space (tenant / personal `~me` / group `@slug`
   prefix) and **parses** it back to the space + node chain.
2. A **node read-cache** serves repeated reads from one fetch and **drops** entries on
   create / rename / delete.
3. The **gallery** lays photos into **justified rows** (per-tile aspect preserved, trailing **widow** at
   target height) and groups them into **date buckets**.
4. The **home view** renders the space switcher + memory/folder grids; the **Memory view** renders the
   tabs, gallery and journal and navigates into chapters/folders.

## Alternate / exception flows
- A1: A **prefix-less tenant** is resolved by host (multi-tenant).
- A2: The **Journal tab** lazily loads the embed-aware journal; the **live-journal** subscription
  replaces the static load and is torn down on leave.
- E1: **No items / zero container** → an empty layout and an empty range.
- E2: **Invalid timestamps** → a placeholder bucket key (no crash).

## Quality notes
- *Engineering-trust:* the web client's **rendering / navigation shell**; it maps to no single product
  use-case (`capabilities: []`).
- *Operator-blind:* routing and layout run on already-decrypted, client-held data; the server sees
  nothing.

## Related
- Feature: [Platform](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md),
  [Space Navigation](../../../crosscutting/space-navigation.md)
- Related use-cases: `uc.platform.app-shell`, `uc.memory.browse`
