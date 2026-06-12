# Feature: Journal

A Markdown **journal** per Memory — free-form text with media / track / playlist embeds and optional
real-time multi-user editing.

- **Touched contexts:** Journal, Media (embeds) — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Live Collaboration](../../crosscutting/live-collaboration.md) (blind relay, CRDT,
  snapshots), [Security Model](../../crosscutting/security-model.md) (content keys),
  [Search](../../crosscutting/search.md) (journal text is a semantic source).
- **Decisions:** [AD-17/18/19/20](../../decisions/README.md) (collaboration transport, persistence, clients).

## Use-cases
- ☐ `uc.journal.write` — write/edit the Markdown journal (solo: REST autosave)
- ☐ `uc.journal.embed` — embed media / tracks / playlists in the journal
- ☐ `uc.journal.live-coedit` — real-time collaborative editing
