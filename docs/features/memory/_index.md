# Feature: Memory

The product center: a **Memory** (photos, media, a journal, places & tracks — added in later slices),
organized in an **encrypted tree** of **folders** (organization only) and **Memories** (nested into
**Chapters**). Everything content lives inside a [scope](../spaces/_index.md) and is **end-to-end encrypted**
— the server stores only ciphertext, opaque node ids/parents/kinds/ordering/timestamps, and blinded slug
tokens; it never sees a name or a content key.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (the key hierarchy + sharing),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md) (the navigation contract; content lives in a
  scope), [Account & Key Model](../../crosscutting/account-key-model.md).
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

## Use-cases (planned, on the tree enabler)

- Create & organize memories (folders, rename, move, cover) — the user-facing curation, with UI
- Add media to a Memory — uploads / `K_blob` + chunked AEAD (the uploads concern)
- Memory journal — markdown sealed under `K_mem`
- Places & tracks (map / GPX) — sealed location under `K_mem`
- Share a Memory — wrap its `K_mem` to a per-share key / recipient identity — least-privilege, ADR 0012
