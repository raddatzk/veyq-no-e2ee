# Feature: Journal

A Markdown **journal** per Memory — free-form text with media / track / playlist embeds and optional
real-time multi-user editing.

- **Touched contexts:** Journal, Media (embeds) — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Live Collaboration](../../crosscutting/live-collaboration.md) (blind relay, CRDT,
  snapshots), [Security Model](../../crosscutting/security-model.md) (content keys),
  [Search](../../crosscutting/search.md) (journal text is a semantic source).
- **Decisions:** [AD-17/18/19/20](../../decisions/README.md) (collaboration transport, persistence, clients).

## Use-cases
- ★ [Write the journal](use-cases/uc.journal.write.md) — solo Markdown autosave (REST)
- ★ [Embed media, tracks, chapters & playlists](use-cases/uc.journal.embed.md)
- ★ [Co-edit the journal live](use-cases/uc.journal.live-coedit.md) — real-time collaboration
