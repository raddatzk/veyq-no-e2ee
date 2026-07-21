# ADR 0046 — Operative lore lives in `.claude/` skills and agents, not a growing CLAUDE.md

- Status: Accepted
- Date: 2026-07-21

## Context

[CLAUDE.md](../../CLAUDE.md) is loaded on **every** task; its cost is paid per turn, forever.
[`ai/README.md`](../ai/README.md) already names this the "tax it pays on every task" and answers it for docs by
deleting duplication. The same tax applies to CLAUDE.md itself: it may hold only what is true for *every* task —
the hard invariants — or it taxes the 90% of turns that never touch the thing.

But operative lore has accreted that is **conditional**, not universal, and it has no home shaped for that. The
trust-chain map (glob-is-registry, the bite-proof protocol) lives in [`changes/README.md`](../../changes/README.md);
the coverage rules in [`ai/README.md`](../ai/README.md); per-guard detail in ten `scripts/validate_*.py` module
docstrings. Assembling that picture from four places is a per-session cost, and none of it is relevant when the
task is unrelated. Grown further, it would either bloat CLAUDE.md (taxing every turn) or the one workflow doc
whose job is to stay short enough that a human judging the project on GitHub reads it (§ audiences).

Three delivery mechanisms exist, and their difference is **load semantics**:

- **CLAUDE.md** — always loaded. Permanent context steering.
- **Skill** (`.claude/skills/`) — loaded on a trigger. A procedure with an explicit *"when"*.
- **Subagent** (`.claude/agents/`) — its own context. Isolation is the point: either the work would flood the
  main context, or unbiasedness (a clean-room implementer, an adversarial verifier) is the whole purpose.

What forced the decision now was a **measured pattern**, not a wish for structure: nearly every expensive recent
error was a *remembered* claim that a cheap measurement would have refuted — a coverage claim believed and later
measured false ("Trap 5"), handoff notes three commits behind the git state, a full analysis written over
already-deleted Android code, an audit finding recommended still-open that had been deleted with the surface it
lived on. That is not a knowledge gap. It is a missing **convention** about how a claim is stated — one that binds
exactly where claims are made, which is not every turn.

## Decision

1. **Three load-semantics, three homes. The decision rule:** *"if X, then procedure Y"* → a **skill**; *"do this
   in isolation / unbiased"* → a **subagent**; *"true for every task"* → **CLAUDE.md**. Conditional lore leaves
   CLAUDE.md for a skill or an agent role.

2. **Skills live in `.claude/skills/<name>/SKILL.md`, agent roles in `.claude/agents/<name>.md` — both committed.**
   `.claude/` is tracked (only `settings.local.json` is gitignored); these are durable repo artifacts, versioned
   beside the code they describe, not machine-local config.

3. **Point at the canonical source; never restate it** — the same invariant that governs `docs/`
   ([`ai/README.md`](../ai/README.md) rule 2). A skill or agent role adds only what no single doc already holds:
   its **trigger**, an **orientation/map** that is currently assembled by hand each session, and any **new
   convention**. Per-item truth stays in its one home — the validator docstring owns what that guard asserts, the
   ADR owns its rule, the use-case owns its behaviour. A skill that duplicates a doc is the defect, deleted like
   any duplication.

4. **Claim-provenance is a standing convention.** Every claim in a report, CR, or handoff is marked **measured**
   or **remembered**; a *measured* claim names the command that proves it. This is the bite-proof idea
   ([`changes/README.md`](../../changes/README.md) § bite-proof a guard) applied to prose instead of tests. It is
   carried by [`trust-chain`](../../.claude/skills/trust-chain/SKILL.md), whose trigger already names *a report, a
   CR, a handoff* — so the claim moments of CR execution are covered there, not in a separate skill (see the
   `cr-execute` finding under Consequences). It binds where claims are made, not on every turn.

5. **[`ai/README.md`](../ai/README.md) stays canonical for the workflow** — the prose narrative and the pipeline,
   read by both the refining agent and the human judging the project. Skills are its **agent-triggered operational
   face**: they link back into it, they do not replace it. CLAUDE.md keeps only the hard invariants.

## Consequences

- **Incremental, and gated by the repo's own trigger** ([`ai/README.md`](../ai/README.md) § when to add a tool):
  *pain before tool* and *rule of three*. Both are met — the provenance failures recur across sessions (rule of
  three), and trust-chain assembly is a felt per-session cost (pain). Skills are added one at a time, each
  justified on its own; `trust-chain` is first and the rest wait behind it.
- **A skills layer is a fourth place a reader can look** — a real cost, paid down only by rule 3. The moment a
  skill restates a doc, both drift and the layer becomes a liability; the no-restate rule is what keeps it an
  asset.
- **Rejected — keep accreting in CLAUDE.md.** Every line taxes every unrelated task; the file grows monotonically
  and the trust-chain lore is noise to almost every turn. This is the status quo the ADR ends.
- **Rejected — fold it all into [`ai/README.md`](../ai/README.md).** That doc is read by a human judging the
  project in twenty minutes; the guard-gap map and a provenance-marking format are operational agent detail that
  is noise to that reader and would bloat the one doc that must stay short.
- **Rejected — one monolithic skill.** Triggers differ (trust-chain fires on *"assert a doc sound"*; cr-execute on
  *"run a CR"*); a single skill would always over- or under-load for the task at hand.
- **Rejected — subagents only, no skills.** A subagent's value is isolation; a procedure the main thread must
  follow *inline* (mark this claim measured, before you write it) cannot be handed to a fresh context. Skill and
  subagent are complements, not substitutes.
- **Worked example of rule 3 — `cr-execute` was checked and *not* built.** A proposed CR-run skill was measured
  against its own no-restate test: every element it would carry — phase order contracts→backend→web, each phase
  self-verifying, coverage-is-part, promote-one-shot-proofs, bite-proof, the closing spoken retro — already has a
  precise canonical home in [`changes/README.md`](../../changes/README.md) + [`ai/README.md`](../ai/README.md) +
  the surface `CLAUDE.md`s, and its claim-provenance role is already covered by `trust-chain`'s trigger. It would
  have been ~100% restatement, failing rule 3, so it was not built. The measured verdict — with the per-element
  citations — is the deliverable, and it is worth more than the skill would have been:
  [cr-0179](../../changes/cr-0179-skills-and-agents.md) P2. This is the no-restate invariant holding against the
  skills layer itself, which is the point.

Deferred to [cr-0179](../../changes/cr-0179-skills-and-agents.md): whether claim-provenance also earns a one-line
CLAUDE.md anchor — so it loads even outside a triggered skill — is left open until the skills exist and we can see
whether the trigger coverage has real gaps. Deciding it now would be guessing.
