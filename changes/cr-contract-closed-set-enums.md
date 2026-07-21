---
id: cr-contract-closed-set-enums
title: Declare the role/status closed sets as contract enums (#103)
status: done
satisfies: []          # contract-hardening; fulfils no use-case acceptance criterion
adrs: ["0007", "0014", "0021"]
invariants: []          # not an operator-blindness change — role is `structure`, status is `public` (cr-0171); neither marker depends on E2EE
---

## Goal
Four response fields are a closed set in their *description* but a bare `string` in the schema, so a generated
client cannot switch exhaustively on them: `role` in `MyTenant` / `ScopeMember` / `ScopeSummary` and
`JobStatus.status`. Narrow each to a declared `enum` — the type gets tighter, no semantics move. The values are
taken from the backend that writes them, not invented.

## Context & constraints
- Inbox #103 ("Closed sets the contract does not declare"), split out of the cr-0171 scalar backfill.
- The refine-question #103 flags: *a declared `enum` makes every new value a breaking change for generated
  clients — which is exactly why error `code`/`error` stay open strings.* Resolved **yes, close them**, because
  unlike the deliberately-open `code` namespace these two are bounded by design:
  - **`role` → `[OWNER, MEMBER]`.** Deliberately closed: `validate_scope_model_vocabulary.py` rejects any third
    role as a phantom ("no `ADMIN`", ADR 0007/0014), and the sibling `SetMemberRoleRequest.role` **already**
    declares the enum. The bare read-side `string` was an inconsistency, not intended openness.
  - **`JobStatus.status` → `[pending, failed]`** (lowercase). Ground-truth: `JobStatus` is constructed only by
    `failTask` and `declineTask` (`TasksResource.kt`), both via `.lowercase()`; `fail` writes `FAILED`/`PENDING`,
    `decline` writes `PENDING` → the wire union is exactly `pending`/`failed`. This is the **response** domain,
    **not** the 4-value column lifecycle (`PENDING/LEASED/DONE/FAILED`, `JobEntity`) — a wider enum would claim
    the field can carry values it never does.
- Source of truth is `contracts/api/` (CLAUDE.md, API-first). Backend + web clients are generated; the diff is
  large but only the type narrows.

## Two guards — verified, neither needed a change (the point-2 coupling did NOT trigger)
- **`validate_contracts_common.py`** (enum drift, #87 F6) already carries the `membership-role` family
  `(MEMBER, OWNER)`. The three new `[OWNER, MEMBER]` enums match it exactly (`values == canon`) → no drift, the
  family is simply matched by more copies. `[pending, failed]` overlaps no family (0) and is single-spec, so it is
  correctly outside the guard's cross-spec scope — a single-spec enum cannot drift across specs.
- **`validate_scope_model_vocabulary.py`** anchors on `SetMemberRoleRequest.role.enum`
  (`ROLE_SCHEMA`, line 67/124) and holds it equal to `MembershipEntity`'s role constants. Left untouched →
  stays green. **This is why the role fields use inline enums, not a named `ScopeRole` `$ref`:** re-homing the
  role enum into a shared schema would break this guard's anchor and force the guard + its docstring + the
  expectation to move together (the #62 coupling). Inline mirrors the existing `SetMemberRoleRequest` precedent in
  the same file and keeps both guards green with zero guard edits.

## Phases
- [x] 1 contracts — `enum` on the 4 fields (`maxLength: 16` dropped; enum is the constraint, mirrors
      `SetMemberRoleRequest` :836) · Done when: `for f in scripts/validate_*.py; do python3 "$f"; done` green
- [x] 2 backend — regen narrows the generated types; map String→enum at each construction site ·
      Locator (the compiler names every site): `cd backend && ./gradlew compileKotlin` — resolved 5 sites:
      `ScopesResource.kt` (MyTenant/ScopeMember/ScopeSummary, `.Role.valueOf(...)`) + `TasksResource.kt`
      ×2 (`JobStatus.Status.valueOf(r.status.lowercase())`) · Done when: `./gradlew check` green
- [x] 3 web — regen narrows `.role`/`.status` to string-literal unions; `=== 'OWNER'` reads still hold ·
      Locator: `cd web && npm run typecheck` — resolved 2 test-fixture sites in `scopes.test.ts` (annotate the
      `remaining` arrays `: ScopeMember[]`) · Done when: `npm run check` green

## Definition of done
`./gradlew check` + `npm run check` green; all 13 trust-chain guards green; inbox #103 → done. No `satisfies:`
use-case, so no coverage delta (`validate_coverage.py` green, unchanged). No new guard: the invariant (role is a
closed 2-set) was already enforced by `validate_scope_model_vocabulary.py`; this CR makes the contract agree with
it rather than adding enforcement.

## Retrospective
- **Planning miss (mine, small):** I first weighed a named `ScopeRole` schema (the repo's dominant convention —
  `ShareRole`/`InviteRole`/`ScopeKind`) before the second guard surfaced. Only reading
  `validate_scope_model_vocabulary.py` showed its anchor is `SetMemberRoleRequest.role.enum`, which a `$ref`
  would break. Root cause: I inventoried the *drift* guard first (it was named in the task) and found the
  *vocabulary* guard only via the value-set spike. **Next time:** when a task says "check the guard", grep
  `scripts/` for *every* guard touching the symbol before choosing a shape, not just the one named.
- **Estimate vs. measured (process win):** inbox #103 estimated "~19 test refs and 5 in web"; the compiler-as-
  locator found **0** backend-test and **2** web sites. Here the bare estimate *over*-counted (the usual repo
  failure is under-counting) — either way the lesson holds: run the locator, don't ship the tally. The generated-
  code compiler is the strongest locator available and named every site for free.
- **Environment (human/process):** the working tree carried uncommitted `cr-space-icon` WIP in the same file
  (`scopes.openapi.yaml`) at start — caught by `git status` before any edit. The mechanical fix was blocked purely
  by two CRs sharing one file; splitting the go into "wait until space-icon lands, then #103 as its own commit"
  kept the commits clean. **Next time:** at the top of any contracts-phase CR, `git status` first — a dirty shared
  spec is a stop-and-confirm, not a proceed.
