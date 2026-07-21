---
id: cr-tenant-role-and-v24-comment
title: myTenants role keys on the root scope (#93) + the V24 name-seal comment (#92)
status: done
satisfies: []
adrs: ["0007", "0027"]
invariants:
  - Operator-blind E2EE — membership is coarse metadata; no key or plaintext is touched
---

## Goal

Two small fixes spun off from the #58 space-icon refine, independent of each other:

- **#93 (a real bug):** `ScopesStore.myTenants` reads a tenant's role via `memberships.first().role` over
  memberships grouped by `tenantId` (`ScopesStore.kt:404`). Every scope birth mints its creator an `OWNER` row
  carrying that `tenantId` — a member's own personal-scope auto-birth guarantees one — so `.first()` over an
  unordered `find` can return the personal `OWNER` row and report `MyTenant.role = OWNER` for a plain member.
  Not an escalation (`myTenants` is a display field, gates are pinned elsewhere) — a wrong badge in the switcher.
  Same shape as cr-0163, in a spot that sweep did not reach.
- **#92 (a stale comment):** `V24__scope_encrypted_name.sql:1` says the group name is "sealed under its own
  scope key `K_SC`". Since cr-0096/ADR 0027 it is sealed under a *derived* `K_name`, not directly under `K_SC`.
  The code (`Entities.kt:31`) and the OpenAPI (`scopes.openapi.yaml:131`) already say so; only the append-only
  migration's comment lags.

## Context & constraints

- The canonical rule the fix mirrors: `MembershipStore.kt:34-39` (cr-0163, ADR 0007) — belonging is by
  `tenantId`, every **role** check keys on the root scope (`scopeId == tenantId`).
- Regression guard on the fix: belonging is by `tenantId` across *all* the user's scopes
  (`MembershipStore.kt:30`), so the role selection falls back to `MEMBER` when no root-scope row is present —
  a tenant the caller belongs to is never dropped from the switcher.
- #92's home is ADR 0027 (the derived name-key); the migration body stays (append-only).

## Phases

Single phase (backend + a migration comment; no contract/web change).

- [x] `ScopesStore.kt` — role = the `scopeId == tenantId` membership, `?: MEMBER`.
- [x] `V24__scope_encrypted_name.sql` — comment points at ADR 0027 / derived `K_name`.
- [x] `ScopesSliceTest.kt` — a member with a personal-scope `OWNER` row (same `tenantId`) must show `MEMBER`.
- [x] `docs/inbox.md` — mark #92 + #93 done.
- [x] `./gradlew check` green; bite-proof the new test (revert the fix → red).

## Definition of done

`./gradlew check` green, the new slice test **bites** (reverting `myTenants` to `.first().role` turns it red),
and the trust-chain stays green. No coverage YAML change (backend bug + doc comment, no `satisfies:`).

## Retrospective

- **Planning (went well):** the regression risk in the fix — that `firstOrNull { scopeId == tenantId }` would
  *drop* a tenant if a member lacked a root row — was surfaced by reading `MembershipStore.kt:30` ("belonging
  is by tenantId") *before* writing the edit, not after. The `?: MEMBER` fallback came from that read, not a
  review. This is the "grep the invariant before the sentence" habit paying off on a cross-scope role rule.
- **Execution (the bite mattered):** the bug is order-dependent (`.first()` over an unordered find), so a
  naive assertion test could have silently passed on the buggy code. Seeding the personal-OWNER row *before*
  the root-MEMBER row made H2's insertion scan order expose it deterministically — proven by the bite (revert
  → 1 failure, restore → green). Without that ordering the test would have been a flaky non-guard.
- **Next time:** one line — for any "`.first()`/`firstResult()` over an unordered query" fix, the regression
  test must control seed order so the pre-fix code fails deterministically, or it proves nothing.
