# Documentation Guide

How to author and maintain veyq's documentation.

## Documentation landscape (where things go)

The **repo is the single source of truth**; the wiki and website are published views. Put new
content in the right track:

| Track | Write it in | Published as |
|---|---|---|
| **Architecture** (arc42) | `docs/wiki/` | GitHub Wiki (auto-synced) |
| **AI working methodology** (these rules) | `docs/ai/` | — (repo-only, never synced) |
| **Operator runbook** (install, env, backup, upgrade, agent, troubleshooting) | `docs/` (versioned with code) | optional website "self-host docs" |
| **End-user help** | in-product first; help text/FAQ | website Help / FAQ |
| **API reference** | `docs/api` (OpenAPI) | Redoc on GitHub Pages |

Routing rules:
- Architecture *facts/decisions* → `docs/wiki/` (arc42). *How-to-work* rules → `docs/ai/`.
- **Deployment architecture** (topology, why) → arc42 **§7** in the wiki. **Procedural,
  version-locked ops** (the runbook) → `docs/`, with the code — not the always-latest wiki.
- **End-user help** is mostly in-product; the use-cases are its source skeleton. Do **not** put
  end-user help in the arc42 wiki.
- **SaaS-internal ops** (T&S/abuse handling, scaling, on-call) stay internal, not on the public site.
- Language is **English** everywhere.

## Structure (feature-first)

`docs/wiki/` is organized **feature-first**, with arc42 kept as a *coverage checklist* (not a rigid
chapter order):
- `overview/` — system-wide foundation (architecture, goals/quality, constraints & strategy,
  deployment, risks, glossary)
- `crosscutting/` — shared concepts (security model, agent mode, search, live collaboration, platform,
  patterns) — **one source**, referenced by features and **never copied** into them
- `features/<feature>/` — `_index.md` + `use-cases/` (vertical slices)
- `decisions/` — the ADR log; plus `actors.md` and `design-notes.md`

A feature index composes the shared building blocks and cross-cutting concepts **by link**. A
use-case is a self-contained vertical slice that links out for everything shared.

## Diagrams

All diagrams are **PlantUML** (diagrams-as-code) — one language, no mix. Write them **inline** in
the chapter as a fenced block (give each a `title` — it becomes the image alt text):

````markdown
```plantuml
@startuml
title <caption>
... diagram ...
@enduml
```
````

PlantUML does **not** render natively on GitHub. The wiki pipeline renders it: the **wiki-sync**
workflow runs `scripts/render-wiki-diagrams.mjs`, which — on the published wiki copy only — replaces
each `plantuml` block with a rendered SVG. In the repository the block stays visible as source.
Preview locally with `scripts/render-diagrams.sh` (needs a `plantuml` command).

## Use-cases (Cockburn-lite, vertical slice)

One file per use-case under `docs/wiki/features/<feature>/use-cases/uc.<feature>.<goal>.md`.
**Filename == ID.** A use-case is **self-contained**: it owns its flow, its diagram (when one helps)
and its quality notes, and **links** to shared concerns rather than copying them. Required structure
(validated by `scripts/validate-usecases.mjs`):

```markdown
# UC: <Title>

- **ID:** uc.<feature>.<goal>
- **Primary actor:** <Actor>   (must exist in actors.md)
- **Secondary actor:** <Actor> (optional — other actors the flow involves)
- **Goal:** <one sentence>
- **Preconditions:** <…>
- **Postconditions (success):** <…>

## Diagram            (optional — a functional flow when it shows branching/parallelism/state)
## Main flow
1. …
## Alternate / exception flows
- A1: …
- E1: …
## Quality notes      (optional — local projection of quality scenarios)
## Related            (the actors actually involved — named, linked to actors.md — plus links: feature, cross-cutting concepts, decisions, related use-cases)
```

Add a diagram only when it shows something text cannot (branching/parallelism/state) — not to restate
the numbered steps. Features: `memory`, `uploads`, `sharing`, `spaces`, `account`, `admin`, `mobile`.
Actors are defined once in [`actors.md`](../wiki/actors.md); the `Related` line names the actors that
use-case actually involves (not a bare link to the file). Constraints live in preconditions; a Kano
tag + priority may be added per use-case.

## Validation

- `node scripts/validate-usecases.mjs` — every use-case has the required sections, a known
  `primaryActor`, an ID matching its filename, and unique IDs.
- `node scripts/validate-feature-coverage.mjs` — the existing feature/test trust chain.

## Wiki publishing

`.github/workflows/wiki-sync.yml` publishes `docs/wiki/**` to the GitHub Wiki on push to `main`
(rendering inline PlantUML to SVG along the way). `docs/ai/**` is repo-only and never published. The
repo is the single source of truth — **edit the repo, not the wiki**.
