---
id: cr-0169
title: Guard the diagrams-as-code rule — `validate_plantuml.py`, the 10th trust-chain validator
status: superseded
satisfies: []            # dev-tooling guard over a working rule; no uc./en./cc. target (cf. cr-0167)
adrs: []                 # no ADR governs diagrams; the rule's home is docs/ai/README.md (see Context)
invariants: []           # no product code, no contract, no behaviour — a docs guard + record hygiene
---

> **Superseded the same day it was built — the guard was deleted, the problem was solved by format.**
> `validate_plantuml.py` and its CI steps (a JRE + a pinned 29 MB jar) are **gone**. All 41 diagrams moved to
> **Mermaid**, which GitHub renders — so a broken block now shows as a visible render error to the audience
> `docs/` is *for*, and needs no parser in CI. What survives from this CR is only the record hygiene:
> `changes/README.md` + `docs/README.md` point at `ci.yml` instead of restating a stale count.
>
> **What it got wrong is the part worth keeping.** This CR asked "how do we enforce the rule?" and never asked
> **"what did the defect cost?"** — three diagrams failed to render for 13 days, and the observed cost was
> **zero**, because no reader ever reached them. It sold those 13 days as proof of urgency; they were proof
> that nobody looks. A machine cannot ask *"do we need this?"* — that question is the one thing only the human
> in the loop can supply, and this CR was refined and implemented without ever putting it to them.
>
> Everything below is the original work order, kept as-is for that lesson. Its technical findings held up
> (the exit-code table, the batch-then-attribute trade); its premise did not.

## Goal

`docs/ai/README.md` mandates diagrams-as-code ("Diagrams as inline PlantUML … Diagrams-as-code only"), and
nothing enforces it. The cost is measured, not hypothetical: all **three** PlantUML diagrams in
`docs/architecture/README.md` had **never rendered** — the single-line `skinparam rectangle { … }` form, which
PlantUML rejects, present since that file's first commit (`c43e1419`, 2026-07-01) and unnoticed until
2026-07-14 (`dbbbae61`). Thirteen days and several doc sweeps passed over a file whose diagrams could not be
displayed, because the breakage is invisible in the markdown source: a broken diagram reads exactly like a
working one until something renders it.

This CR promotes the one-shot proof that found it (`plantuml -checkonly` over every diagram doc) into a
permanent guard, registers it in the only complete validator registry (`.github/workflows/ci.yml`), and
repairs the two registries that already misreport how many guards exist.

## Context & constraints

- **The rule** lives at `docs/ai/README.md` § Working rules ("Diagrams as inline PlantUML"). It is **prose**,
  and prose is what drifted. No ADR governs diagrams — hence `adrs: []`. The rule's home is that working rule,
  not a decision record; this CR does not invent one.
- **The trigger** is `docs/ai/README.md` § When to add a tool → *"an invariant guard | a CR removes a
  duplication or establishes 'X only in Y' — promote its one-shot proof grep to a permanent check"*, and its
  sharpened form in `changes/README.md` § Promote one-shot proofs: *"The tell that this was needed: an audit
  finding for a rule that already existed **in prose** but nothing enforced (e.g. the styleguide mandated
  `@ConfigMapping`, yet 28 sites drifted)."* That tell is met literally.
- **Not the rule of three.** The rule of three counts *repetitions of a manual action*. `plantuml -checkonly`
  has been run once — not because the need arose once, but because **nobody knew to run it**; that is why the
  breakage survived 13 days. Repetitions of an action nobody performs cannot be counted. The rule of three
  measures friction from a task you keep doing; this is the inverse failure — a check nobody does at all,
  whose absence is invisible. The invariant-guard trigger is the one that applies.
- **Pattern precedent:** [ADR 0038](../docs/decisions/0038-contracts-shared-components-bundling.md) — *"kept
  honest by a drift guard (not a bundler)"* — is the same shape: a rule that cannot be enforced structurally,
  kept honest by a check instead. Cited as precedent for the shape, **not** as governing this CR.
- **Conventions this must follow** (from the existing 9, strongest first):
  - *"Nothing to check → red, never skip."* An absent anchor means the guard has been silently disarmed
    (`validate_webfirst_authority.py:112-116` — *"this guard's anchor is gone"*;
    `validate_http_failure_projection.py:55-58`). There is no "0 = nothing to check" convention.
  - `main() -> int` + `sys.exit(main())` + `from __future__ import annotations` — the shape of every validator
    added since 2026-07-12. No argparse, no `sys.argv`.
  - `ROOT = pathlib.Path(__file__).resolve().parent.parent`; module-global `errors: list[str] = []`;
    collect-all, never fail-fast.
  - Four-part module docstring: purpose **+ provenance (this CR)** · why prose can't hold this · what it
    asserts · **what it deliberately does NOT assert, and the accepted cost**.
  - Output: stdout only. `PLANTUML VALIDATION FAILED:` then `  - <msg>` lines. A **counted** success line
    (`plantuml OK: 41 diagram(s) across 32 docs`). Paths always ROOT-relative. Messages prescriptive.
- **What is novel here:** `grep -rn 'subprocess\|shutil' scripts/` → **0**. No validator shells out to an
  external binary today, and the `trust-chain` CI job is a bare `ubuntu-latest` installing only PyYAML
  (`ci.yml:81` — *"PyYAML is the only dep"*). This guard is the first of its kind on both counts.

### Explicitly out of this CR

`cr-0168` decision 7 — that `ci.yml`'s `paths-ignore` (`**/*.md` + `docs/**`, with three negation escapes
pointing at paths that do not exist: `ls -d docs/api docs/wiki docs/product` → 3× *No such file or directory*)
means **a docs-only PR runs no CI at all**. That is real, it hits **all** the docs guards equally (not this
one specially), and it may deserve its own ADR. It does **not** gate this guard: the primary venue is the
per-CR hand-run (`changes/README.md` § trust-chain guards; cr-0166/0167/0168 each demanded "All 9 `scripts/`
validators green" in their own Verify). Do not let it swallow this CR.

## Decisions

1. **The guard is added.** Justified by the invariant-guard trigger + the `changes/README.md` tell, not by the
   rule of three (see Context).
2. **It runs as a `scripts/` validator *and* is registered in CI.** The hand-run per CR is where it fires; the
   CI entry keeps `ci.yml:82-90`/`:95-103` — the only complete registry — honest. A 10th guard outside that
   registry would be the first exception and would be the one nobody remembers when decision 7 is settled.
   **Not a pre-commit hook yet:** doctrine sequences the hook *after* ("a hook | a validator/formatter exists
   and we've run it manually a few times"). Revisit once this has run manually a few times.
3. **Missing `plantuml` binary → red, with a prescriptive install message.** Per the "nothing to check → red"
   convention: `shutil.which("plantuml") is None` is an anchor-gone condition, not a skip. **The accepted
   price, stated plainly:** `plantuml` becomes a hard dependency of the trust chain, which today needs only
   `python3` + PyYAML. A `which → skip` was rejected because it is exactly the disarmed-guard mode the other
   guards exist to prevent — the guard would vanish silently precisely on the machine that lacks the tool.
4. **Filter on `@startuml`, not the ```` ```plantuml ```` fence.** Fence → 33 files, `@startuml` → 32; the
   difference is `docs/ai/README.md`, which only names the fence in prose and legitimately holds no diagram.
   It must not red.
5. **The three-valued exit becomes a feature, not a trap.** `plantuml -checkonly` returns 0 = parses,
   200 = broken diagram, **100 = "No diagram found"** — so a naive `returncode != 0` reds on diagram-less
   files. But because (4) filters on `@startuml`, exit 100 on a file we *know* contains `@startuml` means
   PlantUML cannot see the diagram the grep saw — a malformed block. Mapping:
   | exit | meaning | guard |
   |---|---|---|
   | 0 | parses | pass |
   | 100 | file contains `@startuml` but PlantUML finds no diagram | **red** — malformed block |
   | 200 | diagram has errors (detail on **stderr**; stdout is empty) | **red** |
   | other | unexpected | **red** |
6. **Batch first, attribute only on failure.** Measured: all 32 files in one JVM = **1.07s**; a single file =
   **0.92s** — batching is nearly free. But the batch prints one undifferentiated `Some diagram description
   contains errors` with **no file attribution**. So: batch as the fast path (~1.1s); on red, loop per-file to
   name the offender (~30s, paid only when something is already broken).
7. **Anchor guard.** If the `@startuml` file count reaches 0, red — the guard's anchor is gone.

## Inventory

Every count below is a cited command's output, re-run 2026-07-14 — not an estimate.

| Fact | Command | Value |
|---|---|---|
| Docs carrying a diagram (the guard's surface) | `grep -rl '@startuml' docs/ \| wc -l` | **32** |
| Diagram blocks total (the success line's count) | `grep -rh '@startuml' docs/ \| wc -l` | **41** |
| Docs with the fence but no diagram (must not red) | `comm -23 <(grep -rl '```plantuml' docs/ \| sort) <(grep -rl '@startuml' docs/ \| sort)` | **1** — `docs/ai/README.md` |
| Diagram docs currently parsing | loop `plantuml -checkonly` over the 32 | **32/32** |
| Existing validators | `ls scripts/validate_*.py \| wc -l` | **9** |
| Validator invocations in CI | `grep -c 'venv/bin/python scripts/validate_' .github/workflows/ci.yml` | **9** |
| Subprocess precedent in `scripts/` | `grep -rn 'subprocess\|shutil' scripts/ \| wc -l` | **0** |
| PlantUML/Graphviz in CI | `grep -n -i 'plantuml\|graphviz' .github/workflows/ci.yml` | **0 hits** |
| Diagrams outside `docs/` (excl. `legacy/`) | `grep -rl '@startuml' . --exclude-dir=legacy --exclude-dir=.git \| grep -v '^docs/'` | **none** — `docs/` is the whole surface |
| Diagram files in `legacy/` (out of scope) | `grep -rl '@startuml' legacy/ \| wc -l` | **9**, of which **1 is broken** (`legacy/docs/ai/documentation-guide.md`) |

Files to touch:

| File | Change |
|---|---|
| `scripts/validate_plantuml.py` | **new** — the guard |
| `.github/workflows/ci.yml` | comment enumeration (`:82-90`), invocation (`:95-103`), + a `plantuml` install step |
| `changes/README.md` (`:29-32`) | "Three deterministic checks" → point at the canonical registry |
| `docs/README.md` (`:113-116`) | "machine-checked" list of 4 → point at the canonical registry |
| `changes/review-2026-07-14-doc-drift-findings.md` (#8) | correct the closing paragraph (see below) |

### The registries are already stale — and they drifted exactly as our own rule predicts

- `changes/README.md:29-32` — *"**The trust-chain guards stay green.** Three deterministic checks gate a CR"* —
  names **3**. Stale by six, about to be seven.
- `docs/README.md:113-116` — *"The wiring is machine-checked:"* — names **4** of 9.

Both **restate a list that lives canonically in `ci.yml`**, which is precisely what `changes/README.md`'s own
rule forbids: *"point at a canonical source, don't restate it"*. The fix is therefore not "update the count to
ten" — that would re-arm the same trap. Both prose sites must **point at** the enumeration in `ci.yml` and
stop counting. Updating them is not scope creep: adding the 10th validator is not complete while the places
that enumerate the set still say three and four.

(`.claude/settings.local.json:180-182,245` also lists 4 of 9 plus two long-deleted `.mjs` validators. That
file is machine-local, not repo doctrine — **out of scope**, noted only so the next reader isn't surprised.)

### Correct finding #8's own claim

`changes/review-2026-07-14-doc-drift-findings.md` #8 closes with: *"A `validate_plantuml.py` is the obvious
guard and is **blocked on that CI decision**; refine it fresh, not as a drive-by (it would be green by
construction today)."* Both halves are wrong, and this CR is what disproves them:

- **Not blocked.** Validators are run by hand at every CR's verify step; the CI question is orthogonal
  (see § Explicitly out of this CR).
- **"Green by construction" describes every guard in `scripts/` the day it lands.** cr-0166 and cr-0167 each
  fixed the drift *first*, then added the guard — green at commit. A guard that is red at commit means
  known-broken state was shipped. The question is never *"is it green now"* but *"can it go red later"* — and
  this one demonstrably can: it **would have fired on `docs/architecture/README.md` every day from 2026-07-01
  to 2026-07-14**.

Left uncorrected, the error reproduces: it already propagated once into a refine brief.

## Plan

Single-phase — small and coherent (`changes/README.md`: *"Phases only above real size."*).

- [x] 1 — `scripts/validate_plantuml.py` per decisions 3–7 and the conventions in § Context ·
      Done: `plantuml OK: 41 diagram(s) across 32 docs parse` in **1.5s**, exit 0; the bite-proof reds and names
      the file (§ Outcome).
- [x] 2 — register in `.github/workflows/ci.yml`: one comment line in the `:82-90` block, one invocation in
      `:95-103`, and an install step for `plantuml` before the venv step ·
      Done: **measured, not estimated** — the `Install PlantUML` step is **5.2s** (curl 29 MB + `sha256sum -c`
      + shim), plus a `Set up Java` step reusing the runner's preinstalled Temurin 21. Both container-measured;
      the runner's own number lands on this job's first real run, which this session did not push.
- [x] 3 — record hygiene: `changes/README.md:29-32` + `docs/README.md:113-116` point at `ci.yml` instead of
      restating; finding #8's closing paragraph corrected ·
      Done: `grep -c 'Three deterministic' changes/README.md` → **0**; `validate_doc_links.py` green (125 docs).

## Verify

- All **10** `scripts/` validators green (the 9 + the new one).
- **Bite-proof by snapshot-and-restore, never `git checkout`** (`changes/README.md` § Bite-proof a guard —
  and note `docs/architecture/README.md`'s fix is now **committed** in `dbbbae61`, so `git checkout` would
  restore rather than destroy here; the protocol is still the one to follow, because it is the habit that
  protects the *uncommitted* case):
  1. `cp docs/architecture/README.md /tmp/snap`
  2. Re-inject the historical defect — collapse one `skinparam rectangle { … }` back to the single-line form
     (the exact breakage that survived 13 days).
  3. `python3 scripts/validate_plantuml.py` → **must red**, and must **name `docs/architecture/README.md`**
     (this proves decision 6's per-file attribution, which the batch fast path alone cannot give).
  4. `command cp -f /tmp/snap docs/architecture/README.md`, then confirm the restore (grep the multi-line
     form) before moving on.
- Prove decision 4 does not misfire: the run must **not** flag `docs/ai/README.md`.
- Prove decision 3: temporarily shadow `plantuml` on `PATH` → the guard reds with an install message rather
  than passing silently.

## Definition of done

`scripts/validate_plantuml.py` exists, follows the conventions in § Context, and is green over 32/32 docs with
a counted success line. It is bite-proofed by the protocol above, with the result written into § Outcome. It is
registered in `ci.yml` (comment + invocation + install step), with the step's measured added runtime recorded.
`changes/README.md` and `docs/README.md` point at the canonical registry instead of restating a stale count.
Finding #8's closing paragraph is corrected. All 10 validators green.

No `docs/coverage/` change: `satisfies:` is empty (dev tooling has no use-case), so `validate_coverage.py` is
unaffected — but it must still be green, as must `validate_changes.py` and `validate_doc_links.py`.

## The open question, settled

**Does `-checkonly` need Graphviz? No.** Refinement's two attempts failed because both tried to hide `dot` from
a tool that never looks for it on `PATH`; the third approach — PlantUML's own `-graphvizdot` flag — answers it,
and the **control experiment** is what makes the answer evidence rather than another silent ignore:

| run | result |
|---|---|
| `-graphvizdot /nonexistent/dot -tpng` on a class diagram (needs `dot` to lay out) | `java.io.IOException: Cannot run program "/nonexistent/dot"` — **the flag is honored** |
| `-graphvizdot /nonexistent/dot -checkonly` on that same file | **exit 0** |

So `-checkonly` parses without ever laying out, and the CI install needs a JRE only. This stopped being a
runtime micro-optimisation and became load-bearing: it is half of why the pinned-JAR route (JRE + a 29 MB jar)
beats `apt-get install -y plantuml`, which drags in fop/batik/latex Java libs for a renderer we never invoke.

## Outcome

`scripts/validate_plantuml.py` — the 10th guard, green over **41 diagrams across 32 docs** in **1.5s**.
Decisions 3–7 all landed as refined, and each is proven below rather than asserted. Two things the CR did not
foresee, both found by running the thing:

- **The apt package would have made the guard mean two different things.** The CR assumed `apt-get install -y
  plantuml` was simply "the install". Ubuntu 24.04 ships **1.2020.02** (2020-03-01) — six years stale — and its
  exit codes are *not* the ones decision 5's table maps: a diagram-less file returns **0**, not 100. Decision
  5's `100 → red` branch would have been **dead code in CI** while live at the hand-run, and the skew cuts the
  other way too: a diagram using post-2020 syntax could red CI while green locally. That is precisely the silent
  cross-venue divergence this guard exists to prevent — reintroduced by the guard's own install step.
  **Resolved with the user:** pin the upstream release developers actually run (1.2026.6, sha256 `89948f14…`)
  via `setup-java` + a `plantuml` shim — which is *also* the cheaper route: **5.2s** against **53s**.
- **A fourth exit code exists.** `50` = "No file found", hit while probing. `other → red` already covered it —
  which is the argument for that catch-all clause, not against it; the CR's table is not the whole set.

Design points the guard earned beyond the CR:

- **A red batch that no per-file run reproduces still reds.** Decision 6's batch-then-attribute has a hole the
  CR did not name: if the batch fails but the loop finds no offender, `errors` stays empty and the guard would
  print OK over a red. It now reds with the batch's exit code and a reproduce command — never pass on a red it
  cannot explain.
- **Selection is `docs/**/*.md`, so `changes/` is out of scope** — and this CR is why that matters: its own
  prose names `@startuml` five times while holding no diagram. Fed to the guard it would red as a "malformed
  block". Decision 4's reasoning (`docs/ai/README.md` must not red) generalises further than the CR knew.

**Bite-proof (snapshot-and-restore per `changes/README.md` — no `git checkout`).** `cp` snapshot first, then the
exact historical defect re-injected into `docs/architecture/README.md` (the first `skinparam rectangle { … }`
collapsed to the single-line form from `c43e1419`). Guard **red, naming the file** — proving decision 6's
attribution, which the batch alone cannot give (it prints only `Some diagram description contains errors`). Red
path **32.5s**, green path **1.5s** — exactly the trade decision 6 predicted. Restored with `command cp -f`;
confirmed by the multi-line form back at 3 occurrences **and an empty `git diff` against HEAD**.

Also proven: **decision 3** — `plantuml` shadowed off `PATH` reds with the install message, never a silent pass.
**Decision 7** — both anchors red (no `docs/` tree; a `docs/` tree holding no `@startuml`). **Decision 4** — the
run does *not* flag `docs/ai/README.md`, absent from the surface, while `docs/architecture/README.md` is in it.
The guard additionally ran **on a clean Ubuntu container** through the exact `ci.yml` commands: same
`41 diagram(s) across 32 docs`, 0.7s.

Full set green: all **10** `scripts/` validators — via the glob that replaced the counted prose.

## Retrospective

**Planning.** The CR was strong: decisions 3–7 survived contact unchanged, and its exit-code table was right for
the version it was written against. Its one real fault is instructive — **the install step was treated as a
detail.** "`apt-get install -y plantuml` pulls a JRE and graphviz regardless" appears in the *open question*, as
an aside dismissing a question, and that aside smuggled in an unexamined premise: that the package is
interchangeable with the local tool. It is six years stale and behaves differently. **Root cause:** the CR
verified everything about the *guard* with cited commands and nothing about its *dependency* — every inventory
count was a re-run command, while the one claim about the CI install was never run. **Next time:** when a CR
introduces a first-of-its-kind dependency — and this one *knew* it was first (`grep -rn 'subprocess\|shutil'
scripts/` → 0, flagged as novel right there in the inventory) — that dependency's version and behaviour belong
in the inventory table like any other count. `apt-cache policy` is a cited command too.

**Execution.** Two self-inflicted detours, both from not reading my own output. (1) I declared the inventory's
`grep -v '^docs/'` broken, "fixed" it to `'^\./docs/'`, and made it worse — macOS grep emits no `./` prefix, so
the original was right. I caught it in the next message, but I had already told the user their CR was wrong when
it wasn't. **The skipped check:** look at what the command actually printed before theorising about what it
should print. (2) I copied 3.2 GB of `.git` + `legacy/` across a VirtioFS bind mount to test a **read-only**
validator, and burned 8 minutes before killing it. **Next time:** mount read-only and run in place.

**Process / collaboration — both directions.**
- **The CR demanded a number I could not honestly produce.** "The step's added runtime is **measured and written
  into this CR**, not estimated" — but the only venue that can measure it is the runner, and implementation
  doesn't push. I measured a container proxy and labelled it as one. **A done-when should name its venue:** one
  that can only be satisfied outside the session's remit will be met with a proxy or a fudge, and the CR cannot
  tell which it got.
- **`status:` stayed `planned` in a CR whose every box was ticked**, until I set it by hand.
  `validate_changes.py` checks numbers, ids and refs — not that a CR claiming done says `done`. Cheap guard,
  and a candidate for the next one by exactly this CR's own trigger.
- **The escalation worked and is worth repeating.** The version skew was a decision the CR didn't cover, so it
  went to the user with measured numbers and a recommendation instead of being resolved silently. That is the
  sparring-partner rule paying off in the direction it's hardest — mid-implementation, when the cheap move is to
  just follow the work order.
- **"Run the trust chain as CI would" is now a hand-rolled `docker run`, retyped per session.** By this repo's
  own rule of three that is a script (`scripts/run_trust_chain.py`), not a bespoke incantation — worth proposing
  once it has been wanted a third time.
