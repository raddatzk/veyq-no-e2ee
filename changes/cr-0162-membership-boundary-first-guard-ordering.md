---
id: cr-0162
title: Membership boundary first — close the scope-existence oracles (operator-blind, ADR 0042 amendment)
status: done
satisfies: []
adrs: ["0042"]
invariants: [operator-blind-e2ee, non-enumerable]
---

## Goal

Close the **scope-existence enumeration oracles** in the backend and make the operator-blind status convention a
*structural* property rather than a per-site judgement. Realises the [ADR 0042](../docs/decisions/0042-http-failure-projection.md)
**amendment**: the membership boundary is the **first** guard on every scope-addressed operation; capability
checks (grant-holder, owner) run after it and may answer 403, because a member legitimately knows the scope
exists.

This supersedes [inbox #83](../docs/inbox.md) as originally written. The refine inventoried the whole class and
found the finding wrong **in both directions** — it named three sites of which only one leaks, and missed eight
that do. See `## Inventory`.

## Context & constraints

- **Behaviour-changing, security-motivated.** Unlike [cr-0161](cr-0161-http-failure-projection.md) (which was
  deliberately behaviour-preserving and only made the drift visible), this CR changes what a **non-member** sees:
  403 → 404. It does **not** change what any legitimate member sees.
- **The defect is guard *order*, not the status tag.** Existence leaks when an operation probes existence (or
  scope **kind**) before the membership guard, so the branches answer differently. Flipping a `HttpFailure` tag —
  the literal fix #83 proposed — would fix two non-bugs and leave the real leaks standing.
- Builds directly on cr-0161's `shared/http/` projection and cr-0160's `shared/membership/MembershipStore`
  (`isScopeMember` / `holdsGrant` / `isScopeOwner`) — no new primitive is needed; this CR **reorders guards** and
  retags the residual failures.
- **Grant ≠ membership** (ADR 0020). `isScopeMember` is the confidentiality boundary; `holdsGrant` is a capability
  *within* it. A member with pending consent holds no grant but must not be told the scope does not exist.
- Contracts are the source of truth → the spec examples/responses change before the backend
  ([contracts/api/scopes.openapi.yaml](../contracts/api/scopes.openapi.yaml),
  [tasks.openapi.yaml](../contracts/api/tasks.openapi.yaml)).
- No crypto change, no migration, no client behaviour change for legitimate users (see `## Surface impact`).

## Decisions

1. **Membership-first ordering** on every scope-addressed operation:

       if (!isScopeMember(caller, scopeId)) return ScopeNotFound   // 404, uniform for strangers + bogus ids
       if (!holdsGrant(caller, scopeId))    return Forbidden        // 403, member without the key
       if (!isScopeOwner(caller, scopeId))  return NotOwner         // 403, member who is not the owner
       // …existence / kind / state (deleted, epoch) checks only from here on

2. **`not_scope_member` → renamed.** Once the boundary guard runs first, the residual 403 means exactly "member,
   but not a key-holder". The code is renamed to reflect that (`not_key_holder`; final spelling settled at
   implement time against the sibling code vocabulary). Cheap: the code is consumed by **no client** — it appears
   only in two spec examples, `Failures.kt`, two tests and one `.http` file.

3. **`ScopeNotFound` code mismatch resolved** (the second side-finding): `setRole`/`removeMember` emit
   `member_not_found` from the same `scopeKind` guard that makes its five siblings emit `scope_not_found`. With
   the boundary guard hoisted above it, all of them emit `scope_not_found`; `member_not_found` stays for the
   genuine "target member does not exist" case it names.

4. **`tasks.createJob` keeps gating on `grantedScopeIds`** but answers 404 for a non-member, matching the
   content/media/favorites siblings.

5. **Rejected — leave the uniform-403 sites alone.** `listMembers`/`tasks` are not leaks, so a minimal fix could
   skip them. Rejected: the same semantic would keep two statuses, which is exactly the drift ADR 0042 exists to
   kill, and the next reader cannot tell "uniform 403 by design" from "oracle" without re-deriving the guard
   order. Uniformity *is* the reviewable property.

## Inventory

Sites, and what changes for a **non-member** (nothing changes for a member):

| Site | Today | Leak? | After |
|---|---|---|---|
| `ScopesHandler.listEpochs` | existence → 404, then `holdsGrant` → 403 | **YES — live oracle** | member-guard first → uniform 404 |
| 8× owner-gated in `ScopesHandler` (rename, addGroupMember, resolveCandidate, setRole, removeMember, rotate, delete, restore) | `scopeKind` → 404, then `isScopeOwner` → 403 | **YES — oracles** (also leak scope *kind* via `NotGroup` 409) | member-guard first → uniform 404 |
| `not_tenant_owner` in `TenantHandler` (5×), `TenantTermsHandler.update` | `findTenant` → 404, then `isOwner` → 403 | Yes, same shape (lower severity — tenant domains are public by design) | membership-guard first |
| `ScopesHandler.listMembers` | `holdsGrant` first; bogus id also → 403 | No — uniform 403 | uniform 404 (consistency) |
| `tasks.createJob` | `grantedScopeIds` only, no existence probe | No — uniform 403 | uniform 404 (consistency) |
| content / media / favorites / sharing / tenant-terms read+accept | membership-guard first → uniform 404 | No | **unchanged — the reference pattern** |

Exact line numbers deliberately omitted (they drift); the guard shape above is greppable. `resolveCandidate`'s
existing "convenience, not a confidentiality barrier" argument covers *username* enumerability and does **not**
cover scope-id existence — it is in scope here.

**Explicitly out of scope — token-gated ops must NOT get the membership guard.** The rule applies to
*scope-addressed* operations, where the caller names a `scopeId` they may have guessed. It must **not** be applied
mechanically to the paths a non-member reaches *legitimately* by **possessing an unguessable token**: the
`@PermitAll` share-guest ops in [SharingResource.kt](../backend/src/main/kotlin/com/veyq/app/features/sharing/SharingResource.kt)
(`resolveShare`/`resolveShareBlob` + the contributor ops, gated on share state + token) and tenant
`preview`/`register`/`accept` in [TenantsResource.kt](../backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantsResource.kt)
(secret-/invite-gated; the accepting user is by definition not yet a member). Those callers already legitimately
know the resource exists, so their 403s (`share_password_incorrect`, `share_not_contributable`) are **correct**
per ADR 0042 and stay. Adding a membership guard there would break invite acceptance and public sharing. The
probe test's endpoint table is therefore "authenticated + scope-addressed", not "everything".

**Test blast radius** (asserts pinning today's 403; the actor decides whether it flips):

| Test | Actor | Change |
|---|---|---|
| `TasksSliceTest.kt` (`not_scope_member`) | outsider | → 404 `scope_not_found` |
| `MemberAddSliceTest.kt` (`not_scope_member`, listMembers) | outsider | → 404 `scope_not_found` |
| `ScopesSliceTest.kt` addGroupMember (`not_scope_owner`) | tenant member, **not** in the group | → 404 `scope_not_found` |
| `ScopesSliceTest.kt` ×4 (rotate, rename, removeMember, delete) | **group member**, not owner | **stays 403** `not_scope_owner` |

The `scopes.epochs` non-member path — the one real leak — is **untested today**. That gap is the reason it
survived.

## Surface impact

- **web**: unaffected for legitimate users. `not_scope_owner` is mapped to UI messages in
  [GroupMembersPanel.vue](../web/src/features/spaces/GroupMembersPanel.vue) (7×) + [members.ts](../web/src/features/spaces/members.ts)
  — those callers are group **members**, who keep their 403. `not_scope_member` is referenced by no client.
  Implement must still grep web for 403-handling on `epochs`/`listMembers` and confirm.
- **apple / android**: parked per the web-first V1 priority; no code references the renamed code. Regenerated
  clients pick up the spec change when those surfaces are next touched.

## Plan

1. **Contracts** — update the `scopes`/`tasks` spec responses + examples: the non-member case documents 404
   `scope_not_found`; `listMembers` gains its 404; the renamed key-holder 403. Done when: `npm run check` (web
   client regenerates) + `python3 scripts/validate_contracts_common.py` green.
2. **Backend guard ordering** — hoist the membership guard in the sites above; retag the residual failures;
   rename the code in `Failures.kt`; resolve the `ScopeNotFound` code mismatch. Done when: `./gradlew check` green
   with the four flipped assertions updated and the four member-actor assertions **untouched** (if a member-actor
   assertion needs changing, the guard order is wrong — that is the signal to stop).
3. **The guard** (below) + coverage refs. Done when: the probe test reds on a reverted guard.

## Verify

- `./gradlew cleanTest check` in `backend/` — green, with exactly the four assertions above flipped.
- **Enforcement guard (permanent, carries the invariant):** a table-driven `OperatorBlindProbeTest` that, for
  **every scope-addressed endpoint**, issues the same request as a **non-member** against (a) a real `scopeId` and
  (b) a random UUID, and asserts the two responses are **identical in status and `code`**. This is the definition
  of "no oracle" expressed as a test, and it is strictly better than a grep here: guard *order* is not greppable,
  and the test fails on any future endpoint that probes before it gates. A grep-based validator is explicitly
  **not** the right guard for this invariant — recorded so the next CR does not try to add one.
- New coverage for the `scopes.epochs` non-member path (the untested leak).
- `python3 scripts/validate_changes.py` + `validate_doc_links.py` + `validate_coverage.py` green.

## Definition of done

- No scope-addressed operation distinguishes "non-member" from "no such scope" — proven by `OperatorBlindProbeTest`
  over the full endpoint table, not by inspection.
- Every legitimate member's status + code is unchanged (the four member-actor assertions still pass untouched).
- ADR 0042's amendment is the written rule; `Failures.kt` / `HttpFailure.kt` no longer carry the "3 sites tracked
  by #83" framing (it is wrong — see the amendment).
- `docs/inbox.md` #83 closed with the corrected framing.

## Retrospective

Done. `./gradlew cleanTest check` green; the membership guard is now the first statement of all 10 scope-addressed
`ScopesHandler` ops, the 5 `TenantHandler` sites, `TenantTermsHandler.update` and `tasks.enqueue`. A non-member gets
404 everywhere, byte-identical to a bogus id; **every member-actor assertion passed untouched** — the CR's own
stop-signal never fired, which is the evidence the ordering was right. `OperatorBlindProbeTest` covers **35**
endpoints and was verified to actually bite: reverting `listEpochs` to its pre-CR order reds `scopes.listEpochs`
specifically, and restoring it goes green. `not_scope_member` → `not_key_holder` (the CR left the spelling open;
`not_key_holder` won — the sibling `not_scope_owner`/`not_tenant_owner` are `not_<role>`, and "key-holder" is the
term ADR 0020/0024 already use for a grant-holder; `not_scope_key_holder` bought nothing but length).

Four things the refine missed, all cheap here but worth carrying forward:

- **The blast-radius table under-counted the flips by two — it inventoried the *code*, not the *actor*.** It
  correctly listed the tenant sites in `## Inventory` ("`not_tenant_owner` … membership-guard first") but its test
  table only walked the `not_scope_member`/`not_scope_owner` tests, so `MemberAddSliceTest:51` and
  `MemberInviteManagementSliceTest:51` — both seeding a **non-member** outsider via `seedUser`, both pinning 403
  `not_tenant_owner` — were absent. Six assertions flipped, not four. The generalisable lesson: for a guard-order
  CR the blast radius is keyed on **which actor a test seeds** (`seedUser` = outsider → flips; `seedTenantMember` /
  group member → stays), not on which code it asserts. A refine can compute that: grep the tests for the seed helper,
  not for the code string. `docs/ai/README.md`'s "the inventory greps the *test* trees too" is right but not
  sufficient — it must grep them along the axis the change actually turns on.
- **The CR's site names were stale in three places** and cost a first-read reconciliation: `ScopesHandler` lives at
  `features/spaces/scope/`, not `features/scopes/`; **`tasks.createJob` does not exist** — the op is `enqueueTask` →
  `TasksHandler.enqueue` (`createJob` survives only in ADR 0042 prose + the spec's `JobCreated`); and
  `ListMembersOutcome` had no `ScopeNotFound` variant to retag (it had to be added). Per the refinement checklist
  this is exactly right — "name the inventory, let implement discover the exact where" — but a refine that names a
  symbol at all should have grepped that the symbol exists.
- **`tasks.enqueue` needed a new Outcome, which the CR read as a retag.** `TasksStore.enqueue` returned `String?`,
  collapsing "no grant" and "server-structural tag" into one `null`, so it structurally *could not* distinguish
  non-member from member-without-grant. Decision #4 ("keeps gating on `grantedScopeIds` but answers 404 for a
  non-member") is only implementable by lifting the membership guard into `TasksHandler` (which had to start
  injecting `MembershipStore` — `TasksStore` still queries `MembershipEntity` directly) and introducing
  `EnqueueOutcome`. Not scope creep, but it is a *new type*, not a tag flip — the kind of thing the "spike when the
  shape is unclear" rule exists for.
- **YAML flow-mappings ate a comma.** The compact `"404": { description: …, content: … }` form silently reparses an
  unquoted description containing `, ` into extra keys; `validate_contracts_common.py` and `yaml.safe_load` both
  passed, and only `openApiGenerateSpecs` caught it ("attribute … `ADR 0042).` is unexpected"). Worth knowing that
  the contracts' own validator does not cover this class — the generator is the real gate.

**Found in passing, out of scope, filed as [inbox #86](../docs/inbox.md):** the tenant-owner gate
(`TenantStore.isOwner`, and its verbatim copy `ScopesStore.isTenantOwner`) counts OWNER memberships by
`tenantId` **without** filtering on the tenant root scope, so any tenant member who creates a group — an O(1) call
— reads as a tenant owner. That is privilege escalation (invite/remove members, the ACL-revoke cascade, terms) plus
a leak of the owner-only member emails. Distinct defect class from this CR (guard *condition*, not guard *order*)
at the same call sites; agreed with the user to keep it a separate CR so this one's verify signal stays clean.

**Inbox hygiene:** id `83` is used **twice** — row 62 ("Slideshow") and row 137 (this topic). Closed row 137; the
duplicate is left as-is rather than renumbered, since ADR 0042, cr-0161 and cr-0162 all cite "#83" and a renumber
would break those references. Worth a rule (or a validator) before the inbox is split.
