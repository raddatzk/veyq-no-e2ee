# Working Methodology (Spielregeln)

How to work on veyq. These rules carry forward the previous `AGENTS.md` working rules and add the
new architecture direction.

## Project intent

veyq is a **self-hostable memory-sharing product** — not a cloud drive or folder gallery. It helps
people preserve and share trips, events and personal moments as curated **Memories** with photos,
videos, journal text, maps, tracks, music and metadata. Memory UI and Memory APIs are the active
baseline. *Album* and *Explorer* are historical and must not be reintroduced.

## Security & privacy invariants (highest priority)

- **Full end-to-end encryption everywhere** is the target architecture. The server is **blind** to
  content. Read [`docs/wiki/crosscutting/security-model.md`](../wiki/crosscutting/security-model.md)
  before touching any data, key, media, sharing, search or storage path.
- **The paramount invariant**: a user's unshared personal space is confidential even from the
  operator. Never design a path where the operator gains content access unilaterally or silently.
- **Privacy-preserving APIs**: never expose cross-memory person graphs or unauthorized memories.
  Person lookup must stay **non-enumerable**.
- **Keep veyq self-hostable by default.** Managed hosting, push gateways, federation and commercial
  features are optional layers, never required.

## Product & terminology rules

- Use **Memory** for product-facing concepts; **Chapter** (de: *Kapitel*) for nested Memories.
  *Folder* is organizational only. Do **not** add Album/Explorer flows, models, APIs or tables.
- Follow the Memory UI styleguide (reference: `docs/product/memory-ui-styleguide.md`) and reuse
  shared `.veyq-*` CSS classes before adding new ones.
- For iOS, follow the iOS UI styleguide and inspect current SwiftUI under `ios/` first.
- Keep client-parity documentation current when web/iOS/Android/API behavior changes; mark
  intentional platform differences explicitly.

## The trust chain (acceptance coverage)

The Greenfield trust chain is use-case centered:

```
Feature → Use-case → Coverage file → Acceptance criteria → Positive/negative tests or explicit gaps → CI
```

- Treat the mirrored Greenfield coverage tree (`docs/wiki/coverage/features/<feature>/use-cases/`) as
  the source of truth for acceptance coverage. Every implemented behavior maps to a use-case coverage
  file with the same ID and feature folder as its `docs/wiki/features/<feature>/use-cases/` source.
- The legacy feature catalog (`docs/product/features/`) is reference while old product detail is
  migrated; do not add new active coverage there.
- Prefer meaningful, risk-based coverage over coverage percentages; use positive **and** negative
  tests where behavior has real success/failure paths.
- Run `node scripts/validate-feature-coverage.mjs` after changing coverage docs or tests.
- New architecture documents live under `docs/wiki/` (arc42); run
  `node scripts/validate-usecases.mjs` after changing use-cases.

## Decisions & documentation

- Capture stable decisions about terminology, privacy, sharing, storage, encryption, licensing,
  sync or migrations as **ADRs** (see [`docs/wiki/decisions/README.md`](../wiki/decisions/README.md)
  and the legacy `docs/adr/`). Use product docs for exploratory ideas and open questions.
- Document meaningful product/architecture decisions in the docs **before or alongside**
  implementation.
- API contracts live in `docs/api` and are generated into backend and frontend code; the API is
  **internal**.

## Code conventions

The full code-level styleguide (layering, naming, comments, input validation, error handling, testing,
cross-platform parity) lives in the [Developer Styleguide](../wiki/crosscutting/developer-styleguide.md).
Highlights:

- Backend: model project-owned `veyq.*` properties with complete `@ConfigMapping` classes (not
  per-value `@ConfigProperty`); internal `quarkus.*` may use direct injection.
- Use domain-scoped **Conventional Commits** (see `docs/development.md`). Database/migration changes
  use the `backend` scope; multi-domain changes list each affected domain.
- Write all repository documentation in **English**. Keep code identifiers, table names, annotations
  and config keys unchanged.

## When changing behavior

Check the affected use-case coverage file, acceptance criteria, positive/negative coverage and remaining gaps
before considering the work complete. When you touch a security/privacy/sharing path, re-read
the [Security Model](../wiki/crosscutting/security-model.md) and confirm the paramount invariant still holds.
