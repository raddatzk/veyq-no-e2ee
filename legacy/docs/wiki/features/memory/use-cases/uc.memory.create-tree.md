# UC: Create a Memory tree

- **ID:** uc.memory.create-tree
- **Primary actor:** Memory Curator
- **Goal:** Structure a library with folders, Memories and chapters to organize trips and events.
- **Preconditions:** Authenticated; has access to a tenant, group or personal space; holds the keys
  for that space.
- **Postconditions (success):** Folders, Memories and chapters nest per the containment rules; each
  child's slug is unique within its parent; content is encrypted under the space's content keys; the
  server holds only the ID graph, blinded slug tokens and ordering metadata.

## Node types
- **Folder** — organizational only; nests folders and Memories. No media, date or cover.
- **Memory** — a content unit (date range, cover, media, chapters). May live **anywhere** — at the space
  root or inside a folder.
- **Chapter** — a nested Memory inside a Memory; itself a full Memory (own date/cover/media) and may
  nest further. **No folders inside a Memory.**

Within a Memory, media are grouped into per-uploader **buckets** — an axis orthogonal to chapters; see
[organize-buckets](uc.memory.organize-buckets.md).

## Main flow
1. The curator optionally creates a **folder** to group Memories — e.g. `urlaube/`.
2. The curator creates a **Memory** anywhere (root or in a folder) with a title; the title yields a
   **slug** for the permalink (`/urlaube/italien-2024`).
3. The curator adds **chapters** (nested Memories) to sub-divide an event.
4. The curator **orders siblings** manually (an explicit order index); media *inside* a Memory stay
   ordered by `takenAt`.
5. The Memory's **date range** is derived from its content (`takenAt`), with an optional manual
   override that sticks.
6. The system encrypts new content under the space's per-subtree content key; the server stores only
   ciphertext, the **ID graph**, blinded **slug tokens** and ordering indices.

## Alternate / exception flows
- A1: **Slug collision** under the same parent (across folders/Memories/chapters) → the server rejects
  the create/rename via the `UNIQUE(parentId, slugToken)` constraint; the client suggests another name
  (see [slug tokens](../../../crosscutting/security-model.md)).
- A2: The curator **moves** a node within the same scope → accepted; the client-side canonical path
  updates (the server only re-points the parent edge — it stores no paths or names).
- A3: The curator **reorders** siblings → the order index updates (a server-visible integer; no name
  exposure).
- E1: A **cycle** (moving a node under its own descendant), a **cross-scope** move (different content
  key → would be copy + re-encrypt, not a move), or placing a **folder inside a Memory** → rejected
  (tree invariants).
- E2: The space's keys are unavailable on this device → the curator is prompted to enroll the device
  or use the recovery code before creating content.

## Quality notes
- *Confidentiality (paramount):* names/titles are **encrypted**; the operator sees only the ID graph,
  blinded slug tokens and low-sensitivity ordering metadata — never names or paths.
- *Functional suitability:* slug uniqueness per parent and the tree invariants (no cycles, no
  cross-scope moves, no folder-in-Memory) must hold — positive and negative cases.
- *Usability:* clean permalinks (`/urlaube/italien-2024/tag-1`) without exposing names to the server.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (scope keys, slug tokens,
  encrypted vs. visible), [Space Navigation](../../../crosscutting/space-navigation.md) (tree model and listing),
  [Search](../../../crosscutting/search.md) (ordering metadata)
- Decisions: [AD-03](../../../decisions/README.md) (per-subtree keys), [AD-26](../../../decisions/README.md) (blinded slug tokens)
- Related use-cases: `uc.uploads.unified-media`, `uc.memory.curate`
