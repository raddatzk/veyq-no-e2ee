# UC: Browse a Memory

- **ID:** uc.memory.browse
- **Primary actor:** Memory Curator
- **Secondary actor:** Public Viewer
- **Goal:** Navigate the library and view a Memory — its gallery, map, playlists, journal, chapters and
  buckets — and open individual media.
- **Preconditions:** May read the target (a member holding the keys, or share access).
- **Postconditions (success):** The decrypted Memory is shown (header + tabs); the gallery lists media
  across all buckets and chapters ordered by `takenAt`; the media viewer and filters work client-side.

## Main flow
1. **Navigate.** The scope root holds any number of **folders and Memories**. Opening a **folder** shows
   its folders and Memories (recursively); opening a **Memory** opens the Memory view.
2. **Memory header.** Shows the **cover**, metadata (title, date range, media count, participants) and
   actions — favorite, upload, download ([download-zip](uc.memory.download-zip.md)), edit, set cover,
   participants, share, delete.
3. **Tabs.** The Memory body is split into tabs:
   - **Gallery** — the unified media grid across all **buckets and chapters**, ordered by `takenAt`; a
     filter switches **all / mine / group** (personal favorites vs. shared highlights).
   - **Map** — geotagged media + GPX tracks (see [add-map-tracks](uc.memory.add-map-tracks.md)).
   - **Playlists** — attached links (see [link-playlists](uc.memory.link-playlists.md)).
   - **Journal** — the collaborative journal (Journal feature).
   - **Chapters** — the nested Memories; opening one scopes the view to it.
   - **Buckets** — per-uploader contributions (see [organize-buckets](uc.memory.organize-buckets.md)).
   - **Info** — general metadata.
4. **Open a medium → lightbox.** A medium opens in a **lightbox** with **previous/next** and an **info
   sidebar** (date, location, bucket/uploader). Prev/next is **scoped to the active filter** — e.g.
   filtered to *my highlights*, the lightbox cycles only those.
5. **Map clustering.** On the map, media **cluster** when zoomed out; clicking a cluster opens a lightbox
   cycling **only that cluster's** media.

## Alternate / exception flows
- A1: A **chapter** is opened → the same Memory view, scoped to that chapter and its descendants.
- A2: A **Public Viewer** (no account) has no personal favorites → the gallery offers **all / group**
  only.
- E1: The keys are unavailable on this device → the actor is prompted to enroll the device or recover.

## Quality notes
- *E2E rendering:* everything renders **client-side** from decrypted data; the server serves only
  ciphertext plus low-sensitivity ordering metadata.
- *Lazy decryption (performance):* opening a Memory does **not** decrypt everything — metadata and
  **thumbnails decrypt lazily on scroll**; full-resolution images and **video stream segment-wise on
  open** ([AD-28](../../../decisions/README.md)).
- *Consistency:* the lightbox and map clusters always cycle within the **active filter**, so navigation
  matches what the user sees.

## Related
- Feature: [Memory](../_index.md) · Actors: [Memory Curator](../../../actors.md), [Public Viewer](../../../actors.md)
- Concepts: [Security Model](../../../crosscutting/security-model.md),
  [Space Navigation](../../../crosscutting/space-navigation.md) (scope-aware browsing),
  [Search](../../../crosscutting/search.md),
  [Agent Mode](../../../crosscutting/agent-mode.md) (derivatives & streaming)
- Decisions: [AD-02](../../../decisions/README.md) (E2E / blind server), [AD-13](../../../decisions/README.md) (ordering metadata), [AD-28](../../../decisions/README.md) (segmented streaming)
- Related use-cases: `uc.memory.curate`, `uc.memory.organize-buckets`, `uc.memory.add-map-tracks`, `uc.memory.download-zip`
