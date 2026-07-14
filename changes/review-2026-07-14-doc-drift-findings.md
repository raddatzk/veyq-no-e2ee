# Doc-vs-implementation review — findings handoff (2026-07-14)

A five-agent review compared `docs/` against the actual code and found ~30 confirmed drifts. The **three acute
security defects and the sidebar-weight nit were fixed and committed**, and **Tier 1 (#1–4) is refined** (see
*Already done* below); **everything from Tier 2 down is untouched and lived only in the review conversation** —
this doc persists it so a fresh session can pick items off it.

**How to use this.** Each finding carries a **confirm-command** (run it first — it re-grounds the finding against
the *current* code, which may have moved) and a suggested disposition. Pull one item → refine it with the user →
CR, per [`docs/ai/README.md`](../docs/ai/README.md). This doc models the workflow rule it came from
([`changes/README.md`](README.md), cr fcff3aec): a checkable claim carries the command that produced it.

**Trust levels:** *(verified)* = re-checked by hand this session; *(agent)* = reported by a review subagent,
high-confidence but not independently re-run — confirm before acting.

**The meta-lesson (why this review happened):** refinement decides well but *asserts* badly — almost every past
defect was a checkable fact a `grep` would have settled, not a wrong decision. The durable state is trustworthy
exactly where a validator checks it (contracts, coverage refs, links) and drifts exactly where none does
(crosscutting prose, the architecture map, the styleguides).

---

## Already done this session (do NOT redo)

- **cr-0163** `ad034d8a` — tenant-owner gate keyed on the root scope (privilege escalation).
- **cr-0164** `0b767823` — share-password gate on the guest write path (bypass).
- **cr-0165** `5116ea79` — per-share brute-force bound + **the 3 `cc.security.rate-limit.md` drifts fixed**
  (5 governed endpoints not 2; the static check exists; verification is partial).
- Sidebar entry-weight consistency `160e4dc0`; workflow rule `fcff3aec`.

**Refined 2026-07-14 (Tier 1 — planned, not yet implemented):**
- **[ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md)** (Accepted) — web-first V1, apple/android
  parked, un-park by explicit call. The decision record finding #3 said was missing.
- **[cr-0166](cr-0166-tenancy-roles-and-scope-key-terminology.md)** — findings #1 + #2 (roles + scope-key
  terminology → the code), ends in a lexical guard.
- **[cr-0167](cr-0167-web-first-citations-and-crypto-core-crossrefs.md)** — findings #3 + #4 (44 citation
  repoints, ADR 0010's per-CR clause, `CLAUDE.md`, the three-way crypto-core cross-reference).

---

## Tier 1 — contradictions that actively mislead — **DONE 2026-07-14 → cr-0166 · cr-0167 · ADR 0043**

> **All four are implemented**, each behind a permanent lexical guard in `scripts/` (+ CI):
> `validate_scope_model_vocabulary.py` (#1/#2) and `validate_webfirst_authority.py` (#3/#4). Re-grounding found
> **three of the four inventories below were wrong** — always *under*-counted, and #4 was wrong on the facts.
> The corrections are folded into the CRs; the original claims are struck here so nobody re-inherits them.
> **This is the review's own meta-lesson biting the review:** every wrong number came from a grep that was too
> narrow, and every one of them would have shipped a CR that closed as "done" while leaving most of the defect
> in place.
>
> **It bit once more during implementation, and this is the sharper version of the lesson:** cr-0167's *own*
> corrected inventory was still wrong — 1 of its 38 lines cited ADR 0010 for **0010's own subject**, so the
> planned repoint would have broken a correct citation while fixing a wrong one. A grep finds *candidates*; the
> invariant "cites X **as the authority for** Y" is a binding no grep can see. **Before repointing N citations
> of X, grep for counter-evidence** — which of the N cite X for X's own subject? — and write the output into the
> CR first. See cr-0167 § *Corrections found while implementing*.

1. **Phantom `ADMIN` role** → **[cr-0166](cr-0166-tenancy-roles-and-scope-key-terminology.md)**.
   ~~`scopes-and-tenancy.md:108-109`, 2 spots~~ → **6 spots in that one file**: the **tenant** row (`:108`) is as
   wrong as the group row, `:109` omits `OWNER` entirely, `:135` gives the owner email to a phantom `ADMIN`, and
   `:23`/`:85`/`:95` seed the concept in prose. The role vocabulary is `OWNER`/`MEMBER` at **every** level
   (ADR 0007:22,42,47,57). Dead const confirmed (`MembershipEntity.kt:76`).

2. **Scope "keypair" that doesn't exist** → **[cr-0166](cr-0166-tenancy-roles-and-scope-key-terminology.md)**.
   ~~"~2 spots" in `security-model.md`~~ → **21 phrase-hits across 5 files**, incl. **ADR 0007:17-18 — the
   canonical definition of "grant"** — plus what a phrase-grep cannot see: ~6 PlantUML lines aliasing the concept
   as `spk`, 3 lines naming a payload field (`wrappedScopePrivateKey`) that **never existed**, and ADR 0012:25's
   residue. Root cause is citable: ADR 0024:76-80 lists the docs it invalidates, names 0012/0020/0021 (executed,
   clean) and **misses exactly the five that still say "keypair"**.

3. **Web-first has no home** → **[ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md)** (written) +
   **[cr-0167](cr-0167-web-first-citations-and-crypto-core-crossrefs.md)** (the repoints).
   ~~"cited 19×"~~ → **88 assertions across 41 files**; the "19" was an artifact of one grep phrasing (`4.6×`
   under). Honest command: `grep -rn -iE 'web.first|web first' docs/` (excl. `inbox.md`, ADR 0043) → 88. Of
   those, **44 carry a citation and all 44 are wrong** (38 → ADR 0010, 5 → `platform-parity.md`, 1 →
   `CLAUDE.md`); the other 44 assert it bare. ~~"no home"~~ is also imprecise: `platform-parity.md:17-25`
   *states* the rule — in a **state** doc, and **weaker** than the rule in force. Decisions taken with the user:
   own ADR (not a clause in 0010/0041), and the un-park is an **explicit call, no measurable trigger**.

4. **ADR 0009 ↔ 0041** → **[cr-0167](cr-0167-web-first-citations-and-crypto-core-crossrefs.md)**.
   ~~"0041 (Proposed) **adopts** it"~~ — **it does not.** 0041 says "Adopt … but **not now**" and defers against
   a trigger; the filename is literally `0041-shared-crypto-core-**deferred**.md`. The real gap is *wider*: **ADR
   0010:73 also rejects the shared core**, so it is three ADRs, not two, and none references another
   (`grep -c '0041' docs/decisions/{0009,0010}-*.md` → 0, 0). Nothing gets marked `Superseded` — 0041 is
   `Proposed`, and a proposal cannot supersede an accepted decision. Whether 0041 → `Accepted` is left as an
   open decision, not smuggled into a citation CR.

## Tier 2 — stale / false durable state (says X, code says not-X)

5. **ADR 0040's "Measured" CI table is false** *(verified).* Claims CI runs web e2e; `ci.yml:105` is `if: false`.
   This measurement *carries* ADR 0040's core decision.
   Confirm: `grep -n 'if: false' .github/workflows/ci.yml` · `grep -n 'run_web_e2e' .github/workflows/ci.yml`
   Do: correct the table to what CI actually runs; re-check the decision still holds without the false premise.

6. **ADR 0038 drift-guard catches 3 of 10 enum drifts** *(agent).* Overlap-≥2 heuristic misses drops/renames on
   2-value families; `suite-version` (1 value) is structurally unguarded.
   Confirm: read `scripts/validate_contracts_common.py:108-109`; the agent mutation-tested it (10 cases).
   Do: strengthen the guard (exact-set comparison per family), or scope down ADR 0038's coverage claim to the truth.

7. **Stale ADRs written in the future/past tense** *(agent).* 0027 "until then the code still does the one-off
   seal" (false since cr-0096); 0025 "are unbuilt" (cr-0091 done); several ADRs future-tense for landed CRs.
   Confirm: `grep -n 'until then\|are unbuilt\|To be implemented' docs/decisions/00{25,27,40}-*.md`
   Do: edit to current-state in place (docs are edited in place, git holds history).

8. **`architecture/README.md` indexes 17 of 42 ADRs, 9 of 14 enablers** *(verified/agent).* Self-describes as
   "kept current"; last touched 2026-07-01, 1 commit.
   Confirm: `ls docs/decisions/[0-9]*.md | wc -l` → 42 · `grep -c '00[0-4][0-9]' docs/architecture/README.md`
   Do: decide — either commit to keeping it a live index (a guard could check ADR/enabler coverage) or demote its
   self-claim. **This is the arc42 question in miniature** (see Tier 3, #14).

9. **`platform-parity.md` matrix covers 7 of 39 UCs; `en.memory.tree` marked not-built** *(agent).* The one
   artifact meant to show "which UC on which surface" is 82% empty and partly stale (tree is live).
   Confirm: `grep -c '◻\|✅' docs/crosscutting/platform-parity.md` vs 39 UCs.
   Do: regenerate from coverage, or drop the matrix and point at `docs/coverage/`.

10. **`ui-web-styleguide.md` — wrong legacy source + phantom token system** *(agent).* Points to `legacy/frontend`
    (the rejected E2EE-wrong-concept descendant) instead of `legacy/frontend-noe2ee`; mandates `--app-font-size-*`
    tokens that don't exist in `web/`. ~89 CRs behind.
    Confirm: `grep -rc 'font-size: var(--app-' web/src` → 0 · `grep -n 'legacy/frontend' docs/crosscutting/ui-web-styleguide.md web/CLAUDE.md`
    Do: rewrite the styleguide against the real `web/src/styles/` (the "upcoming styleguide rewrite" cr-0154 named).

11. **`developer-styleguide.md` still says `http/` "verifies the contract"** *(verified).* cr-0159 removed this
    stance everywhere else; the binding styleguide §3/§11 was missed by the sweep.
    Confirm: `grep -n 'verifies the contract\|contract-level coverage' docs/crosscutting/developer-styleguide.md`
    Do: align with ADR 0040 (http is a dev aid, not a coverage surface). Also `:31` lists
    `shared/{security,content,keymgmt,storage}` — 4 of 6 don't exist (`ls backend/.../shared/`).

12. **Bucket authz doc drift** *(agent).* `en.memory.buckets.md` describes owner-gated; cr-0123 relaxed to
    member-gated. A reader infers a stronger guarantee than the code gives.
    Confirm: `grep -n 'owner_key == caller\|owner-gated' docs/features/memory/enablers/en.memory.buckets.md` vs `MediaHandler.kt` bucket ops.
    Do: correct the enabler to member-gated; also `:41` claims a `UNIQUE` partial index that `V35` doesn't create.

13. **Phantom `ACTIVE membership` state** *(verified).* `scopes-and-tenancy.md:94`, `ADR 0008:79`,
    `developer-styleguide.md:53` say authz needs an "ACTIVE membership" — `MembershipEntity` has no status column,
    and server authz needs no grant (the boundary guard is `isScopeMember`).
    Confirm: `grep -rn 'status' backend/.../shared/membership/ db/migration/*membership*` → none.
    Do: strike "ACTIVE"; state the real guard (membership row exists; grant is a *later* 403, ADR 0042).

14. **Dead i18n keys = documented-but-never-built features** *(agent).* `share.expires`, `unlock.untrust`,
    `unlock.trustFailed` are defined, unreferenced, and each maps to a UC-documented feature that was never built.
    Confirm: for each key, `grep -rc "'<key>'" web/src` → 0 refs beyond the definition.
    Do: per key, either build it or mark the UC section deferred (the doc currently over-promises).

## Tier 3 — structural (the soil the drift grows in)

15. **No authorization matrix** *(verified).* The auth rules exist *only* in code — 30 call sites, 6 handlers;
    10 of 16 handlers appear in zero docs; two role systems (`OWNER/MEMBER`, `VIEWER/CONTRIBUTOR/UPLOADER`) never
    documented together. This was the root cause of cr-0162/0163.
    Confirm: `grep -rlc 'Handler' docs/` per handler; `grep -rn 'fun is\|not_' backend/.../features/*/`.
    Do: an `authorization` crosscutting doc — ideally a **generated** gate matrix (op → guard → code), so it can't
    drift. Highest-leverage item here.

16. **`inbox.md` bloat + phantom header path** *(verified).* 82k chars / ~20k tokens, 104 CR refs — the file every
    refine session reads first; its header points to `docs/wiki/`, which doesn't exist (structure is `docs/` +
    separate `wiki/`).
    Confirm: `wc -c docs/inbox.md` · `ls docs/wiki` → not found · `grep -n 'docs/wiki/' docs/inbox.md`
    Do: split the inbox by area (its own trigger, long overdue); fix the header path; move done-chronicle prose to
    git history.

17. **arc42 + adoc conversion** *(decided this session).* Legitimate as an outward-facing communication format,
    **not** as a drift fix (the existing `architecture/README.md` is already the worst-maintained doc — #8).
    **Hard precondition:** adoc breaks **5 of 7 validators silently** (they are markdown-bound; e.g.
    `validate_doc_links.py:21` is the markdown-link regex). Convert the validators alongside; the proof is a
    *broken reference going red*, never "CI green" (a validator that finds nothing reports success on an empty set).
    Confirm: `grep -n 'LINK = re.compile' scripts/validate_doc_links.py` · `grep -c '\.md' scripts/validate_feature_wiring.py`
    Do: its own multi-phase CR; the validator conversion is phase 1 and gates the rest.

## Loose ends (never logged; small, real)

- **Test-shutdown noise slows every verify** *(verified):* backend H2 `SessionPurge` shutdown stacktrace + web
  vitest `EnvironmentTeardownError`/`ECONNREFUSED:3000` (flaky). Both appear in *green* runs; both cost diagnosis
  time. A cleanup would make "is this run actually clean?" cheap.
- **Contract-change CRs hang on a manual backend restart** *(verified):* three CRs this session needed the user to
  restart the IntelliJ backend for the live smoke (no-parallel-`quarkusDev` rule bars a second instance). A
  separate smoke backend, isolated from the IntelliJ instance, would unblock this.
- **Sidebar renders the same nav 4×** *(verified):* desktop / compact-visible / compact-measure / mobile, no shared
  row component; `.sidebar-*` (40 rules) ∥ `.mobile-nav-item` (9 rules). This is why the weight fix needed two
  places. A `<SidebarEntry>` + one CSS system would remove the class. (Note: the *within-desktop* duplication the
  user asked about separately is minimal — favorites/groups already share `.sidebar-scope-link`.)

---

## Suggested next pick

~~Tier 1 (#1–4)~~ — **done 2026-07-14**: ADR 0043 + cr-0166 + cr-0167, both CRs closed with a guard in CI.
They were *not* the "quick warm-up" this section billed them as: the real inventories were 3×, 10× and 4.6× the
reported ones, and #3 needed a genuine new decision (not a correction), so it could not legally share a CR with
#1/#2 — `docs/ai/README.md` requires the refined state *before* the CR that cites it.

**#15 (authorization matrix, generated)** — it is the root cause of the two escalation CRs, it is the one Tier-3
item that *prevents* a whole defect class rather than patching an instance, and a generated matrix can't re-drift.
#16 (inbox) unblocks every future refine. #17 (arc42) is the big one and depends on the validator-conversion
precondition.

**Read this before picking anything:** every *(agent)*-trust finding below still carries an unverified inventory,
and Tier 1's track record is now 3-of-4 wrong — always under-counted, because the confirm-command was narrower
than the defect. **Re-ground with a deliberately *wider* command than the finding's**, and reconcile the two
numbers before planning against either.
