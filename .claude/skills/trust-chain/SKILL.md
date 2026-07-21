---
name: trust-chain
description: >-
  Use before asserting any doc, coverage, contract, or CR change is sound; when adding or modifying a
  scripts/ guard; or when a green trust-chain is about to be used as evidence for a claim. Carries the
  claim-provenance convention (mark every claim measured vs. remembered) and the map of what a green
  trust-chain does — and does not — prove. Entry point into veyq's doc↔code validation; per-guard detail
  lives in each scripts/validate_*.py docstring, the mutation-testing deep-dive in ADR 0045.
---

# trust-chain

The doc↔code validation layer, as an entry point. This skill is **not** the guards' documentation — each
guard's own module docstring owns what it asserts and the defect that bought it (`scripts/` is the list,
per [`changes/README.md`](../../../changes/README.md) § trust-chain guards). This skill adds only what no
single file holds: the **one convention** that governs every claim, and the **map of the chain's edges** —
what green proves and where it stops.

## When this fires

- You are about to write "the docs are correct / coverage is green / this change is sound" — anywhere (a
  report, a CR, a handoff, a commit body).
- You add or change a `scripts/validate_*.py` guard, or lean on one as proof.
- You are reading a green build as evidence for anything.

## The one rule that travels everywhere: claim-provenance

**Every claim in a report, CR, or handoff is marked `measured` or `remembered`. A `measured` claim names
the command that proves it. A "measured" claim with no command is `remembered` wearing a command's clothes.**

This is the bite-proof idea ([`changes/README.md`](../../../changes/README.md) § bite-proof a guard) applied
to prose instead of tests: a claim you cannot re-run is a claim nobody can bite.

- **measured** — `measured (grep -rc '@Test' backend/…/FooTest.kt → 3): the slice has three cases`. The
  reader re-runs, not re-trusts. The command is the part that stays true while the code drifts; the number
  is spent the moment the go/no-go call is made ([`changes/README.md`](../../../changes/README.md) § the CR
  carries the locator).
- **remembered** — `remembered: coverage for uc.X looked complete last session`. Legitimate as a lead;
  never as a settled fact, and never the basis of an action. Before it drives a decision, measure it — that
  *is* the first step of any trust-chain task.

Why this convention and not a "be careful" plea: it was bought by measured failures. A remembered coverage
claim ("Trap 5") measured false; handoff notes three commits behind the git state; a full analysis written
over already-deleted Android code; an audit finding recommended still-open that had been deleted with its
surface. Every one was a *remembered* claim a one-line command would have refuted. The tell is the feeling
of already knowing ([`changes/README.md`](../../../changes/README.md) § upstream of every trap) — that is
exactly where the imported prior sits, and where this repo most often inverts it. Rationale in
[ADR 0046](../../../docs/decisions/0046-operative-lore-in-skills-and-agents.md) § 4.

**Delegate the measuring, but keep it measured.** When a claim needs reading across many files (a surface
sweep, "where does X live", "is Y consistent"), delegate the read to a **read-only `Explore` subagent** so
the main context stays lean — and require it to **return each finding measured: the command it ran and that
command's output**, not a prose conclusion. A subagent that hands back "coverage looks complete" has
laundered a `measured` claim into a `remembered` one across the delegation boundary; you inherit its
confidence without its evidence. Ask for the ledger, not the verdict — then the delegation costs context
without costing provenance.

**This skill obeys its own rule.** Every factual claim below is a pointer to its canonical home, or is
marked `measured` and carries the `file:line` it was read from — re-runnable, not recalled. Nothing here is
remembered.

## What green proves — and where it stops

**Green means: nothing points into the void. It does not mean: what it points at carries weight.**
`measured` (per-file read of all ten `validate_*.py` + both `mutation_meta_check*.py`):
every guard asserts a reference **resolves / exists / is present / count > 0** — never the substance of what
it references. The per-guard limit is each validator's own docstring (e.g. `validate_field_visibility.py`:
"a marker permits, it does not verify"); this skill only names the pattern across all of them.

Concrete, `measured` (judged from the guard code — re-confirming the prior sessions'
bite-tests by reading, not re-injecting):

- a **web** coverage ref at a *non-test* file passes — `validate_coverage.py` only checks the path exists;
- a **backend** ref at an *empty `fun` body* passes — only the symbol name is resolved, never the body;
- a CR marked `status: done` with no implementation passes — `validate_changes.py` checks the enum + that
  refs resolve, nothing more;
- a `#fragment` anchor is stripped, never verified (`validate_doc_links.py:35`);
- a coverage file's `status: done` is *rejected*, but a **lying `status: planned`** on an implemented
  use-case passes (`validate_coverage.py:132-134,:159`) — the asymmetry is real, don't rely on it;
- `mutation_meta_check.py` (crypto) asserts a **total** mutant count > 0 (`:53-55`); the **backend** one is
  **per-glob** (`mutation_meta_check_backend.py:54-63`). The remembered "total-count" claim held for the
  crypto check only — corrected here.

### The four edges are principled limits, not gaps to fill

Adding more validators is **explicitly not the goal**. The chain has four ends no `validate_*.py` can close;
each has a canonical home — point there, don't try to build past it:

1. **Oracle correctness** — a guard never proves a test's assertion proves anything (`fun seal(k,s)=s`
   passes coverage). [ADR 0045](../../../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md)
   closes this on the *unit layer* via mutation score; nowhere else.
2. **Timing** — a test counts only when CI runs it; a ref to a skipped/gated test resolves but proves
   nothing at green. [ADR 0040](../../../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) +
   [`ai/README.md`](../../../docs/ai/README.md) § what counts as verified.
3. **Cross-feature** — when a rule was *never written*, the doc↔code delta is empty and the defect is still
   real (trash × sharing served trashed media). [`ai/README.md`](../../../docs/ai/README.md) § the pipeline,
   "what this step cannot see".
4. **Scope-completeness** — a guard sees only the present tree, never history: a cited ADR that was deleted
   (inbox #95), a CR number burned by a since-deleted CR (inbox #107). Green means nothing *currently*
   dangles. Reuse-checks must grep the working tree, not `git log`.

## The map (assembled from nowhere single)

`measured`:

- **Three CI runs, three triggers.** `trust-chain.yml` — unfiltered, buildless, runs the whole guard glob
  (`:14-21`, no paths filter). `ci.yml` — `paths-ignore` includes `docs/**` (`:9-13`, `:19-23`), so a
  docs-only change is validated *only* by trust-chain. `mutation.yml` / `mutation-backend.yml` — path-gated
  onto their scope files (`:16-43` / `:17-51`).
- **The glob is the registry.** `trust-chain.yml:47-60` runs `for f in scripts/validate_*.py` with a
  `count == 0` backstop — a new `validate_*.py` runs automatically, no registration. The
  `mutation_meta_check*.py` sit **outside** that glob (filename prefix) and run only from the mutation jobs.
  Run every guard locally the same way: `for f in scripts/validate_*.py; do python3 "$f"; done`.
- **The one coupling that bites differently per surface:** **web coverage refs are path-bound, backend refs
  are symbol-bound** (`validate_coverage.py`: web `(WEB/ref).exists()` :101-103; backend `ClassName.method`
  via the `KOTLIN_FUN` regex + stem map :75,:86-99). The same move hits them differently — relocating a file
  breaks web refs; renaming a symbol breaks backend refs. This is what broke in cr-0175; carry it into any
  relocation's blast-radius grep.

## Procedures — pointers, not restatements

- **Run the guards:** `for f in scripts/validate_*.py; do python3 "$f"; done` (the CI glob, unfiltered).
  A red guard means a CR is not done ([`changes/README.md`](../../../changes/README.md) § trust-chain guards).
- **Bite-proof a new/changed guard:** the snapshot-and-restore protocol (never `git checkout`) lives in
  [`changes/README.md`](../../../changes/README.md) § bite-proof a guard — follow it there. Bite-test a
  guard *before* trusting its design: write the case that must make it FAIL first.
- **Coverage is part of the CR, not a follow-up:** [`changes/README.md`](../../../changes/README.md) §
  coverage is part of the work; the rule underneath is [ADR 0040](../../../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md).
- **Promote a one-shot proof to a permanent guard** when a CR rests on a structural invariant:
  [`changes/README.md`](../../../changes/README.md) § promote one-shot proofs. A new `validate_*.py` joins
  the glob for free — that is the whole point of the glob.

## Canonical sources (this skill restates none of them)

- [`docs/ai/README.md`](../../../docs/ai/README.md) — the workflow + coverage rules + what counts as verified.
- [`changes/README.md`](../../../changes/README.md) — glob-is-registry, bite-proof, the locator rules.
- [ADR 0040](../../../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) — a test counts only when CI runs it.
- [ADR 0045](../../../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md) — the assertion-strength last link.
- [ADR 0046](../../../docs/decisions/0046-operative-lore-in-skills-and-agents.md) — why this skill exists at all.
- Each `scripts/validate_*.py` module docstring — what that one guard asserts, and the defect that bought it.
