# Change requests (temporary)

A change-request (CR) is the **self-contained work order** for an implementation. It is derived
from refined state and is **temporary**: deleted once done. The durable trace of what changed
lives in git history + the updated `docs/` state + (for decisions) ADRs. CRs are **not**
documentation.

- One file per CR: `cr-NNNN-slug.md`.
- **Self-contained as a work order:** it *links* to the durable truth (use-cases, contracts,
  crosscutting concerns, ADRs) and *contains* the delta + plan + acceptance. Never copy state docs
  into a CR — if you feel the urge, the state doc is what should be updated instead.
- **Phases only above real size.** A small CR is single-phase. Each phase ends with the repo in a
  working, verifiable state (its own "Done when:"). The natural seam is the dependency order:
  contracts → backend → web → apple → android.
- **Genuinely independent work = separate CRs, not phases.**

## Template

```markdown
---
id: cr-0001
title: <short title>
status: planned          # planned → in-progress → done (then delete the file)
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

## Definition of done
Acceptance for the whole CR (coverage for `satisfies:` green across the listed platforms).
```
