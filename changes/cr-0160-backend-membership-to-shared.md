---
id: cr-0160
title: Backend membership → shared/membership/ — extract the entity + guard, dedupe 5 stores + 2 slice cycles (audit P3a, ADR 0007)
status: done
satisfies: []
adrs: ["0007"]
invariants: []
---

## Goal

Implement the placement decision added to [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md):
move `MembershipEntity` + the read-only membership/owner guards out of the `admin/tenant` feature slice into a
shared, query-only `shared/membership/` module, and collapse the duplication onto it (audit finding **P3a**,
[inbox #71](../docs/inbox.md)). Today `MembershipEntity` lives in `features/admin/tenant/Entities.kt` and **8
cross-slice sites import it from the admin slice**; the membership guard is reimplemented **verbatim in 5 stores**
and the owner-count (last-owner) guard is duplicated across **2 feature slices**. This is a **behaviour-preserving
refactor** — the authz guards must resolve exactly as before; `./gradlew check` (the full slice-test suite across
content/media/sharing/spaces/admin) is the proof.

## Context & constraints

- Realises **ADR 0007** (`adrs: ["0007"]`) — its "the relation is a *shared* primitive" decision. Pure backend
  refactor: **no use-case satisfied**, no contract/crypto change, no migration (the table + columns are
  untouched; `MembershipEntity`'s `@Table("veyq_membership")` mapping is package-independent).
- **Operator-blindness is unaffected** — membership role/owner-count are already server-visible operational
  metadata ([ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md)); this only relocates the code
  that reads them. But the guards **are authz**, so the refactor must be exactly behaviour-preserving — no guard
  may resolve differently after the move.
- **Feature-slice architecture** ([ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)):
  `shared/membership/` must stay a **leaf** (entity + stateless queries) — it may not import `ScopeEntity`
  (spaces) or `TenantEntity` (admin); the last-owner **policy** (raising `LastOwner`) stays in the feature
  handlers. This keeps `features → shared` one-directional (no cycle).
- **Grounding (from the code):** `MembershipEntity` is coupling-free (only `jakarta.persistence`), so the move is
  clean; it currently shares `admin/tenant/Entities.kt` with the genuinely-tenant entities, so the entity is
  **split out**, not the file moved. The richest guard implementation is `features/spaces/scope/ScopesStore.kt`
  (`isMember`/`isScopeMember`/`isScopeOwner`/`ownerCount`/`memberRole`/`holdsGrant`) — those queries are the
  shared API. `TasksStore`'s `MembershipEntity` use is grant/scope **enumeration**, not a guard — it is not a
  duplicate; it only updates its import.

### The duplication to collapse

- **5× verbatim `isMember`** (`MembershipEntity.count("userId = ?1 and scopeId = ?2", …) > 0`):
  `content/NodesStore.kt`, `content/favorites/FavoritesStore.kt`, `media/MediaStore.kt`, `sharing/SharingStore.kt`,
  `spaces/scope/ScopesStore.kt` (each also imports the entity from `admin/tenant`).
- **2 slice-cycle owner-count guards** (last-owner, ADR 0007): `spaces/scope/ScopesStore.ownerCount` +
  `ScopesHandler` (demote/remove) **and** `admin/tenant/TenantStore.tenantOwnerCount` + `TenantHandler` — the same
  `count("scopeId = ?1 and role = ?2", …, OWNER)` query on the scope vs. tenant-root axis.

## Phases

- [x] 1 backend (extract) — create `shared/membership/`: split `MembershipEntity` (+ the `OWNER`/`ADMIN`/`MEMBER`
      role constants) out of `features/admin/tenant/Entities.kt` into `shared/membership/MembershipEntity.kt`
      (package `com.veyq.app.shared.membership`); add a leaf `MembershipStore` (`@ApplicationScoped`, mirroring
      `shared/identity/*Store`) exposing the guard queries lifted from `ScopesStore`
      (`isScopeMember`/`isScopeOwner`/`ownerCount`/`isMember`(tenant)/`memberRole`/`holdsGrant`). Update the ~8
      cross-slice imports to the new package · Done when: `./gradlew check` (in `backend/`) green (compile + all
      slice tests, zero behaviour change).
- [x] 2 backend (delegate) — rewire the **5 verbatim `isMember`** stores and the **2 owner-count** guards to
      delegate to the shared `MembershipStore` (inject it; delete the hand-written `count(...)` queries and the
      per-slice `tenantOwnerCount`), leaving only scope/tenant orchestration + the `LastOwner` policy in the
      feature handlers · Done when: `./gradlew check` green; no feature re-implements the membership/owner query.

## Out of scope

- **The last-owner policy / error mapping** stays in the feature handlers — only the query primitives move.
- **`TasksStore`'s membership queries** (grant/scope enumeration, not a guard) — import update only, no dedup.
- **The Result/Failure error-mapper** (that is the sibling P3b, #72 — its own CR).
- **No coverage-YAML changes** — test method names are unchanged, so every existing coverage ref still resolves.

## Verify

- `./gradlew check` (in `backend/`) **green** after each phase — the full slice-test suite across
  content/media/sharing/spaces/admin proves every membership/owner guard resolves identically (behaviour-preserving).
- **No stale coupling:** `grep -rn "features.admin.tenant.MembershipEntity" backend/src/main` returns nothing;
  `grep -rn 'count("userId = ?1 and scopeId = ?2"' backend/src/main` matches **only** `shared/membership/`.
- `python3 scripts/validate_changes.py` + `python3 scripts/validate_doc_links.py` green (this CR + the ADR 0007
  amendment resolve).

## Definition of done

`MembershipEntity` + the membership/owner guard queries live in `shared/membership/` (a query-only leaf); the 5
verbatim `isMember` copies and the twin owner-count guard delegate to it; no feature imports the entity from
`admin/tenant` and no guard query is duplicated outside `shared`; the last-owner policy stays per-handler.
`./gradlew check` green (behaviour-preserving proof) + `validate_changes`/`validate_doc_links` green. Inbox #71
marked **done → cr-0160**.

## Retrospective

Done, behaviour-preserving, `./gradlew check` green after each phase. Two things the plan under-counted, both
mechanical fallout of the move rather than scope creep:

- **The import sweep was wider than "~8 cross-slice".** `TenantStore` + `TenantHandler` referenced
  `MembershipEntity` from their *own* (old) package with no import line — splitting the entity out forced an
  explicit import into both. And **22 test files** imported the entity too (16 cross-slice + 6 admin/tenant
  same-package); all had to move or `compileTestKotlin` fails. Lesson: an entity relocation's blast radius is
  "every referencing compilation unit", not just the main-source importers the audit counted.
- **Delegation went past the enumerated 5+2.** Phase 1 lifted the *full* guard set into `MembershipStore`
  (`isScopeOwner`/`memberRole`/`holdsGrant`/tenant-`isMember` alongside the 5 `isScopeMember` + owner-count), so
  stopping Phase 2 at the literal "5 verbatim + 2 owner-count" would have left those four duplicated between
  `ScopesStore` and `shared` — contradicting the DoD's "no guard query duplicated outside `shared`". Resolved by
  delegating *all* of `ScopesStore`'s now-shared guards + `TenantStore.hasMembership`, so `MembershipStore` is the
  sole implementer of all six and nothing it exposes is dead. `TasksStore`/`ScopePurge`/`MediaStore` genuine
  enumeration and the handlers' `MembershipEntity.OWNER`/`MEMBER` role constants were left untouched (not guards).

Guard grep now matches **only** `shared/membership/MembershipStore.kt` for all three query shapes
(`userId+scopeId`, `scopeId+role`, `userId+tenantId`); `features.admin.tenant.MembershipEntity` returns nothing.
