# UC: Embed media, tracks, chapters & playlists

- **ID:** uc.journal.embed
- **Primary actor:** Memory Curator
- **Goal:** Inline references to the Memory's own media, GPX tracks, child chapters and linked playlists
  inside the journal, rendered as rich blocks.
- **Preconditions:** Editing a journal ([write](uc.journal.write.md)); the referenced
  media/tracks/chapters/playlists belong to the same Memory subtree and content key.
- **Postconditions (success):** Embed tokens are stored **inline in the sealed Markdown**; on render they
  resolve to a media carousel, a track mini-map with stats, a chapter link, or an external playlist
  link; unresolved ids degrade to a placeholder.

## Embed model
- **Token syntax:** `::<kind>:<id[,id…]> <optional label>`, ending at newline / EOF / the next token.
  Kinds: `media`, `track`, `chapter`, `playlist`.
- **media** — comma list → a photo/video **carousel**.
- **track** — comma list (deduped, **capped at 20**) → a **map** with distance / duration / ascent.
- **chapter** — **first id only**, must point at a child node → a navigable **link**.
- **playlist** — **first id only** → an attached streaming link (provider icon derived client-side);
  opens externally.

## Main flow
1. In the editor the curator types `::` (or taps "+ Einbetten") and picks a **kind**; a picker lists the
   Memory's media / tracks / chapters / playlists.
2. The selection is serialized to a **token** on its own line and inserted into the Markdown.
3. In the live editor the token shows as an **atomic chip**; on save the body stays plain Markdown (the
   token is part of the sealed text).
4. On read, the client **parses** tokens, **resolves** ids against the Memory's content and renders each
   as its block; surrounding Markdown renders normally.

## Alternate / exception flows
- A1: Duplicate track ids / more than 20 → **deduped and capped**; extra ids dropped.
- A2: Multiple ids on `chapter` / `playlist` → only the **first** is used.
- A3: Deleting an embed in the live editor → the chip deletes **atomically** (a first Backspace *arms*
  it, a second removes the whole token; moving the cursor *disarms*).
- E1: An **empty target** (`::media:`) → the token is ignored.
- E2: An **unresolved id** (deleted media/track/chapter, or missing playlist) → a muted **placeholder**
  renders instead of the block; the rest of the journal is unaffected.

## Quality notes
- *Confidentiality:* tokens live inside the **sealed** Markdown — the operator never learns which
  media/tracks/chapters a journal references.
- *Cross-platform parity:* the token grammar is **byte-compatible** across the web and iOS/Android
  parsers; a token written on one platform must render identically on the others.
- *Robustness:* resolution is **best-effort** — embeds never break the surrounding text when a target is
  gone.

## Related
- Feature: [Journal](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Decisions: ADR 0010 (playlist links), ADR 0016 (journal sync)
- Related use-cases: `uc.journal.write`, `uc.memory.link-playlists`, `uc.memory.add-map-tracks`
