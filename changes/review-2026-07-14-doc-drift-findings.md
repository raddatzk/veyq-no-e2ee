# Doc-vs-implementation review — findings handoff (2026-07-14)

A five-agent review compared `docs/` against the actual code and found ~30 confirmed drifts. The **three acute
security defects and the sidebar-weight nit were fixed and committed** (see *Already done* below); **everything
else is untouched and lived only in the review conversation** — this doc persists it so a fresh session can pick
items off it.

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

---

## Tier 1 — contradictions that actively mislead (a reader who checks is *confirmed in the wrong belief*)

1. **Phantom `ADMIN` role** *(verified).* `scopes-and-tenancy.md:108-109` lists group roles as `ADMIN · MEMBER`;
   ADR 0007 + 0014 forbid `ADMIN`, and the contract has `[OWNER, MEMBER]`. A dead `const val ADMIN` in the code
   makes a naive grep *confirm* the wrong doc.
   Confirm: `grep -rn 'ADMIN' backend/src/main` → 1 hit (dead const) · `grep -n 'enum' -A2 contracts/api/scopes.openapi.yaml | grep -A1 SetMemberRole`
   Do: fix the tenancy-doc role lists to `OWNER/MEMBER`; delete the dead const (or promote a guard forbidding it).

2. **Scope "keypair" that doesn't exist** *(verified).* `security-model.md` describes revoke as "rotate the scope
   **keypair**" (~2 spots); ADR 0024 is a symmetric random-key epoch chain, no keypair.
   Confirm: `grep -n 'keypair' docs/crosscutting/security-model.md` · `grep -rniE 'scopeKeyPair|scopePublicKey' backend/src web/src` → 0
   Do: correct the terminology to the epoch-chain model (distinct from the *identity* keypair, which is real).

3. **Web-first has no home, cited 19× to the wrong ADR** *(verified).* Docs cite "ADR 0010" as the web-first
   authority; ADR 0010 says nothing about it (says the opposite: "parity guaranteed by shared contracts"). ADR 0041
   cites "CLAUDE.md", which also lacks it. `CLAUDE.md` says "kept at **parity**".
   Confirm: `grep -rl 'web-first.*ADR 0010\|Web first, then native parity' docs/ | wc -l` → 19 · `grep -ic 'web.first\|defer\|park' docs/decisions/0010-*.md CLAUDE.md` → 0
   Do: give web-first one real home (its own ADR, or a clause in an existing one), point the 19 citations there,
   fix `CLAUDE.md`'s "kept at parity" to reflect the actual priority.

4. **ADR 0009 ↔ ADR 0041 contradict, no cross-reference** *(agent).* 0009 (Accepted) rejects a shared Rust crypto
   core; 0041 (Proposed) adopts it — different trigger, neither references the other.
   Confirm: `grep -c '0041\|0009' docs/decisions/0009-*.md docs/decisions/0041-*.md`
   Do: reconcile — 0041 supersedes/extends 0009's "revisit" clause, or one links the other.

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

## Suggested first pick

**#15 (authorization matrix, generated)** — it is the root cause of the two escalation CRs, it is the one Tier-3
item that *prevents* a whole defect class rather than patching an instance, and a generated matrix can't re-drift.
Tier 1 (#1–4) are quick, high-mislead-value doc corrections good for warming up a session. #16 (inbox) unblocks
every future refine. #17 (arc42) is the big one and depends on the validator-conversion precondition.
