# veyq Architecture Documentation

Human-facing documentation for **veyq**, a self-hostable platform for curating memories (photos,
videos, journal, maps, tracks, music) and sharing them with clear, privacy-preserving controls.

Organized **feature-first**: features and their use-cases hold everything about themselves; shared
concerns are factored into *cross-cutting* and *overview*. It is published as the GitHub Wiki and
auto-synced from `docs/wiki/**` — **edit the repo, not the wiki**.

> Describes the **target professional architecture** we are designing; the running system is the
> baseline/reference, migrated incrementally. Open points are marked **(open)**.

## Start here

- New? Read [Goals, Stakeholders & Quality](overview/goals-stakeholders-quality.md) — what veyq is and
  the qualities that drive every trade-off.
- Want the technical picture? [System Architecture](overview/architecture.md).
- Care about privacy/encryption? [Security Model](crosscutting/security-model.md) — the heart of veyq.

## Overview (system-wide)

- [System Architecture](overview/architecture.md) — tech stack, building blocks, runtime mechanics
- [Goals, Stakeholders & Quality](overview/goals-stakeholders-quality.md)
- [Constraints & Solution Strategy](overview/constraints-and-strategy.md)
- [Deployment](overview/deployment.md)
- [Risks & Technical Debt](overview/risks.md)
- [Glossary](overview/glossary.md)

## Cross-cutting concepts (one source, referenced by features)

- [Security & Encryption Model](crosscutting/security-model.md)
- [Agent Mode — Processing Pipeline](crosscutting/agent-mode.md)
- [Search](crosscutting/search.md)
- [Live Collaboration (Journal)](crosscutting/live-collaboration.md)
- [Platform Concepts](crosscutting/platform-concepts.md) (auth, ML, i18n, robustness, capabilities)
- [Patterns & Conventions](crosscutting/patterns-and-conventions.md)

## Features (vertical slices, with use-cases)

- [Memory](features/memory/_index.md) · [Uploads](features/uploads/_index.md) ·
  [Journal](features/journal/_index.md) · [Sharing](features/sharing/_index.md) ·
  [People](features/people/_index.md) · [Search](features/search/_index.md) ·
  [Spaces](features/spaces/_index.md) · [Account & Auth](features/account/_index.md) ·
  [Administration & Operations](features/admin/_index.md) · [Mobile](features/mobile/_index.md)

## Decisions

- [Architecture Decisions (ADR log)](decisions/README.md)

## Companion

- [Actors](actors.md) — used by use-cases
- [Design Notes & Open Questions](design-notes.md)
- **AI working methodology** (Spielregeln for agents) lives in `docs/ai/` in the repo and is **not**
  part of this wiki.

## Documentation landscape

The **repo is the single source of truth**; the wiki and website are published views.

| Track | Audience | Home (repo) | Published as |
|---|---|---|---|
| **Architecture** (this wiki) | developers, architects, AI | `docs/wiki/` | GitHub Wiki (auto-synced) |
| **AI working methodology** | AI agents | `docs/ai/` | — (repo-only) |
| **Operator runbook** (install, env, backup, upgrade, agent, troubleshooting) | self-hosters, SaaS ops | `docs/` (versioned with the code) | optional website "self-host docs" |
| **End-user help** | Memory Curators, guests | in-product first | website Help / FAQ |
| **API reference** | our own clients | `docs/api` (OpenAPI) | Redoc on GitHub Pages |

Boundaries: the **operator runbook** is procedural and *version-locked* → repo `docs/`, not this
always-latest wiki. **End-user help** is mostly in-product (the use-cases are its source skeleton) and
does not belong here. **SaaS-internal ops** stay internal, not on the public site.

## Conventions

- Language: **English**.
- Diagrams: **all PlantUML** (diagrams-as-code), written **inline** as `plantuml` fenced blocks. The
  wiki pipeline renders them to SVG when publishing (GitHub does not render PlantUML natively).
- API: the OpenAPI contracts in `docs/api` are **internal**; a rendered reference is linked from
  [System Architecture](overview/architecture.md).
