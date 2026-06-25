# veyq AI Documentation (Spielregeln)

This directory is the **operating context for AI agents and contributors** working on veyq — the
"rules of the game" for *how* to work, distinct from *what* the product is.

> Two documentations, decoupled:
> - **Human / product / architecture docs** → `docs/wiki/` (arc42), published as the GitHub Wiki.
> - **AI working methodology (this dir)** → repo-only; describes how an agent works on veyq.
>
> The AI docs are **not** coupled to individual use-cases or arc42 chapters; they are cross-cutting
> working rules.

## Read first

1. [`working-methodology.md`](working-methodology.md) — the core rules: terminology, privacy &
   security invariants, self-hostability, the trust chain, commit and config conventions.
2. [`documentation-guide.md`](documentation-guide.md) — how to author and maintain the arc42 docs
   and the Cockburn-lite use-cases, and how the Wiki sync works.
3. [`local-smoke.md`](local-smoke.md) — repeatable local web smoke seed + browser verification.

## Architecture & product (human docs)

- Architecture: [`docs/wiki/Home.md`](../wiki/Home.md). The **Security & Encryption Model**
  in [`crosscutting/security-model.md`](../wiki/crosscutting/security-model.md) is foundational —
  **read it before touching any data, key, media, sharing or storage path.**
- Features & use-cases: [`docs/wiki/features/`](../wiki/features/).

## Reference (being retired)

The legacy `docs/` tree (`architecture.md`, `product/**`, `adr/**`) is **reference** while the new
arc42 docs are written. Prefer the arc42 docs; consult legacy docs for not-yet-migrated detail. Do
not build new structure on the legacy layout.
