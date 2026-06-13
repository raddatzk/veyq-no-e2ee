# veyq Agent Context

This file is the short entry point for AI agents and contributors. The full working methodology
lives in **[`docs/ai/`](docs/ai/README.md)**; the architecture lives in **[`docs/wiki/`](docs/wiki/Home.md)**
(arc42). The GitHub-facing project overview is [README.md](README.md).

## Read first

1. [`docs/ai/README.md`](docs/ai/README.md) — how to work on veyq (the Spielregeln).
2. [`docs/ai/working-methodology.md`](docs/ai/working-methodology.md) — terminology, security/
   privacy invariants, the trust chain, commit/config conventions.
3. [`docs/wiki/crosscutting/security-model.md`](docs/wiki/crosscutting/security-model.md) — the
   **Security & Encryption Model**. Read it before touching any data, key, media, sharing, search
   or storage path.
4. [`docs/ai/documentation-guide.md`](docs/ai/documentation-guide.md) — authoring arc42 docs and
   use-cases.

## Non-negotiables (quick reference)

- **Full E2EE everywhere; the server is blind.** A user's unshared personal space is confidential
  even from the operator — never design a path that breaks this. Privacy-preserving, non-enumerable
  APIs. Self-hostable by default.
- Product language: **Memory / Chapter**; never reintroduce *Album* / *Explorer*.
- Capture stable decisions as **ADRs**; document before or alongside implementation.
- All repository docs in **English**; code identifiers/tables/config keys unchanged.
- Run `node scripts/validate-feature-coverage.mjs` after coverage/test changes and
  `node scripts/validate-usecases.mjs` after use-case changes.

## Documentation map

- **Architecture (arc42):** [`docs/wiki/`](docs/wiki/Home.md)
- **Features & use-cases:** [`docs/wiki/features/`](docs/wiki/features/)
- **Acceptance coverage:** [`docs/wiki/coverage/`](docs/wiki/coverage/)
- **AI working methodology:** [`docs/ai/`](docs/ai/README.md)
- **Reference (being retired):** legacy `docs/` (`architecture.md`, `product/**`, `adr/**`).
