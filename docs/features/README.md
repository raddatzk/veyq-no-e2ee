# Features

Product capabilities. Each feature is a folder with an overview (`_index.md`) and its **use-cases**
underneath. The use-case is the unit of the trust chain *and* the single source for the end-user wiki.

```
features/<feature>/
  _index.md                         # feature overview, scope, cross-cutting links
  use-cases/uc.<feature>.<goal>.md  # one concrete flow each
```

## Use-case format (Cockburn-lite)

```
# UC: <Title>
- ID: uc.<feature>.<goal>
- Primary actor: <actor>
- Secondary actor: <optional>
- Goal: <one sentence>
- Preconditions: …
- Postconditions (success): …

## Main flow
1. …

## Alternate / exception flows
- A1: … / E1: …

## Quality notes        (optional — invariants/threats this flow must respect)

## What this means for you / Was das für dich bedeutet
Plain-language, bilingual (EN + DE). The end-user wiki is DERIVED from these sections
(+ the standalone safety pages) — do not maintain a parallel wiki page by hand.
Omit only for purely internal use-cases with no end-user-visible effect.

## Related
Actors, cross-cutting concepts, decisions (ADRs), related use-cases.
```

The bilingual section is the **single source** behind `wiki/` (a Python generator assembles the wiki
from it later — deferred under start-light; author the section now so it's free then).

**Diagrams.** Illustrate flows with inline PlantUML where it clarifies — typically a **sequence
diagram** for the main flow and a **state machine** for lifecycle/state, as diagrams-as-code (see
[`docs/ai`](../ai/README.md)). Validate with `plantuml -checkonly` before considering a doc done.
