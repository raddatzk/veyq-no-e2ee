---
id: cr-0166
title: Tenancy roles + the scope-key model — correct the docs to the code (ADR 0007/0014/0024)
status: done
satisfies: []            # doc-hygiene over crosscutting + ADRs; no uc./en./cc. target (cf. cr-0038)
adrs: ["0006", "0007", "0012", "0014", "0024"]
invariants: []           # nothing at risk: the code, the contract and ADR 0024 are already correct — only docs are wrong
---

## Goal

Two drifts in the same two crosscutting documents describe a veyq that does not exist: a **phantom `ADMIN`
role** and an **asymmetric scope keypair**. Both are worse than a gap — a reader who *checks* is confirmed in
the wrong belief (a dead `const val ADMIN` makes a naive grep corroborate the wrong doc; the "scope private
key" prose is the canonical definition of a *grant*). Correct `docs/` to the model that the code, the
contract and [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) already implement, delete the dead
constant, and promote the proof to a permanent guard so neither can silently return.

Findings #1 + #2 of [the 2026-07-14 doc-vs-code review](review-2026-07-14-doc-drift-findings.md). **No decision
is made here** — this CR only makes the docs say what is already true.

## Context & constraints

- **Both findings were under-sized; the re-grounding is the inventory below.** #1 said "2 spots", it is 6 in
  `scopes-and-tenancy.md`; #2 said "~2 spots", it is 21 phrase-hits across 5 files *plus* what a phrase-grep
  cannot see (two PlantUML diagrams alias the concept as `spk`, and one ADR carries a semantic residue with no
  matching phrase).
- **The code and the contract are on the right side — this is a docs-only defect.**
  `grep -rniE 'scopeKeyPair|scopePrivateKey|scopePublicKey|wrappedScopePrivateKey' backend/src web/src contracts/`
  → **0**. The real field is `wrappedGrant`, and `contracts/api/scopes.openapi.yaml:38` already describes it
  correctly ("the scope key sealed to a member's identity public key"). `wrappedScopePrivateKey`, which
  `scopes-and-tenancy.md` shows three times as an API payload field, **never existed**.
- **The root cause is citable, and it is a process one.** [ADR 0024](../docs/decisions/0024-scope-key-rotation.md)'s
  Consequences enumerate the downstream docs it invalidates — it names ADR 0012 / 0020 / 0021, and those were
  executed and are clean. It never names `0006`, `0007`, `security-model.md`, `scopes-and-tenancy.md` or
  `uc.admin.create-tenant.md` — **exactly the five that still say "keypair"**. The drift is the unexecuted tail
  of a correctly-identified list, which is why the fix here ends in a guard rather than a sweep.
- **Authority is unambiguous.** ADR 0024 (Accepted, 2026-07-05) is the mechanism; ADR 0007 / 0006 (both
  Accepted, 2026-06-26) predate it by ~5 weeks and are stale on this point. Neither is marked `Superseded`, so
  both read as live while being factually wrong — the reason this ranks Tier 1 rather than cosmetic.
- **The risk this closes is native.** `apple/`/`android/` are parked ([ADR 0043](../docs/decisions/0043-web-first-v1-native-parked.md));
  when they un-park, `security-model.md` + ADR 0007 are exactly what an implementer reads for the key model.
  The crypto vectors would eventually red a scope-keypair implementation — but only after it was written.
- **Not an invariant risk today:** the running system is correct. This is a correctness-of-the-map defect.

## Decisions

1. **The role vocabulary is `OWNER` / `MEMBER`, at every level — there is no `ADMIN`, and no tenant tier
   either.** ADR 0007 already carries this whole-model: `:22` ("at **every** level (Server, Tenant, Group)" a
   scope has owners), `:42` (server = `OWNER`/`MEMBER`), `:47` (group = `OWNER`/`MEMBER`), `:57` (the owner
   email goes to a `SERVER_OWNER` or the tenant's `OWNER` — no third role is offered it). The contract agrees
   (`scopes.openapi.yaml:829`: "`OWNER` (co-owner) or `MEMBER`"). So `scopes-and-tenancy.md`'s **tenant** row is
   as wrong as its group row; the finding only caught the latter.

2. **The scope key is symmetric; "keypair" is the wrong noun and "rotate" the wrong mechanism.** Per ADR 0024,
   `K_SC` is a chain of random 32-byte epochs `E0, E1, …`; a **grant** is `asymWrap(memberIdentityPub, E_latest,
   "scope-grant")` — asymmetric *transport* of a **symmetric payload**. Revoking a member mints a *new epoch*
   granted to the remaining members; nothing is re-encrypted and no keypair rotates. The docs must name the
   **identity** keypair (real) and the **invite-wrapping** keypair (real) as such, and never speak of a scope
   private/public key. `contracts/crypto/vectors/v1.json:115-117` pins the grant plaintext at **32 bytes**,
   which is the byte-level proof: an identity private bundle would be ~140.

3. **Delete the dead `const val ADMIN`** (`shared/membership/MembershipEntity.kt:76`) — it is referenced
   nowhere (`grep -rn 'ADMIN' backend/src` → the definition only) and its only effect is to corroborate the
   wrong doc.

4. **The guard is lexical, and that is the point.** Unlike cr-0162/0163 — where the defect was a *condition*
   inside a query and only a probe test could hold it — this invariant **is** vocabulary: certain nouns must not
   appear in `docs/` because the things they name do not exist. A grep expresses it exactly. Add a Python
   validator (`scripts/`, per the Python rule) holding two invariants:
   - **docs:** the scope-asymmetric vocabulary does not occur in `docs/`. The phrases have **no** legitimate use
     — the identity and invite-wrap keypairs are named differently — so this is a flat zero-hit check with no
     allowlist.
   - **code ↔ contract:** `MembershipEntity`'s role constants equal the role enum in
     `contracts/api/scopes.openapi.yaml`. This is what makes decision 3 stick: it fails the day a third constant
     returns, and it needs no allowlist either.

   The `ADMIN`-in-prose case is the one that resists a flat grep — ADR 0007:47 and 0014:66 legitimately say
   "no `ADMIN`" (2 of the 5 hits). Implementation picks the discrimination (a negative-lookbehind, or an
   allowlist of those two lines) and the CR does not script it; what is **not** acceptable is dropping the
   check or rewording the ADRs to make the grep easy — "no `ADMIN`" is precisely the signpost a future reader
   greps for.

5. **Scope addition — the same two diagrams carry a second, independent drift.** Both label the layer under the
   scope key `"Per-subtree content keys"` (`security-model.md:113`, `scopes-and-tenancy.md:61`), a model ADR
   0012 **explicitly rejected** (`0012:17` "a full per-subtree key tree — rejected") in favour of per-Memory
   `K_mem`. Included because we are rewriting those exact diagram lines anyway and leaving a known-false label
   in a diagram we just touched is worse than not touching it. It pulls in one consequence: `0012:11` says, in
   the present tense, "The Security Model **sketches** per-subtree content keys" — true today, false the moment
   this lands, so its tense follows.

6. **Explicitly out of scope** (named so they are not silently absorbed):
   - the **"tenant-admin guard"** naming in `developer-styleguide.md:54` and `0008:39,51,79` — the guard is
     really `isTenantOwner`; that is finding #15's (authorization matrix) territory, not a vocabulary fix.
   - the phantom **`ACTIVE membership`** on `scopes-and-tenancy.md:94` — one line above our `:95` edit, but it
     is finding #13 with its own confirm-command and its own answer (ADR 0042).

## Inventory

**#1 — the `ADMIN` phantom.** `grep -rn '`ADMIN`' docs/ | grep -v inbox` → **5**, of which **2** are the
legitimate negative form (ADR 0007:47, 0014:66) → **3 defects**, all in one file:

| Site | Says | Truth |
|---|---|---|
| `scopes-and-tenancy.md:108` | Tenant roles `OWNER · ADMIN · MEMBER` | `OWNER · MEMBER` |
| `scopes-and-tenancy.md:109` | Group roles `ADMIN · MEMBER` — **omits `OWNER` entirely** | `OWNER · MEMBER` |
| `scopes-and-tenancy.md:135` | owner email visible to `OWNER`/`ADMIN` | `SERVER_OWNER` or the tenant's `OWNER` (ADR 0007:57) |

Plus **3** lowercase prose lines in the same file that seed the phantom
(`grep -n -iE 'group admin\|tenant admin\|admins manage' docs/crosscutting/scopes-and-tenancy.md` → `:23`,
`:85`, `:95`), and **1** dead constant (`grep -rn 'ADMIN' backend/src` → `MembershipEntity.kt:76`).

**#2 — the scope-keypair phantom.**
`grep -rniE 'scope keypair|scope key pair|scope-keypair|scope private key|scope public key|wrappedScopePrivateKey' docs/ | grep -vc inbox`
→ **21 lines / 5 files**:

| File | Phrase-hits | What is there |
|---|---|---|
| `docs/crosscutting/scopes-and-tenancy.md` | 8 | the `spk` diagram rectangle; the grant definition (`:72`, `:80`, `:102`); the invite-wrap "passenger" (`:144`, `:149`, `:155`); `:180` "scope-keypair rotation" |
| `docs/crosscutting/security-model.md` | 4 | the `spk` diagram rectangle (`:112`); the per-member wrap note (`:126-129`); `:134` add-a-member; **`:135` "rotate the scope keypair"** |
| `docs/decisions/0007-membership-role-grant-relation.md` | 1 | **`:17-18` — the canonical definition of "grant"** |
| `docs/decisions/0006-cold-tenant-scope-birth.md` | 2 | `:11`, `:27` — cold birth "generates the scope keypair" |
| `docs/features/admin/use-cases/uc.admin.create-tenant.md` | 3 | `:39`, `:40`, `:62` (incl. a PlantUML sequence step) |

**What the phrase-grep cannot see — confirmed by reading, not by grep** (changes/README: a null result counts
only once you've confirmed the search hit the right place):
- **~6 PlantUML alias lines** bound to the `spk` rectangle (`scopes-and-tenancy.md:65,66,67,71`;
  `security-model.md:119,120,121,125`) — they read `spk --> ck`, so they carry the wrong name without the phrase.
- **3 payload-field lines** naming `wrappedScopePrivateKey` (`scopes-and-tenancy.md:158,163,166`) — a field that
  does not exist; the contract's is `wrappedGrant`.
- **`0012:25`** — "the scope key unwraps it", implying an intermediate key between the grant and `K_SC`. Per ADR
  0024 and the code the grant plaintext **is** `E_latest`; there is no intermediate. Last fossil inside an
  otherwise-updated ADR.

**Already correct — do not touch** (they are the model to match): `en.spaces.scope-key-rotation.md`,
`en.spaces.group-scope.md:60`, `en.spaces.personal-scope.md:46`, `0020:25`, `0012:24,60-62`,
`security-model.md:175`, and every *identity*-keypair and *invite-wrap*-keypair mention.

## Plan

Single phase — docs + one dead constant + one validator. No contract change, no behaviour change, so there is
no `contracts → backend` seam to walk.

1. **Docs** — the two crosscutting docs (prose + both PlantUML diagrams), ADR 0006, ADR 0007, ADR 0012's two
   residues, `uc.admin.create-tenant.md`. Done when: `plantuml -checkonly` passes on both rewritten diagrams and
   `python3 scripts/validate_doc_links.py` is green.
2. **Backend** — delete `MembershipEntity.ADMIN`. Done when: `./gradlew check` green with **no test touched**
   (if a test references it, stop: the constant was not dead and decision 3 is wrong).
3. **The guard** — the validator from decision 4, wired where the other `scripts/` validators run. Done when:
   it is **proven to bite** (below) and green on the corrected tree.

## Verify

- `python3 scripts/validate_doc_links.py`, `validate_changes.py`, `validate_coverage.py` — green.
- `plantuml -checkonly` on `security-model.md` + `scopes-and-tenancy.md` (per the PlantUML rule in
  `docs/features/README.md`).
- `./gradlew check` in `backend/` — green, **no test file changed**.
- **Enforcement guard, proven to bite** — snapshot-and-restore per `changes/README.md`, never `git checkout`:
  `cp` the file to a snapshot, re-introduce "scope private key" into `security-model.md` → the validator must
  red; restore from the snapshot and confirm (grep the marker). Repeat for the code↔contract half: re-add
  `const val ADMIN` → red. **A validator that finds nothing reports success on an empty set** — so the bite-proof
  is the only evidence that the check is wired to the right tree, and "CI green" is not.
- Read-back check that no *legitimate* keypair lost its name: `grep -rc 'identity keypair\|identity public key' docs/`
  must not drop.

## Definition of done

- No document describes a scope **keypair**, a scope **private/public key**, or `wrappedScopePrivateKey`; the
  grant reads as "the scope's current content-epoch key, sealed to the member's identity public key", matching
  ADR 0024 and `contracts/api/scopes.openapi.yaml:38`.
- No document lists a membership role other than `OWNER` / `MEMBER`; `scopes-and-tenancy.md:109` names `OWNER`.
- `MembershipEntity` has exactly the constants the contract's role enum has.
- Both diagrams name per-Memory `K_mem`, not a per-subtree key tree; `0012:11`'s tense follows.
- The invariants are held by a validator that has been **watched to red**, not by this CR's prose.

## Retrospective

Delivered as planned: docs 21 → 0 on the scope-asymmetric grep, `` `ADMIN` `` 5 → 2 (both the legitimate
negative form), `MembershipEntity.ADMIN` deleted with `./gradlew check` green and **no test file touched** (so
decision 3 held — it was genuinely dead), and `scripts/validate_scope_model_vocabulary.py` wired into the CI
guard step and **watched red on all four injected defects**. What the plan got wrong:

- **The vocabulary ban was checked against the text it *replaced*, never against the text it would *create*.**
  Decision 4 asserts the scope-asymmetric phrases have "no legitimate use", hence a flat zero-hit check with no
  allowlist. But the natural way to *state* the correction in ADR 0007 is the negative form — and that is
  exactly what I wrote ("…so there is no scope keypair…"), meaning my own new sentence would have red-flagged
  the guard I was about to write. The CR foresaw precisely this for `` `ADMIN` `` (insisting the "no `ADMIN`"
  signpost survive) and assumed it away one paragraph earlier for the scope key. Rephrased to a positive
  assertion (`K_SC` **is** symmetric, a random 32-byte epoch key), which corrects a reader harder than the
  negative would — so the flat check survives exactly as decided.

- **"The contract's field is `wrappedGrant`" was right for one of the three lines and wrong for two.** The
  inventory treats `scopes-and-tenancy.md:158,163,166` as one defect with one fix. But `:158`/`:163` are the
  *invite envelope* — `K_SC` sealed to a throwaway invite-wrapping public key, which is **not** a grant — so
  renaming them `wrappedGrant` would have swapped one wrong name for another. And `inviteWrappingPublicKey`
  exists nowhere in `contracts/` either: the entire invite-wrap flow is uncontracted target model, so those
  diagram field names are illustrative, not citable. Only `:166` (accept) is a real `wrappedGrant`; `:158`/`:163`
  became `wrappedScopeKey`. Root cause: the CR checked the contract for the *noun* and not for whether the
  *flow* carrying it exists — a near-miss of the same phantom-endpoint trap (cr-0044's `/api/token`) that
  `changes/README` names.

- **The CR's own `plantuml -checkonly` gate was itself an unproven check.** The CR is emphatic that "a validator
  that finds nothing reports success on an empty set" and demands a bite-proof for the *new* validator — then
  lists `plantuml -checkonly` on two `.md` files as a plain green gate. Whether PlantUML reads fenced blocks out
  of markdown at all is the very same question, and a silent no would have made both diagram rewrites unverified.
  Extended the bite-proof to it (inject a syntax error → exit 200), so that green is now evidence. The
  scepticism had been aimed only at the check we were adding, not at the one we inherited.

- **One scope call, named rather than buried:** decision 5 scoped the per-subtree fix to the two *diagram*
  lines, but `scopes-and-tenancy.md`'s "Shares are least-privilege" bullet sits directly under the diagram and
  said "the *specific* subtree's content key". Aligning the diagram alone would have left two nouns for one
  thing on one screen, so the bullet moved to `K_mem` too — beyond the letter of decision 5, inside its stated
  reason.

- **Process:** the go signal has a hole one day after we cut it. "weiter mit cr166" is neither the bare
  reference (= ground + plan) nor one of the named go forms (`cr166 go` / `start` / `los`) from the cr-0160 retro
  convention. Read as a go — fresh session, fully-refined CR, directive verb — and flagged as such before
  anything was touched, but it was a judgement call the convention exists to remove.

- **Next time:** when a CR bans a vocabulary, draft one sentence of the *replacement* prose during refinement and
  run the proposed guard's own regex over it. The ban's premise ("no legitimate use") is a claim about the docs
  the CR will **write**, not the ones it deletes — and it is checkable at refine time for free.
