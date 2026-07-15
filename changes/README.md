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
- **A checkable claim carries the command that produced it — run it, don't estimate.** A CR's inventory/plan
  states code-confirmable facts — a symbol, a count, a path, "all N of X do Y" — as the **output of a command
  written into the CR** (`grep -rc … → 4`), never a guess. This is a *format* requirement, not a "be careful"
  plea (which broke nine times, e.g. cr-0162's phantom `tasks.createJob`) — a cited command is re-runnable, and
  in this repo's history every bare estimate was an under-count. Two traps: a null result counts only once you've
  confirmed the search hit the right place, and match the **marker**, not a hand-rolled regex (`grep -c '@Test'`,
  not `fun \w+`). And still: **point at a canonical source, don't restate it** (cr-0044's phantom `/api/token`).
- **Coverage is part of the work, not a follow-up.** Every CR updates `docs/coverage/` for the
  use-case(s) it `satisfies:` — each new acceptance criterion gets a test ref (or an explicit `gaps:`
  entry with a reason) — and leaves `python3 scripts/validate_coverage.py` **green**. A CR is not done
  while the trust chain is red. (A use-case refined ahead of any implementation ships a `status: planned`
  coverage file that gaps every target surface.)
- **The trust-chain guards stay green.** *Every* deterministic check in `scripts/` gates a CR — all of them, not
  a remembered subset: `for f in scripts/validate_*.py; do python3 "$f"; done`. The glob is the point: it cannot
  go stale. What each guard asserts is enumerated canonically by the `trust-chain` job in
  [`../.github/workflows/ci.yml`](../.github/workflows/ci.yml); this line points there rather than restating it,
  because a list restated in prose drifts out of step with the one that runs — this file's own *"point at a
  canonical source, don't restate it"*, applied to itself. A red guard means the CR isn't done.
- **Promote one-shot proofs to permanent guards.** When a CR's correctness rests on a structural invariant it
  proves with a one-shot check — a `grep` that "X is no longer duplicated", "Y lives only in `shared/`" — that
  check is added as a **permanent guard** (a `scripts/` validator step or CI grep) so the invariant can't
  silently regress. The tell that this was needed: an audit finding for a rule that already existed *in prose* but
  nothing enforced (e.g. the styleguide mandated `@ConfigMapping`, yet 28 sites drifted). If a one-shot check
  genuinely suffices, the CR says why.
- **Bite-proof a guard by snapshot-and-restore, never `git checkout`.** To prove a new test bites, revert the
  fix and watch it red — the revert must be exact and reversible. Protocol: `cp <file> /tmp/snap` **before** the
  injection, break the code, run the one test, then `command cp -f /tmp/snap <file>` (the `command` prefix skips
  any interactive `cp -i` alias). **Never `git checkout -- <file>` to restore** — it resets to HEAD and silently
  discards *uncommitted* work in that file; `git checkout` is safe only when the baseline is committed. Confirm
  the restore (grep the fix marker) before moving on.
- **Every CR closes with a short, honest retrospective.** A `## Retrospective` section: what went wrong
  in *planning* and in *execution* (not what went right), the root cause, and one concrete change that
  would have prevented it. Criticism flows **both ways** — the assistant's misses *and* the human's
  direction (a premature instruction, a missing constraint, a process gap) are both fair game and
  explicitly wanted. Cover **process & collaboration**, not just the technical work — in particular,
  did we honor the conventions and feedback we already agreed (language, style, workflow rules, the
  memory notes)? A rule that already exists but was ignored is a *real* miss, not a non-event. Each
  retro ends with a forward-looking **"next time"** — one concrete, actionable thing to do differently.
  Two or three bullets is enough; the goal is a compounding record of process lessons, not ceremony.
  If genuinely nothing went wrong, say so in one line — don't manufacture a fault. **The process/collaboration
  part is also delivered *actively* to the user at implement-session close** — spoken in the conversation, not
  only written into this section (see the closing process retro in [`docs/ai/README.md`](../docs/ai/README.md)).

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
- [ ] N coverage — docs/coverage YAML(s) for each `satisfies:` use-case ·
      Done when: `python3 scripts/validate_coverage.py` green

## Definition of done
Tests green across the listed platforms, AND `docs/coverage/` updated for each `satisfies:` use-case
(every new acceptance criterion covered by a test or an explicit gap) with
`python3 scripts/validate_coverage.py` green. If the CR rests on a structural invariant, its proof is
promoted to a permanent guard (or the CR notes why a one-shot check suffices).

## Retrospective
What didn't go well — in planning, execution, AND process/collaboration (did we honor the conventions
+ feedback we already agreed — language, style, workflow, the memory notes?) — root cause, and the
concrete fix. Both directions (assistant misses + human direction). End with a forward-looking "next
time": one concrete, actionable change. 2–3 bullets, or one line if nothing did.
```
