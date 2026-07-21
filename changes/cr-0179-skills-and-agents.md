---
id: cr-0179
title: Operationalize the working lore into .claude/ skills and agent roles
status: in-progress
satisfies: []
adrs: ["0046"]
invariants: []
---

## Goal

Move the repo's **conditional** operative lore out of an ever-growing CLAUDE.md into the mechanism whose load
semantics fit it: **skills** (`.claude/skills/`, loaded on a trigger) and **agent roles** (`.claude/agents/`,
their own context). The decision, the three load-semantics, and the claim-provenance convention are recorded in
[ADR 0046](../docs/decisions/0046-operative-lore-in-skills-and-agents.md). This CR is the phased rollout. Skills
and agent roles **point at** the canonical docs (`ai/README.md`, `changes/README.md`, ADRs, validator
docstrings) and never restate them — a skill that duplicates a doc is the defect.

Phased deliberately: **Phase 1 (the `trust-chain` skill) is built and reviewed before the rest is designed.**
Later phases are intent, not a frozen plan — each gets its own refinement, and any that proves genuinely
independent becomes its own CR rather than a phase here (`changes/README.md` § phases).

## Context & constraints

- **ADR:** [0046](../docs/decisions/0046-operative-lore-in-skills-and-agents.md) — the whole basis.
- **Canonical sources a skill points at, never copies:** [`docs/ai/README.md`](../docs/ai/README.md) (workflow +
  coverage rules), [`changes/README.md`](README.md) (glob-is-registry, bite-proof, locator rules),
  [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md),
  [ADR 0045](../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md), and the ten
  `scripts/validate_*.py` module docstrings.
- **No-restate invariant** ([`ai/README.md`](../docs/ai/README.md) rule 2): the guard that keeps the skills layer
  an asset. Each skill adds only its trigger, an orientation/map assembled from nowhere-single, and any new
  convention.
- **Placement:** `.claude/` is tracked (measured: `git check-ignore` reports only `settings.local.json` ignored;
  `.claude/skills/**`, `.claude/agents/**` are `tracked-ok`), so these are durable, committed artifacts.
- **Self-application:** every factual claim inside a skill obeys claim-provenance — it is a pointer, or it is
  marked *measured* and names the command. The skill is the convention's first worked example.

## Phases

- [x] **1 — `trust-chain` skill.** `.claude/skills/trust-chain/SKILL.md`: the claim-provenance rule + a verified
      guard-gap map (green = reference resolves, not that it carries weight) + pointers to the canonical sources.
      All content *measured* (this CR's Verification section is the command log) or a pointer — nothing recalled.
      Done when: SKILL.md exists on verified content, carries no restated block from a canonical doc, and
      `for f in scripts/validate_*.py; do python3 "$f"; done` is green.

Later phases — **intent only, re-refined before each is built; not a frozen queue** (`changes/README.md`):

- [ ] **2 — `cr-execute` skill.** The CR-run procedure (phase order contracts→backend→web, coverage-is-part,
      promote-one-shot-proofs, the closing retro) as a triggered entry — pointing at `changes/README.md` +
      `ai/README.md`, restating nothing. Carries claim-provenance too (ADR 0046 §4). *May instead stay a
      pointer-only skill if refinement finds `changes/README.md` already suffices.*
- [ ] **3 — `mutation-gate` skill** (deep-dive under `trust-chain`): the assertion-strength last link
      (ADR 0045) — when PIT/Stryker run, the total-vs-per-glob meta-check asymmetry, the KAT-beats-mutation
      finding. Points at ADR 0045; restates nothing.
- [ ] **4 — agent roles.** `refine` (plan-only, no Write outside `changes/`), `implement`, `verify`
      (adversarial), `measure` (read-only, short-lived). Grounds the clean-room split that `ai/README.md`
      § session-topology already argues. Each role's boundary is the new content; the workflow it serves stays in
      `ai/README.md`. *Likely its own CR — agent roles are a larger design surface than the skills.*
- [ ] **5 — CLAUDE.md / docs reconcile.** Once the skills exist: thin CLAUDE.md and `ai/README.md` only where a
      skill now carries the conditional detail, and settle ADR 0046's open question (does claim-provenance earn a
      one-line CLAUDE.md anchor?). Never delete a canonical home to a skill — only stop *duplicating* it.

## Definition of done (Phase 1)

`.claude/skills/trust-chain/SKILL.md` exists; every factual claim in it is a pointer or marked *measured* with
its command; it restates no canonical doc (spot-checked: no copied paragraph from `changes/README.md` /
`ai/README.md`); and `for f in scripts/validate_*.py; do python3 "$f"; done` is green. The trust-chain guards
have no hook for a prose skill file, so the completeness proof here is the **Verification** log below (the
measured basis for every claim), not a validator — stated honestly per ADR 0045's own limit that green proves
resolvability, not substance.

## Verification (the measured basis for Phase 1's claims)

Ledger of the commands whose output the skill's claims rest on — so a fresh reader can re-run, not re-trust.

- **`.claude/` tracked, not ignored:** `git check-ignore -q <path>` → `settings.local.json` IGNORED;
  `skills/trust-chain/SKILL.md`, `agents/refine.md`, `launch.json` all tracked-ok.
- **Three CI runs / triggers:** read `.github/workflows/{trust-chain,ci,mutation,mutation-backend}.yml` —
  trust-chain unfiltered (`trust-chain.yml:14-21`, no paths filter), `ci.yml` `paths-ignore` incl. `docs/**`
  (`:9-13`,`:19-23`), mutation jobs path-gated onto scope files (`mutation.yml:16-43`,
  `mutation-backend.yml:17-51`).
- **Glob = registry:** `trust-chain.yml:47-60` `for f in scripts/validate_*.py` + `count==0` guard;
  `mutation_meta_check*.py` sit outside the `validate_*` glob (filename prefix).
- **web=path-bound / backend=symbol-bound:** `validate_coverage.py` — web `(_WEB_/ref).exists()` (:101-103),
  backend `ClassName.method` resolved via `KOTLIN_FUN` regex + stem map (:75,:86-99).
- **Green = resolvability, not substance:** every `validate_*.py` + both meta-checks assert existence/presence/
  count>0, never the weight of what is referenced (per-file read of all ten + the two meta-checks).
- **Six measured guard gaps** (judged from code, not bite-tested this session — the bite-tests are the prior
  sessions' record, re-confirmed here by reading): web ref at a non-test file passes; backend ref at an empty
  `fun` passes; `mutation_meta_check.py` is **total-count** (`:53-55`) while `mutation_meta_check_backend.py` is
  **per-glob** (`:54-63`) — the remembered "total-count" claim held only for the crypto check; CR `status: done`
  with no impl passes; `#fragment` stripped unverified (`validate_doc_links.py:35`); coverage `status: done`
  rejected but a lying `status: planned` passes (`validate_coverage.py:132-134,:159`).
- **Correction to the remembered map:** "five coupling points" was an undercount — there are **ten**
  `validate_*.py` validators + two meta-checks (`ls scripts/validate_*.py scripts/mutation_meta_check*.py`).

## Retrospective

_(filled at Phase 1 close — see the process retro delivered in-session.)_
