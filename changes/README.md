# Change requests

A change-request (CR) is the **self-contained work order** for an implementation, derived from refined state.
**A CR is deleted when it is done.** It is scaffolding: consumed the moment the work lands.

This reverses a 2026-06-25 convention change that kept them "as a readable log". By 2026-07-14 that log was
**168 files / 18,016 lines** — larger than all of `docs/`, larger than the backend — and it had no reader:
the owner does not read `docs/` at all, nobody judges a project by reading 168 work orders, and an agent reads
the *current* CR plus recent retrospectives. A log nobody reads is not a record, it is a cost (§ What the docs
are for, in [`../docs/ai/README.md`](../docs/ai/README.md)).

Nothing durable dies with it, because nothing durable was ever *only* there:
- **What was built** → the updated `docs/` state; **what was decided** → an ADR. If a CR holds a decision that
  is in neither, that is the bug — fix it there, then delete the CR.
- **What was learned** moves to where it bites, not into an archive. A code trap becomes a comment on the line
  that trips over it (cr-0089's post-rotation zeroed-key trap lives in `web/src/app/activeSpace.ts`, three
  lines from the code that caused it); a working lesson becomes a rule in this file. Both already happened —
  the retrospectives were the ore, not the metal.
- **Do not offload it to git.** The old pre-2026-06-25 rule said "the durable trace lives in git history",
  which is exactly the assumption that breaks at the v1 repo move. Harvest *before* deleting, into the tree.

So: no `changes/cr-*` archive. `changes/` holds this file and any live review/finding backlog.

- One file per CR: `cr-NNNN-slug.md`.
- **Self-contained as a work order:** it *links* to the durable truth (use-cases, contracts,
  crosscutting concerns, ADRs) and *contains* the delta + plan + acceptance. Never copy state docs
  into a CR — if you feel the urge, the state doc is what should be updated instead.
- **Phases only above real size.** A small CR is single-phase. Each phase ends with the repo in a
  working, verifiable state (its own "Done when:"). The natural seam is the dependency order:
  contracts → backend → web.
- **Genuinely independent work = separate CRs, not phases.**
- **The CR carries the locator, not the number — run it, don't estimate, don't freeze.** A CR's inventory states
  code-confirmable facts (a symbol, a path, "all of X do Y") as a **command written into the CR that finds the
  sites**, never a guess and never a frozen tally. This is a *format* requirement, not a "be careful" plea (which
  broke nine times, e.g. cr-0162's phantom `tasks.createJob`): in this repo's history every bare estimate was an
  under-count. Three reasons the number itself does not belong in the file:
  - **A count rots; a locator re-runs.** The code drifts between refine and implement, and a stale `→ 4` reads
    as current. The command is the only part that stays true, so it is the part that ships.
  - **The number's one real job happens before the CR exists.** *"Is this worth doing at all?"* is decided in the
    refine conversation, looking at the output. Once that call is made the tally has no further reader — the
    implementer needs the **sites**, and the CR already says implementation discovers the exact *where*.
  - **Where the CR builds a guard, the guard is the completeness proof** — infinitely stronger than a tally in
    prose. cr-0171 could not under-deliver on 235 fields: the validator stays red while one is missing. Prefer
    that over a number every time (see *promote one-shot proofs*, below).
  **Find the sites from two angles keyed on different things — the easiest command encodes what you already
  believe.** The locator you reach for first is a paraphrase of your own model of the code, so it fails exactly
  where that model is wrong: silently, and with a plausible number. Ask a second time on a **different key** — a
  different marker, a different tree, the opposite direction — and compare. **The disagreement is the finding**,
  and it names the sites rather than merely raising doubt. cr-0171's refine asked the container's *declaration*
  (`type == "array"` → 49) where the reliable key is its *target* (`$ref` anywhere in the property → 60); the
  11-property gap was the entire miscount, and it listed every offender. This is the only rule here that works
  **without foreknowledge** — *match the marker, not a paraphrase* (below) assumes you already know which is
  which, and not knowing is the whole miss. Two conditions:
  - **Agreement is worth exactly what the independence is worth.** Two angles that share a blind spot agree, and
    the agreement then reads as confirmation — worse than a single angle, because it manufactures confidence.
    `grep -c` against `grep -rc` is one angle twice: cost paid, nothing bought.
  - **Spend it where the number decides something** — a go/no-go call, or a predicate that *is* the CR's own rule
    ("what counts as a container" was ADR 0044's decision, measured by a probe that quietly re-implemented it).
    Not on every grep: this repo dies of ceremony faster than of arithmetic.

  **Emit the list, not the tally.** A number cannot look wrong; a list can. Printing the items behind cr-0171's
  "246 scalars" puts `derivatives: ['array','null'] items={$ref}` in front of you and the fault is unmissable.
  Cheaper than a second angle and it catches a wrong *predicate* — but not a wrong *scope*: a list of the sites
  you searched can never reveal the tree you didn't (cr-0160's "~8 imports" were 2 **and 22 test files**). The
  two defenses cover different failures; neither replaces the other.

  A cited command is re-runnable, which is the point; it is not automatically right, and **a locator is only as
  true as its predicate** — over a structured format, enumerate the format's spellings, not the one in front of
  you, and trace the predicate to the *rule's own words* rather than to a paraphrase of them. Three further
  traps: a null result counts only once you've confirmed the search hit the right place; a **truncated** result
  is not a null result (cr-0171 piped an inline-YAML probe through `head -10`, read the empty tail as "there are
  none", and broke three specs on the assumption); and match the **marker**, not a hand-rolled regex
  (`grep -c '@Test'`, not `fun \w+`). And still: **point at a canonical source, don't restate it** (cr-0044's
  phantom `/api/token`).
- **Coverage is part of the work, not a follow-up.** Every CR updates `docs/coverage/` for the
  use-case(s) it `satisfies:` — each new acceptance criterion gets a test ref (or an explicit `gaps:`
  entry with a reason) — and leaves `python3 scripts/validate_coverage.py` **green**. A CR is not done
  while the trust chain is red. (A use-case refined ahead of any implementation ships a `status: planned`
  coverage file that gaps every target surface.)
- **The trust-chain guards stay green.** *Every* deterministic check in `scripts/` gates a CR — all of them, not
  a remembered subset: `for f in scripts/validate_*.py; do python3 "$f"; done`. The glob is the point: it cannot
  go stale. CI runs that same glob, unfiltered, in
  [`../.github/workflows/trust-chain.yml`](../.github/workflows/trust-chain.yml). What each guard asserts — and
  the defect that bought it — is that validator's own module docstring: **`scripts/` is the list**, and no file
  restates it, because a restated list drifts out of step with the one that runs. This file's own *"point at a
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
status: planned          # planned → in-progress → done (then harvest what binds, and delete the file)
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
