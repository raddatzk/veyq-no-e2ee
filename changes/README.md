# Change requests

A change-request (CR) is the **self-contained work order** for an implementation. It is derived
from refined state. **CRs are kept as a record** (not deleted when done) — they form a readable log
of what each piece of work delivered. The durable *engineering* truth still lives in the updated
`docs/` state + (for decisions) ADRs + git history; CRs are the work-order trail alongside it, not a
substitute for state docs.

- One file per CR: `cr-NNNN-slug.md`.
- **Self-contained as a work order:** it *links* to the durable truth (use-cases, contracts,
  crosscutting concerns, ADRs) and *contains* the delta + plan + acceptance. Never copy state docs
  into a CR — if you feel the urge, the state doc is what should be updated instead.
- **Phases only above real size.** A small CR is single-phase. Each phase ends with the repo in a
  working, verifiable state (its own "Done when:"). The natural seam is the dependency order:
  contracts → backend → web → apple → android.
- **Genuinely independent work = separate CRs, not phases.**
- **Coverage is part of the work, not a follow-up.** Every CR updates `docs/coverage/` for the
  use-case(s) it `satisfies:` — each new acceptance criterion gets a test ref (or an explicit `gaps:`
  entry with a reason) — and leaves `python3 scripts/validate_coverage.py` **green**. A CR is not done
  while the trust chain is red. (A use-case refined ahead of any implementation ships a `status: planned`
  coverage file that gaps every target surface.)
- **Every CR closes with a short, honest retrospective.** A `## Retrospective` section: what went wrong
  in *planning* and in *execution* (not what went right), the root cause, and one concrete change that
  would have prevented it. Criticism flows **both ways** — the assistant's misses *and* the human's
  direction (a premature instruction, a missing constraint, a process gap) are both fair game and
  explicitly wanted. Two or three bullets is enough; the goal is a compounding record of process lessons,
  not ceremony. If genuinely nothing went wrong, say so in one line — don't manufacture a fault.

## Template

```markdown
---
id: cr-0001
title: <short title>
status: planned          # planned → in-progress → done (the file is kept as a record)
satisfies: []            # use-case ids this fulfills (trust-chain link)
adrs: []                 # related ADR ids
invariants: []           # hard rules this work could threaten
---

## Goal
One paragraph, readable cold — no chat context required.

## Context & constraints
Links (not copies) to the durable truth this must honor: use-case(s), contract file(s),
crosscutting concern(s), ADR(s), and the specific invariants at risk.

## Phases
- [ ] 1 contracts — <scope> · Done when: <verifiable checkpoint>
- [ ] 2 backend  — <scope> · Done when: <verify command green>
- [ ] 3 web      — <scope> · Done when: <verify command green>
- [ ] N coverage — docs/coverage YAML(s) for each `satisfies:` use-case + any http/ acceptance ·
      Done when: `python3 scripts/validate_coverage.py` green

## Definition of done
Tests green across the listed platforms, AND `docs/coverage/` updated for each `satisfies:` use-case
(every new acceptance criterion covered by a test or an explicit gap) with
`python3 scripts/validate_coverage.py` green.

## Retrospective
What didn't go well — in planning and execution — root cause, and the concrete fix. Both directions
(assistant misses + human direction). 2–3 bullets, or one line if nothing did.
```
