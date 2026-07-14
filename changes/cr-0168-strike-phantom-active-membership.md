---
id: cr-0168
title: Strike the phantom `ACTIVE` membership and guard it; correct the review's finding #15
status: done
satisfies: ["cc.security.authorization"]
adrs: ["0006", "0007", "0008", "0042"]
invariants: []           # no code, no contract, no behaviour — vocabulary + record hygiene
---

## Goal

Four documents state that server-side authorization requires an **`ACTIVE` membership**. There is no membership
status column and never has been: `MembershipEntity` carries `(userId, scopeId, tenantId, role, wrappedGrant)`,
and the real gate is the membership **row's existence** — the boundary guard `isScopeMember`, first, then the
key-holder check ([ADR 0042](../docs/decisions/0042-http-failure-projection.md)). A reader who *checks* is
confirmed in the wrong belief, which is worse than a gap. Strike it at every site, and promote the strike to a
permanent guard so it cannot return.

Bundled with the correction of the review's **finding #15**, because they are the same defect at two altitudes:
[cc.security.authorization](../docs/crosscutting/cc.security.authorization.md) (`abec9887`) is now the document
that says what the guard actually is — the docs that contradict it must follow, and the finding that asked for
the wrong artifact must stop asking. Findings **#13 + #15** of
[the 2026-07-14 doc-vs-code review](review-2026-07-14-doc-drift-findings.md).

## Context & constraints

- **Finding #13's inventory was wrong — the same direction as every other one.** It named three sites
  (`scopes-and-tenancy.md:94`, `ADR 0008:79`, `developer-styleguide.md:53`). The truth is **five**: one line
  number is off by two, and it missed **both** `uc.admin.create-tenant.md` sites entirely, because they phrase the
  phantom differently — "`ACTIVE` **member**" and "ACTIVE **tenant-owner**", never the phrase "ACTIVE
  membership" that the confirm-command greps for. `ADR 0008:79` is already fixed (`abec9887`), so **four remain**.

  ```
  grep -rniE '`?ACTIVE`?[ *]*(member|membership|tenant-owner)|(member|membership)[a-z]* (is |as )?`?ACTIVE`?' \
    docs/ --include='*.md' | grep -v '^docs/inbox.md'                                                    → 4
  grep -rn 'ACTIVE membership' docs/ --include='*.md'                                                    → 1
  ```

  The narrow phrase finds **one of four**. This is the review's own meta-lesson for the third time, and the
  sharpest form of it yet: the finding's confirm-command **was** the finding's definition of the defect, so the
  two agreed with each other and both were wrong.

  **And it bit this CR, at refine time, on this very grep.** This document first cited the narrow grep as `→ 2`.
  That was its output *before* `abec9887` (`developer-styleguide.md:53` + `ADR 0008:79`) — the number was never
  re-run after the same CR's Context recorded that 0008 was already fixed, and `scopes-and-tenancy.md:96` writes
  `` `ACTIVE` `` **backticked**, so the literal never matched it at all. A cited command is only re-runnable if
  someone re-runs it; pasting yesterday's output under today's claim is the estimate the format was meant to
  kill. Corrected at implement time by re-running both greps against `HEAD`.

- **`ACTIVE` itself is real and must survive.** It is a live state on two other axes — the **tenant** lifecycle
  (`PENDING → ACTIVE`, [ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md):29) and the **invite**
  lifecycle (`ACTIVE → SUPERSEDED / CONSUMED / EXPIRED`). **8 legitimate lines across 4 files**
  (`uc.spaces.manage-member-invites.md` 2, `uc.admin.create-tenant.md` 4, `uc.admin.tenant-terms.md` 1,
  `ADR 0006` 1). A flat strike — or a flat guard — destroys them:

  ```
  grep -rncE 'tenant[^.]{0,30}`?ACTIVE`?|`?ACTIVE`? *(→|->) *(SUPERSEDED|CONSUMED|EXPIRED)|PENDING *(→|->) *`?ACTIVE`?' \
    docs/ --include='*.md' | grep -v ':0$' | grep -v '^docs/inbox.md'   → 4 files, 8 lines
  ```

  So the phantom is not the word `ACTIVE`; it is **`ACTIVE` applied to a membership**. That distinction is the
  whole CR.

- **One site is inside a PlantUML block.** `uc.admin.create-tenant.md:64` is a sequence-diagram line
  (`S -> S : verify secret; store grant;\nowner = ACTIVE tenant-owner;\ntenant = ACTIVE; invite = CONSUMED`) —
  it carries **both** the phantom *and* a legitimate `tenant = ACTIVE` on the same line. Editing it needs
  `plantuml -checkonly` to stay green, and it is the line that makes a line-scoped guard a proxy (below).

- **No code, no contract, no behaviour.** Every check is a `scripts/` validator plus `plantuml -checkonly`.

## Decisions

1. **The phantom is `ACTIVE` *applied to a membership*, never the word itself.** Strike it at the four sites; the
   8 tenant/invite-lifecycle lines are untouched and are the reason the guard is narrow.
   *Rejected — strike or guard `ACTIVE` flatly.* It reds on 8 correct lines and would push someone to reword the
   real tenant lifecycle to satisfy a grep — the failure mode `validate_scope_model_vocabulary.py`'s own docstring
   already warns about for the `ADMIN` signpost.

2. **Two sites state the real guard; two just lose a word.** Where the phantom sits inside a sentence *about
   authorization* (`scopes-and-tenancy.md:96`, `developer-styleguide.md:53`), deleting "ACTIVE" leaves a claim
   that is still wrong-by-omission ("a valid grant" is not the first gate — membership is). Those two state
   [ADR 0042](../docs/decisions/0042-http-failure-projection.md)'s rule and point at
   [cc.security.authorization](../docs/crosscutting/cc.security.authorization.md) — the same shape as `ADR 0008`'s
   fix in `abec9887`. Where it is a passing adjective on a tenant-activation step (`uc.admin.create-tenant.md`
   :42/:64), the word simply goes; that use-case is not about authz and must not grow a paragraph on it.

3. **The guard is a fourth invariant in `validate_scope_model_vocabulary.py`, not a tenth script.** That guard
   already *is* this: "certain nouns must not appear in `docs/` because the things they name do not exist"
   (its docstring), with the same `docs/`-minus-`inbox.md` scope note and the same anchor-existence pre-check.
   A membership state that does not exist is the same class as an `ADMIN` role that does not exist.
   *Rejected — a new script.* One pattern, no new anchor, no new input tree — over-building, against
   `docs/ai/README.md`'s "pain before tool".

4. **The check is a proxy and says so in its own docstring.** It keys on `ACTIVE` being *adjacent to* a
   membership word (`member` / `membership` / `tenant-owner`). It cannot see a phantom phrased without those
   words, and it cannot see a *clause*: `uc.admin.create-tenant.md:64` carries the phantom and a legitimate
   `tenant = ACTIVE` on one line, so line-scope is a proxy for clause-scope — exactly as
   `validate_webfirst_authority.py` records for its own case. The error message must name the false-positive case
   and say **split the claim, don't weaken the check**.

5. **Review finding #15 is struck like Tier 1 — with its real numbers.** Its claims are `(verified)`-tagged and
   wrong: "30 call sites, 6 handlers" (→ **90** guard lines across **15** files under `features/` — **7**
   `Handler` + **6** `Store` classes + 2 others; the declarations are 10 further lines in `MembershipStore.kt`.
   *This CR planned to write "9 handlers"; no defensible command produces 9 — see the retrospective*),
   "16 handlers" (→ 16 `Resource` **plus** 14 `Handler`
   classes, two different nouns per [ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)),
   "two role systems" (→ **three** vocabularies at **four** definition sites; `SERVER_OWNER`/`MEMBER` was missed
   entirely). And the load-bearing claim — "*This was the root cause of cr-0162/0163*" — is not something either
   CR says (`grep -rn -i 'root cause' changes/cr-016{2,3}*.md` → **0 hits**); both explicitly recorded that a
   static check is the *wrong* instrument, as does [ADR 0042](../docs/decisions/0042-http-failure-projection.md):104
   and `OperatorBlindProbeTest.kt:29`. #13's inventory is corrected in the same edit (three sites → five).
   *Rejected — delete #15.* Tier 1's strikes are kept struck so nobody re-inherits the claim; the corrected
   entry is the record of why the concern doc is a map and not a matrix.

6. **Explicitly out of scope — repointing docs at the new concern. There is nothing to repoint.** This CR planned
   to redirect "the docs that mention guards in prose" at `cc.security.authorization`; the counter-evidence grep
   [cr-0167](cr-0167-web-first-citations-and-crypto-core-crossrefs.md)'s retrospective demands *before* planning a
   repoint says the plan was wrong. Every hit names a guard because the guard **is that document's own subject** —
   ADR 0042 (the ordering rule), ADR 0008 (the annotations), ADR 0007 (the relation), ADR 0036 (its own share
   gate). Repointing any of them would be cr-0167's exact defect: citing X for X's own subject.

   ```
   grep -rn 'isScopeMember\|isTenantOwner\|isScopeOwner\|holdsGrant\|@RolesAllowed\|@PermitAll\|@Authenticated' \
     docs/ --include='*.md' | grep -v '^docs/inbox.md' | grep -v 'cc.security.authorization'
   ```
   → 18 lines / 5 files, **all** in the doc that owns the symbol. The one arguable case,
   `developer-styleguide.md:47` (restates ADR 0008's annotation rule in a *binding* section), is **finding #11's**
   territory — the styleguide's stance on restatement-vs-pointer is a decision that needs its own grounding.

7. **Not decided here: whether `docs/` gets a doc-only CI trigger.** `.github/workflows/ci.yml`'s `paths-ignore`
   starts with `**/*.md` + `docs/**`, and all three negation escapes point at paths that do not exist
   (`ls -d docs/api docs/wiki docs/product` → 3× not found), so a docs-only PR **runs no CI at all** — this CR's
   guard included. It still fires on any code PR. Real, out of scope, and its own decision.

## Inventory

| Site | Today | After |
|---|---|---|
| `docs/crosscutting/scopes-and-tenancy.md:96` | "a valid grant + `ACTIVE` membership, checked by small guards" | membership boundary first, then the grant → [cc.security.authorization](../docs/crosscutting/cc.security.authorization.md) |
| `docs/crosscutting/developer-styleguide.md:53` | "a scope-access guard requiring a valid grant + ACTIVE membership" | same rule, one line, pointing at the concern |
| `docs/features/admin/use-cases/uc.admin.create-tenant.md:42` | "marks the owner an **`ACTIVE` member** with the tenant-owner role" | "marks the owner a member with the tenant-owner role" |
| `docs/features/admin/use-cases/uc.admin.create-tenant.md:64` (PlantUML) | `owner = ACTIVE tenant-owner; tenant = ACTIVE` | `owner = tenant-owner; tenant = ACTIVE` — the second `ACTIVE` **stays** |
| `docs/decisions/0008-centralized-http-auth.md:79` | — | **already done** (`abec9887`) |
| 8 lines / 4 files: tenant + invite lifecycle | `ACTIVE` as a real state | **unchanged** (decision 1) |
| 18 lines / 5 files naming a guard symbol | name their own subject | **unchanged** (decision 6) |
| `scripts/validate_scope_model_vocabulary.py` | 3 invariants | 4 — the phantom membership state |
| `changes/review-2026-07-14-doc-drift-findings.md` #13, #15 | wrong counts; #15's root-cause claim is false | struck + corrected, Tier-1 style |

## Plan

Single phase — docs + one validator, no seam to walk.

1. **The four strikes** (decisions 1–2), `uc.admin.create-tenant.md:64` last — it is the PlantUML line.
2. **The guard** — the fourth invariant + its docstring's proxy note (decisions 3–4). Done when: proven to bite.
3. **The record** — review doc #13 + #15 (decision 5).
4. **CI wiring** — none needed: the validator is already on `ci.yml`'s trust-chain step. Note this in the CR
   rather than adding a line (cr-0167 added +2 lines because its script was new; this one is not).

## Verify

- `python3 scripts/validate_scope_model_vocabulary.py` — green, and **watched red first**.
- `python3 scripts/validate_doc_links.py` · `validate_changes.py` · `validate_coverage.py` — green.
- `plantuml -checkonly` on `uc.admin.create-tenant.md` — the diagram still parses after the `:64` edit.
- **Bite-proof, snapshot-and-restore per [`changes/README.md`](README.md) — never `git checkout`.** Three
  branches, injected at once: the `ACTIVE membership` prose form back into `scopes-and-tenancy.md`, the
  `ACTIVE member` form into `uc.admin.create-tenant.md`, and — the branch that matters — a **legitimate**
  `tenant = ACTIVE` line into a doc that has none, which must **stay green**. A guard that reds on the tenant
  lifecycle is worse than no guard. `cp` before, `command cp -f` after, then confirm **both** the fix markers
  (present) and the injection residues (gone).
- Read-back: the phantom grep from *Context* → **0**; the legitimate-`ACTIVE` grep → **still 8 lines / 4 files**.
  Both numbers, or the strike is unproven in one direction.

## Definition of done

- No document states that authorization requires an `ACTIVE` membership; the two authz-sentence sites state the
  real rule (membership boundary first, then the key-holder check) and point at
  [cc.security.authorization](../docs/crosscutting/cc.security.authorization.md).
- The tenant and invite `ACTIVE` lifecycles are **untouched** — 8 lines, verified by the second read-back.
- `validate_scope_model_vocabulary.py` carries the invariant, names its proxy scope in the docstring, and has
  been **watched to red** on the phantom *and* **watched to stay green** on the legitimate form.
- The review doc's #13 and #15 carry their real numbers, struck Tier-1 style.
- All 9 `scripts/` validators green; `plantuml -checkonly` green.

## Retrospective

- **The CR's own thesis bit the CR, twice, and both times on a *pasted* number.** This document exists because a
  finding's counts were wrong — and it shipped two wrong counts of its own. (a) Context cited
  `grep -rn 'ACTIVE membership'` → **2**; at `HEAD` it is **1**. The 2 was that grep's output *before*
  `abec9887`, and the CR's very next sentence says 0008 was already fixed — so the number contradicted its own
  paragraph. (b) Decision 5 specified "6 handlers → **9**"; **no defensible command produces 9** (7 `Handler`
  classes hold a guard call, 6 `Store` classes hold a fused one, 15 files under `features/` in total, 90 guard
  lines). Root cause is the same in both: a number was written from *reasoning about* a command instead of from
  running it, and `changes/README.md`'s format rule was satisfied *typographically* — a command is printed next
  to a number, which reads as proof — while the number came from somewhere else. **A cited command is only
  evidence if someone re-ran it against `HEAD`.** The format catches estimates; it does not catch stale or
  reasoned-out outputs, which look identical on the page.
- **The strike-and-guard shape was right, and the bite-proof earned its keep.** Decision 1's narrow framing (the
  phantom is `ACTIVE` *applied to a membership*) held exactly: the third bite-proof branch — a legitimate
  `tenant … ACTIVE` line carrying a membership word **on the same line but not adjacent** — stayed green, which
  is the single fact that separates this guard from the flat one decision 1 rejected. Proving the *green*
  direction was worth more than proving the red one; the red was never in doubt.
- **Process, both directions.** The user's `go` was clean and the CR was implementable from the doc alone — the
  clean-room test passed, which is the point of the split session. My miss: I nearly wrote "9 handlers" into a
  permanent record *because the CR said so*, and only caught it because the grep-B error had already taught me,
  three minutes earlier, to distrust this document's numbers. Without that accident it would have shipped. The
  CR being an authority and the CR being *checked* are different things, and an implement session that treats a
  refined CR as ground truth for **counts** re-introduces the exact defect the CR was written to remove.
- **Found in passing, out of scope, spun off:** all 3 PlantUML diagrams in `docs/architecture/README.md` fail
  `plantuml -checkonly` at `HEAD` and have apparently never rendered (single-line `skinparam rectangle { … }`).
  Real evidence for finding #8; no validator covers PlantUML, so it rotted invisibly.
- **Next time:** at implement time, **re-run every number a CR cites before using it, and treat a mismatch as a
  finding rather than a typo** — this session found 2 in one CR. Cheap concrete form: the first Bash call of an
  implement session re-runs the CR's own Context/Decision commands as a block and diffs them against the stated
  outputs. Refine writes the claim; implement is where it must be re-earned.
