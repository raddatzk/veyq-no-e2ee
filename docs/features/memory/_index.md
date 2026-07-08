# Feature: Memory

The product center: a **Memory** (photos, media, a journal, places & tracks — added in later slices),
organized in an **encrypted tree** of **folders** (organization only) and **Memories** (nested into
**Chapters**). Everything content lives inside a [scope](../spaces/_index.md) and is **end-to-end encrypted**
— the server stores only ciphertext, opaque node ids/parents/kinds/ordering/timestamps, and blinded slug
tokens; it never sees a name or a content key.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the key hierarchy + sharing),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md) (the navigation contract; content lives in a
  scope), [Account & Key Model](../account/enablers/en.account.key-model.md).
- **Decisions:** [ADR 0012](../../decisions/0012-content-key-hierarchy.md) (per-Memory content key `K_mem`,
  random + many-wrapped — the unit of sharing; `K_SC` for navigation; `K_blob` per blob),
  [ADR 0009](../../decisions/0009-native-crypto-per-platform-vectors.md) (the vector-pinned content crypto).
- **Product language (hard rule):** Memory / Chapter. Folders **organize only**. Never "Album" / "Explorer".

## Key model (ADR 0012, in one line)

`scope key (grant) → K_SC` (per scope: names + slug + dedup) `→ K_mem` (per Memory, random: chapter/journal/
media + sensitive metadata; **the share grain**) `→ K_blob` (per media blob). Folders carry no key.

## Enablers

- ★ [en.memory.tree](enablers/en.memory.tree.md) — **refined** (the encrypted node tree: FOLDER/MEMORY/CHAPTER,
  encrypted names, blinded slugs, per-Memory `K_mem` birth/open — the substrate the use-cases below build on;
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md), [cr-0027](../../../changes/cr-0027-memory-tree.md))
- ★ [en.memory.media](enablers/en.memory.media.md) — **refined** (the encrypted media data layer: a blob sealed
  as chunked AEAD under a random `K_blob` wrapped under `K_mem`, transferred direct-to-store via presigned URLs,
  attached to a node; **no content dedup** — operator-blind trade-off;
  [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md),
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- ★ [en.memory.favorites](enablers/en.memory.favorites.md) — **built** (pin a Memory: the per-(user, scope)
  pinned-id set sealed under `K_SC` as one opaque blob — operator-blind, no plaintext favorite flag;
  [cr-0031](../../../changes/cr-0031-memory-shell-polish.md))
- ★ [en.memory.derivatives](enablers/en.memory.derivatives.md) — **refined** (per-item **thumbnail / preview /
  original** tiers — grid = thumbnail, lightbox = preview, download = original; client-generated encrypted
  blobs, **per-blob `K_blob`**, role-tagged on the media record (`original` + `derivatives[{kind, blob}]`), no
  backfill, first-frame video poster, legacy sizes. **Built before the gallery** — where
  [ADR 0013](../../decisions/0013-blob-storage-presigned-direct-to-store.md)'s deferred thumbnails/previews land)
- ★ [en.memory.node-meta](enablers/en.memory.node-meta.md) — **partly built** (the sealed, client-versioned
  `wrappedMeta` blob on a content node — one opaque envelope for a Memory's node-level metadata, sealed under
  `K_mem`, threaded through `createNode` / partial `updateNode` / `listNodes`, materialized so a card/map reads
  it without loading media. Envelope + `date` sub-object shipped cr-0067; `location` sub-object planned)

## Use-cases (on the tree + media enablers)

- Browse & organize the Memory tree — the shell + folder/Memory browse + create/rename/delete
  ([uc.memory.browse](use-cases/uc.memory.browse.md) — **done**, web/apple/android shells)
- [uc.memory.upload](use-cases/uc.memory.upload.md) — **refined** (add media to a Memory: the web upload flow +
  global uploads tray on [en.memory.media](enablers/en.memory.media.md) — `K_blob` + chunked AEAD,
  direct-to-store; the upload also generates the [derivatives](enablers/en.memory.derivatives.md))
- [uc.memory.gallery](use-cases/uc.memory.gallery.md) — **planned** (open a Memory: thumbnail grid + lightbox
  preview + download original + header/favorite; on [en.memory.derivatives](enablers/en.memory.derivatives.md),
  built first)
- [uc.memory.chapters](use-cases/uc.memory.chapters.md) — **refined** (nested Memories inside a Memory — each
  chapter a full Memory with its own recursive `K_mem`, wrapped under the parent's;
  [ADR 0012](../../decisions/0012-content-key-hierarchy.md))
- [uc.memory.date](use-cases/uc.memory.date.md) — **done** (cr-0067): a curatable date (auto/single/range/ongoing),
  sealed on the node's `wrappedMeta`; browse card range + date-sort
- [uc.memory.place](use-cases/uc.memory.place.md) — **planned**: one named location on the same `wrappedMeta`
  (medoid position + typed/reverse-geocoded name), a tenant-wide map; reverse-only, veyq-blind geocoding (ADR 0016)
- [uc.memory.cover](use-cases/uc.memory.cover.md) — **planned** (cr-0072): a deliberately chosen, cropped cover
  (`{ mediaId, crop }` on the same `wrappedMeta`), shown on the browse card, the detail header, and the map pin
- Memory journal — markdown sealed under `K_mem`
- Tracks (GPX) — sealed under `K_mem` (the sealed *location* is now [uc.memory.place](use-cases/uc.memory.place.md))
- Share a Memory — wrap its `K_mem` to a per-share key (least-privilege, ADR 0012) → the
  [Sharing](../sharing/_index.md) feature ([uc.sharing.share-public-viewer](../sharing/use-cases/uc.sharing.share-public-viewer.md),
  **refined**, [ADR 0029](../../decisions/0029-public-viewer-share.md))
