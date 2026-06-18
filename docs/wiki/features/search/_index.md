# Feature: Search

Find memories and media by **structure** (date/type/scope), **text** (titles/journal/tags), and — later
— **faces and semantics**, all under a blind server.

- **Touched contexts:** spans the content contexts; query orchestration is client-side — see
  [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Search](../../crosscutting/search.md) (the model: three layers, query schema,
  on-device ML), [Security Model](../../crosscutting/security-model.md) (encrypted vs. visible),
  [Capability discovery](../../crosscutting/platform-concepts.md) (degrades by device).
- **Decisions:** [AD-13](../../decisions/README.md) (search model), [AD-14](../../decisions/README.md)
  (ML on-device, deferred).

## Use-cases
- ★ [Filter & find](use-cases/uc.search.filter.md) — client-side name search today; structural facets + journal/tag text deferred
- `uc.search.natural-language` — natural-language / semantic / "ask your memories" (deferred, on-device LLM)
