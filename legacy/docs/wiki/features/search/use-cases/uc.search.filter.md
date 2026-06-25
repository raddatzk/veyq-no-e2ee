# UC: Filter & find

- **ID:** uc.search.filter
- **Primary actor:** Memory Curator
- **Goal:** Quickly find a Memory or folder by typing part of its name — without the server ever seeing
  the query.
- **Preconditions:** Authenticated; in a space whose tree is loaded and whose names are decrypted
  on-device.
- **Postconditions (success):** The visible list narrows to nodes whose **decrypted names** match the
  query; clearing the query restores the full list. The operator learns nothing about the search.

## Current scope
Search runs **entirely client-side** over already-decrypted node names — a case-insensitive **substring
match** shared by the web, Android and Apple clients. There is **no server search endpoint**: by design
the server cannot index encrypted names.

Planned People integration extends the same local index with decrypted participant display names. The server
still receives neither the query nor a plaintext person index.

## Main flow
1. The curator types a query in the space.
2. The client matches it (case-insensitive substring) against the **decrypted names** of the loaded
   nodes.
3. The matching memories / folders are shown; the rest are hidden.
4. Clearing the query restores the full listing.

## Alternate / exception flows
- A1: **Empty query** → the full list is shown.
- A2: **No match** → an empty-state is shown.
- E1: Nodes **not yet loaded / decrypted** on this device aren't searchable until loaded (search covers
  the in-memory tree only).

## Quality notes
- *Confidentiality (paramount):* **operator-blind by construction** — the query and names never leave the
  device; there is no server-side index of encrypted content.
- *Roadmap outside this use-case:* structural facets (date/type/scope filter chips), full-text over
  **participants/journal/tags**, and **semantic/face** search are deferred (see
  [natural-language](../../search/_index.md) and the Search model below).

## Related
- Feature: [Search](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Search](../../../crosscutting/search.md) (three-layer model, query schema),
  [Security Model](../../../crosscutting/security-model.md) (encrypted vs. visible)
- Decisions: [AD-13](../../../decisions/README.md) (search model)
- Related use-cases: `uc.memory.browse`, `uc.search.natural-language`
