# UC: Curate a Memory

- **ID:** uc.memory.curate
- **Primary actor:** Memory Curator
- **Goal:** Give a Memory a cover and date range, and mark favorites and highlights.
- **Preconditions:** Authenticated; is a member of the Memory's scope; holds the keys.
- **Postconditions (success):** Cover, date-range override, the personal favorite and the group/personal
  highlights are set; shared fields are encrypted; the gallery orders media by taken time.

## Main flow
1. **Cover** — the curator designates an **image** media item as the cover (`coverMediaId`). A **video
   cannot** be a cover; with none set, a **placeholder** is shown. (A video's own thumbnail/preview
   uses its **first frame**.)
2. **Date range** — derived from `min`/`max` `takenAt` over all descendants; the curator may set a
   **sticky manual override** (clearing it reverts to derived).
3. **Time correction** — **any member** may correct a wrong `takenAt`:
   - **single** item → set its timestamp directly;
   - **bulk** → set the corrected timestamp of the **first** selected item; the resulting offset is
     applied to the rest, **preserving their relative spacing**.
   Ordering and the derived date range update accordingly.
4. **Personal Memory favorite** — the curator marks the **Memory** as a favorite; **per-user** (→ a
   personal "Favorites" list of memories).
5. **Media favorites & highlights** — within a Memory, media can be favorited at two independent levels,
   switchable in the gallery via **all / mine / group**:
   - **mine** — the user's **personal** media favorites (per-user);
   - **group** — the **shared** group highlights any member sets for the Memory (the "Highlights");
   - **all** — every medium (no favorite filter).

## Alternate / exception flows
- A1: A video is chosen as cover → not allowed; the curator picks an image or keeps the placeholder.
- A2: No cover set (or a Memory with no images) → placeholder shown.
- E1: A non-member tries to curate → rejected.

## Quality notes
- *Permissions (per field):* **cover, date override, `takenAt` correction and the group highlight** may
  be set by **any member** of the scope; **personal favorites** (Memory and media) are **per-user**.
  Per-media **location/GPS** is **not** set here — it is an upload-time decision (see
  [add-map-tracks](uc.memory.add-map-tracks.md)). Delete/move follow [AD-10](../../../decisions/README.md).
- *Concurrency:* shared scalar fields (cover, date override, group highlight) are **last-write-wins** —
  no CRDT merge (unlike the journal). Personal favorites are per-user → never conflict.
- *Confidentiality:* sensitive metadata (the Memory title) and the cover image stay encrypted; the
  server sees only the `coverMediaId` pointer and low-sensitivity `takenAt` for ordering.
- *Functional suitability:* media order derives from `takenAt` — **no separate manual sort field**.
- *Later:* a short **per-media caption** (one line per photo/video) is a future addition — today the
  only text surfaces are the Memory **title** and the **Journal**.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md) (encrypted vs. visible, per-user
  vs. shared state), [Agent Mode](../../../crosscutting/agent-mode.md) (first-frame derivative),
  [Search](../../../crosscutting/search.md) (ordering metadata)
- Decisions: [AD-04](../../../decisions/README.md) (shared vs. unshared), [AD-13](../../../decisions/README.md) (ordering metadata), [AD-10](../../../decisions/README.md) (delete/move)
- Related use-cases: `uc.memory.create-tree`, `uc.memory.add-map-tracks`, `uc.uploads.add-media`
