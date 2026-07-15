# Doc-vs-implementation review — findings handoff (2026-07-14)

A five-agent review compared `docs/` against the actual code and found ~30 confirmed drifts. The **three acute
security defects and the sidebar-weight nit were fixed and committed**; **Tier 1 (#1–4)**, **#13** and **#15** are
**done** (see *Already done* below). The rest of Tier 2/3 is untouched and lived only in the review conversation —
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

**Tier 1 — refined and implemented 2026-07-14:**
- **[ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md)** (Accepted) — web-first V1, apple/android
  parked, un-park by explicit call. The decision record finding #3 said was missing.
- **[cr-0166](cr-0166-tenancy-roles-and-scope-key-terminology.md)** — findings #1 + #2 (roles + scope-key
  terminology → the code), ends in a lexical guard.
- **[cr-0167](cr-0167-web-first-citations-and-crypto-core-crossrefs.md)** — findings #3 + #4 (44 citation
  repoints, ADR 0010's per-CR clause, `CLAUDE.md`, the three-way crypto-core cross-reference).

**#13 + #15 — done 2026-07-14** (the same defect at two altitudes):
- **[cc.security.authorization](../docs/crosscutting/cc.security.authorization.md)** `abec9887` — #15's real gap:
  the authz model as a **map** (7 dimensions, 6 rules), not the generated matrix the finding asked for.
- **[cr-0168](cr-0168-strike-phantom-active-membership.md)** — #13's four remaining phantom `ACTIVE`-membership
  sites struck, behind a fourth invariant in `validate_scope_model_vocabulary.py`; **#13's and #15's own numbers
  corrected in place**.

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
   **Done 2026-07-14 — and it was worse than "false".** The table was headed **"Measured"** and nobody had run
   it: `run_web_e2e.py` had *already* been moved to `legacy/` when the row was written, and its job has carried
   `if: false` ever since. The decision survives — its argument is about `http`, which the table gets right —
   but the false half was load-bearing elsewhere: **four web `e2e` coverage refs were riding on it**, pointing
   at tests gated behind `describe.skipIf(!process.env.VEYQ_E2E)` that every CI run skips. Removed; each
   criterion they touched keeps a CI-run ref.

   **The finding under-called it.** It said "correct the table". The table's own sentence — *"'CI-proven' is
   not a function of area alone"* — was already the right rule; the ADR simply never applied it to `web`. So the
   fix was not a correction, it was **enforcing a rule the ADR had already written and then exempted itself
   from**. It also sharpened `docs/ai/README.md`, where the rule had been restated one level too coarse ("a
   *surface* no CI runs" → "a *test* no CI run executes"): a live surface is no alibi.

6. **ADR 0038 drift-guard catches 3 of 10 enum drifts** *(agent).* Overlap-≥2 heuristic misses drops/renames on
   2-value families; `suite-version` (1 value) is structurally unguarded.
   Confirm: read `scripts/validate_contracts_common.py:108-109`; the agent mutation-tested it (10 cases).
   Do: strengthen the guard (exact-set comparison per family), or scope down ADR 0038's coverage claim to the truth.

7. **Stale ADRs written in the future/past tense** *(agent).* 0027 "until then the code still does the one-off
   seal" (false since cr-0096); 0025 "are unbuilt" (cr-0091 done); several ADRs future-tense for landed CRs.
   Confirm: `grep -n 'until then\|are unbuilt\|To be implemented' docs/decisions/00{25,27,40}-*.md`
   Do: edit to current-state in place (docs are edited in place, git holds history).

8. **`architecture/README.md` indexes 17 of 42 ADRs, 9 of 14 enablers** *(verified/agent).* Self-describes as
   "kept current"; last touched 2026-07-01, 1 commit (2 since `abec9887` indexed the authz concern).
   Confirm: `ls docs/decisions/[0-9]*.md | wc -l` → 42 · `grep -c '00[0-4][0-9]' docs/architecture/README.md`
   **Done 2026-07-14 — the self-claim is demoted, not guarded.** §9 now says outright that it is *"a reader's
   path in, not the full set — all 42 live in `decisions/`, which is the only complete list"*, and the enabler
   roll-call is gone: it points at `features/` instead of naming 9 of 14. A directory cannot be out of date
   about its own contents; a hand-copied list always can. The alternative on the table — a guard checking
   ADR/enabler coverage — would have forced this page to grow every time an ADR landed, to keep a list nobody
   asked for. **The finding assumed the choice was "keep it accurate or admit defeat"; the third option was to
   stop making the claim.** Two stale claims went with it: *"Four client surfaces at parity"* (one is live) and
   *"The 17 ADRs"* (there are 42).

   **Harder evidence, found 2026-07-14 while implementing cr-0168 — the rot was *invisible*, not just
   unattended.** All **three** of this file's PlantUML diagrams failed `plantuml -checkonly` and had **never
   rendered**: each used the single-line `skinparam rectangle { … }` form, which PlantUML rejects, and the broken
   form was present in the file's **first commit** (`c43e1419`). Fixed (the multi-line form; 32/32 diagram docs
   now parse) — but the fix is the small half. **Rendering them exposed content no sweep had ever seen:**
   - `:22` is the **last place repo-wide** calling `http/` "ijhttp contract tests" —
     [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)'s **title** says the opposite, and
     this is finding **#11**'s stance-drift in a second file. cr-0159's sweep never touched this file: it grepped
     `verifies the contract`; the diagram says `contract tests`. **The meta-lesson again — the sweep's command was
     narrower than the defect**, and the text was inside a diagram nobody could see.
   - `grep -c 'parked\|web-first\|0043' docs/architecture/README.md` → **0**. Both client rectangles (`:22`,
     `:149`) still present apple/android as live surfaces, against
     [ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md).

   **Both content items are now fixed** (2026-07-14), not by a guard but by the diagram migration below: the
   `http/` rectangle states what [ADR 0040](../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md)
   decided ("IDE tooling, not a coverage surface" — `ijhttp contract tests` is now gone **repo-wide**), and both
   client rectangles state the [ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md) schedule (web
   live, apple/android parked). This finding's confirm-command `grep -c 'parked\|web-first\|0043'` went **0 → 2**.

   **The real lesson is about the guard this finding asked for, and it is the opposite of what it assumed.**
   [cr-0169](cr-0169-guard-diagrams-as-code.md) built `validate_plantuml.py` — 130 lines, plus a JRE and a
   pinned 29 MB jar as a CI dependency — to parse diagrams that **nobody could see**: GitHub renders no
   PlantUML, so `docs/`'s only visual asset was invisible to the only audience that reads it. The guard was
   **deleted the same day**, obsoleted by switching all 41 diagrams to **Mermaid**, which GitHub renders and
   whose breakage therefore shows as a visible render error. The format choice did what the guard was built to
   do, and removed machinery instead of adding it.

   Three assumptions this finding made, all wrong, all reusable errors:
   - **"Blocked on that CI decision"** — no. Validators run **by hand at every CR's verify step**
     ([`README.md`](README.md) § trust-chain guards). The CI question (cr-0168 decision 7) hits **all** docs
     guards equally and gates none.
   - **"Green by construction"** — true of *every* guard the day it lands, and no argument against one. cr-0166
     and cr-0167 each fixed the drift first, then guarded it; a guard red at commit means known-broken state
     shipped. The question is "can it go red later", not "is it green now".
   - **"A `validate_plantuml.py` is the obvious guard"** — the assumption underneath was that the rot needed
     *enforcing*, when it needed *rendering*. **Ask what the defect cost before building the guard:** three
     diagrams failed to render for 13 days and the observed cost was zero, because no reader ever reached them.
     The 13 days were evidence that nobody looks — not evidence of urgency.

9. **`platform-parity.md` matrix covers 7 of 39 UCs; `en.memory.tree` marked not-built** *(agent).* The one
   artifact meant to show "which UC on which surface" is 82% empty and partly stale (tree is live).
   Confirm: `grep -c '◻\|✅' docs/crosscutting/platform-parity.md` vs 39 UCs.
   **Done 2026-07-14 — dropped, option B.** Today's coverage model *is* the regeneration: a use-case declares
   its target surfaces (`- **Surfaces:**`), coverage proves the live ones, and a build backs the answer. The
   matrix's `✅` column was a hand-copy of that; its `N/A` column is now the declaration; its `http` column
   named a deleted directory. Its three genuine "intentional N/A" rationales were harvested into the use-cases
   that own them first — one of them better than what I had written there.

10. **`ui-web-styleguide.md` — wrong legacy source + phantom token system** *(agent).* Points to `legacy/frontend`
    (the rejected E2EE-wrong-concept descendant) instead of `legacy/frontend-noe2ee`; mandates `--app-font-size-*`
    tokens that don't exist in `web/`. ~89 CRs behind.
    Confirm: `grep -rc 'font-size: var(--app-' web/src` → 0 · `grep -n 'legacy/frontend' docs/crosscutting/ui-web-styleguide.md web/CLAUDE.md`
    **Done 2026-07-14 — not a rewrite; four sections, all of them the *provenance* half.** ~80% of the
    styleguide is healthy: all 12 `.veyq-*` classes it mandates exist and are used, and the component / button /
    product-language / a11y rules are current. What was rotten was every claim about **where the design comes
    from**:
    - *"Canonical, current: `web/src/style.css`"* — it is an 86-line import barrel. The system is
      **`web/src/styles/`**, nine sheets, ~175 KB.
    - *"a minimal subset today (setup slice; **light-mode only**)"* — `styles/dark.css` is 12 KB. The same file
      demanded "every semantic color must define both light and dark" three lines later.
    - *"**grows** as UI lands"* / *"port it from legacy when you need it"* — it never grew token by token. It was
      **vendored whole**, verbatim, from `legacy/frontend-noe2ee/src/styles/`. `style.css`'s own header comment
      says exactly this; the styleguide describes a strategy that was abandoned before it started.
    - the source: `legacy/frontend` → **`legacy/frontend-noe2ee`**, also in **`web/CLAUDE.md`**, which is the more
      harmful of the two — it is loaded by every web session and told the reader to port from the rejected
      E2EE-wrong-concept descendant.

    **The token claim was narrower than the title.** `--app-*` is alive and heavily used (`--app-muted` 184×,
    `--app-line` 166×, `--app-text` 129×). Only `--app-font-size-*` is phantom — **0 definitions, 0 uses, and it
    never existed**. Sizes are raw rem, clustering at 0.78/0.8/0.82/0.85/0.9/0.92/0.95/1rem, while the same
    paragraph told readers to "avoid one-off `1rem` labels" (there are 14). That section now says there is no
    scale, states the rule that actually carried the intent (match the surrounding density), and notes that
    naming a real scale is an ADR plus a ~100-site migration — not a styleguide line. **An unfollowable rule is
    worse than an absent one:** the reader greps, finds nothing, and either invents tokens or ignores the
    section. Both happened.

11. **`developer-styleguide.md` still says `http/` "verifies the contract"** *(verified).* cr-0159 removed this
    stance everywhere else; the binding styleguide §3/§11 was missed by the sweep.
    Confirm: `grep -n 'verifies the contract\|contract-level coverage' docs/crosscutting/developer-styleguide.md`
    Do: align with ADR 0040 (http is a dev aid, not a coverage surface). Also `:31` lists
    `shared/{security,content,keymgmt,storage}` — 4 of 6 don't exist (`ls backend/.../shared/`).

12. **Bucket authz doc drift** *(agent).* `en.memory.buckets.md` describes owner-gated; cr-0123 relaxed to
    member-gated. A reader infers a stronger guarantee than the code gives.
    Confirm: `grep -n 'owner_key == caller\|owner-gated' docs/features/memory/enablers/en.memory.buckets.md` vs `MediaHandler.kt` bucket ops.
    Do: correct the enabler to member-gated; also `:41` claims a `UNIQUE` partial index that `V35` doesn't create.

13. **Phantom `ACTIVE membership` state** → **[cr-0168](cr-0168-strike-phantom-active-membership.md)** — **done**,
    behind a fourth invariant in `validate_scope_model_vocabulary.py`.
    ~~`scopes-and-tenancy.md:94`, `ADR 0008:79`, `developer-styleguide.md:53` — 3 sites~~ → **5**. `:94` is really
    `:96`, and **both** `uc.admin.create-tenant.md` sites (`:42`, `:64`) were missed entirely, because they phrase
    the phantom differently — "`ACTIVE` **member**" and "ACTIVE **tenant-owner**", never the literal this
    finding's own confirm-command greps for. **This is the meta-lesson in its sharpest form: the
    confirm-command *was* the finding's definition of the defect, so the two agreed with each other and both were
    wrong.** (ADR 0008:79 fixed in `abec9887`; the other four in cr-0168.)
    ~~"server authz needs no grant"~~ — it does: the key-holder check (`holdsGrant`) is gate dimension #4 and the
    residual **403**. The boundary is `isScopeMember` **first**, *then* the grant — the order is the property
    (ADR 0042).
    **The phantom is `ACTIVE` applied to a *membership*, never the word** — it is a real state on the **tenant**
    (`PENDING → ACTIVE`) and **invite** (`ACTIVE → SUPERSEDED / CONSUMED / EXPIRED`) axes, 8 legitimate lines
    across 4 files. A flat strike destroys them; that is why the guard keys on adjacency.

14. **Dead i18n keys = documented-but-never-built features** *(agent).* `share.expires`, `unlock.untrust`,
    `unlock.trustFailed` are defined, unreferenced, and each maps to a UC-documented feature that was never built.
    Confirm: for each key, `grep -rc "'<key>'" web/src` → 0 refs beyond the definition.
    Do: per key, either build it or mark the UC section deferred (the doc currently over-promises).

## Tier 3 — structural (the soil the drift grows in)

15. **No authorization matrix** → **[cc.security.authorization](../docs/crosscutting/cc.security.authorization.md)**
    (`abec9887`) — **done as a map, not a matrix**. The gap was real; every `(verified)` number stating it was
    wrong, and the load-bearing claim was false. Corrected in cr-0168 alongside #13 — the same defect at two
    altitudes.
    ~~"30 call sites, 6 handlers"~~ → **90** guard lines across **15** files under `features/`, of which **7** are
    `Handler` and **6** are `Store` classes — the fused query predicates no call-site scan can see; the guard
    *declarations* are 10 further lines in `shared/membership/MembershipStore.kt`.
    `grep -rnE '\b(isScopeMember|isMember|holdsGrant|isScopeOwner|isTenantOwner|ownerCount|memberRole|canView|canUpload|passwordCheck)\b' backend/src/main/kotlin/com/veyq/app/features/ --include='*.kt' | wc -l` → **90**
    ~~"16 handlers"~~ → **16 `Resource` classes *plus* 14 `Handler` classes** — two different nouns
    ([ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)), conflated into one count.
    ~~"two role systems"~~ → **three** vocabularies at **four** definition sites. `SERVER_OWNER`/`MEMBER`
    (`shared/identity/ServerRoles.kt`) was missed entirely — it is the one *declarative* gate — and
    `features/admin/tenant/Entities.kt` is a second, unheld copy of `OWNER`/`MEMBER`.
    ~~"This was the root cause of cr-0162/0163"~~ — **neither CR says it**
    (`grep -rn -i 'root cause' changes/cr-016{2,3}*.md` → **0**), and this was the claim that would have built the
    **wrong artifact**. Both CRs recorded that a static check is the *wrong* instrument for guard **order**, as do
    [ADR 0042](../docs/decisions/0042-http-failure-projection.md):104 and `OperatorBlindProbeTest.kt:29`.
    ~~"ideally a **generated** gate matrix, so it can't drift"~~ — a generated matrix cannot drift *and cannot
    fail*: derived from the code, it can never disagree with it. On the day cr-0163 shipped it would have printed
    `isTenantOwner` and been **green** — the defect was *inside* the predicate. The concern doc is therefore the
    seven gate **dimensions** + the six **rules** that decide which apply; see its *Known limits*.

16. **`inbox.md` bloat + phantom header path** *(verified).* 82k chars / ~20k tokens, 104 CR refs — the file every
    refine session reads first; its header points to `docs/wiki/`, which doesn't exist (structure is `docs/` +
    separate `wiki/`).
    Confirm: `wc -c docs/inbox.md` · `ls docs/wiki` → not found · `grep -n 'docs/wiki/' docs/inbox.md`
    Do: split the inbox by area (its own trigger, long overdue); fix the header path; move done-chronicle prose to
    git history.

17. **arc42** — **done 2026-07-14, in one edit. This finding's premise was false.**

    It called for "arc42 **+ adoc** conversion" and derived a *"hard precondition: adoc breaks 5 of 7
    validators silently"*, hence "its own multi-phase CR; the validator conversion is phase 1 and gates the
    rest". **arc42 is not AsciiDoc.** It is a *section structure*, published in markdown among other formats.
    The precondition never existed, no validator was touched, and the multi-phase CR evaporated. The finding
    fused a structure with one of its file formats and then planned months of work around the fusion.
    **Reusable error: check whether a standard mandates the thing you think it mandates, before pricing it.**

    It was also right about one thing and wrong about its weight. Right: arc42 is a *communication* format, not
    a drift fix. Wrong: that made it sound optional. The section that earned the change is **§11 Risks and
    Technical Debt** — six live debts (this file included) that had no single address and were scattered across
    an ADR's prose, two `gaps:` fields, an audit doc and a dead workflow. §10 (Quality Requirements) and §12
    (Glossary) were also simply missing. The page grew 192 → 299 lines, all of it those three sections; the
    pre-existing ones got *shorter*, because the enabler roll-call became a pointer (#8).

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

~~**#15 (authorization matrix, generated)** — it is the root cause of the two escalation CRs … and a generated
matrix can't re-drift.~~ — **done 2026-07-14** (`abec9887` + cr-0168), and **all three of its premises were
false**: neither CR names a root cause, a generated matrix can't re-drift *because it can't fail*, and the item
it argued for is the one that would not have caught either escalation. What shipped is a **map** — dimensions +
rules. See #15.

~~**#17 (arc42)** is the big one and depends on the validator-conversion precondition.~~ — **done 2026-07-14
in one edit; the precondition was imaginary** (arc42 is not AsciiDoc). See #17.

**#16 (inbox)** unblocks every future refine.

> **Read this section's own track record before trusting its next ranking.** Of the items it sized: Tier 1 was
> billed a "quick warm-up" and its inventories were 3×, 10× and 4.6× under-reported; #15's three premises were
> all false; #17 was "the big one, blocked on a precondition" that did not exist; #8 offered two options and
> the right answer was a third (stop making the claim). **This file is good at noticing that something is
> wrong and bad at saying what it costs or what to do** — which is what an audit can be, and worth keeping in
> mind: take the *observation*, re-derive the *plan*.

**Read this before picking anything:** every *(agent)*-trust finding below still carries an unverified inventory,
and Tier 1's track record is now 3-of-4 wrong — always under-counted, because the confirm-command was narrower
than the defect. **Re-ground with a deliberately *wider* command than the finding's**, and reconcile the two
numbers before planning against either.
