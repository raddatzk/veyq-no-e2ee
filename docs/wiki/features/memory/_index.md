# Feature: Memory

Organizing a library of **folders**, **Memories** and nested **Chapters** with covers, dates and
favorites — the product core. Folders organize; Memories hold the content (media, journal, maps,
playlists). A second, plain file-storage model ("Drive") is planned — see [design notes](../../design-notes.md).

- **Touched contexts:** Memory, Media, Journal, People — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (scope & keys),
  [Space Navigation](../../crosscutting/space-navigation.md) (folders, Memories and chapters),
  [Agent Mode](../../crosscutting/agent-mode.md) (media processing), [Search](../../crosscutting/search.md).
- **Decisions:** [AD-03/04](../../decisions/README.md) (keys, shared-vs-unshared), [AD-10](../../decisions/README.md) (deletion), [AD-32](../../decisions/README.md) (encrypted participants).

## Use-cases
- ★ [Create a Memory tree](use-cases/uc.memory.create-tree.md) — folders, Memories, chapters & permalinks
- ★ [Organize media into buckets](use-cases/uc.memory.organize-buckets.md) — per-uploader buckets & sub-buckets
- ★ [Curate a Memory](use-cases/uc.memory.curate.md) — covers, favorites, dates, highlights
- ★ [Browse a Memory](use-cases/uc.memory.browse.md) — unified gallery, filters (all / mine / group)
- ★ [Add map context and GPS tracks](use-cases/uc.memory.add-map-tracks.md)
- ★ [Consent to external map tiles](use-cases/uc.memory.consent-map-tiles.md) — per-device opt-in before tiles load
- ★ [Link external playlists](use-cases/uc.memory.link-playlists.md)
- ★ [Download a Memory as a ZIP](use-cases/uc.memory.download-zip.md)
