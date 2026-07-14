---
id: cr-0163
title: The tenant-owner gate keys on the root scope — close the privilege escalation (ADR 0007)
status: done
satisfies: ["uc.spaces.manage-member-invites", "uc.spaces.remove-tenant-member", "uc.spaces.add-member", "uc.admin.tenant-terms"]
adrs: ["0007", "0023"]
invariants: [operator-blind-e2ee, least-privilege]
---

## Goal

Fix a **privilege escalation**: the tenant-owner gate counts OWNER memberships by `userId + tenantId + role`
**without** filtering on the tenant root scope (`scopeId == tenantId`). Since *every* membership row carries
`tenantId` and a scope birth mints its creator an `OWNER` row, the gate answers true for ordinary members — it is
functionally equivalent to `isMember`. Key the condition on the root scope, collapse its three copies onto one
shared primitive, and pin it with a probe test that seeds the actor no test seeds today.

Closes [inbox #86](../docs/inbox.md), found while implementing [cr-0162](cr-0162-membership-boundary-first-guard-ordering.md).
Same call sites, **different defect class**: cr-0162 fixed guard *order* (an enumeration oracle), this fixes a
guard *condition* (who passes).

## Context & constraints

- **Severity is higher than inbox #86 states.** #86 says "any tenant member **who creates a group**". Creating a
  group is not required: [`ScopesStore.createPersonal`](../backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesStore.kt)
  persists the personal-scope membership with `role = OWNER` and the tenant's `tenantId`, and personal-scope birth
  is **auto-birth on first entry** in the normal onboarding path ([web/src/features/spaces/scopes.ts](../web/src/features/spaces/scopes.ts),
  `ensurePersonalScope`). So **every tenant member is a tenant owner** for this gate. Groups are only a second
  route to the same row.
- **What the gate protects** (all of it, for every member, today): mint / list / re-issue / revoke member invites,
  remove a tenant member *including the ACL-revoke cascade* (`TenantHandler.removeMember` — deletes memberships,
  deactivates shares, queues rotations), set the tenant terms, and read the **owner-only member emails**
  ([ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md)) — so it is a confidentiality leak too, not
  only an authorization defect.
- **It is a bug, not intent, and the code says so.** In `TenantHandler.removeMember` the **target**-side lookup
  filters `it.scopeId == tenantId` correctly one line below the wrong **caller**-side gate; and the sibling
  `TenantStore.tenantOwnerCount` delegates to `MembershipStore.ownerCount`, which **does** key on `scopeId`. The
  last-owner guard and the access gate therefore disagree today on who a tenant owner is.
- **[ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md) is not the cause — it already describes
  the real model.** Its *"**Personal** scope is self-held — no membership row beyond the member's own grant"*
  reads, in the cardinality list it belongs to, as "exactly one row, the member's own, never a foreign one" —
  which is precisely what `createPersonal` persists. So the buggy condition had no mitigating premise in the
  state docs: it was wrong from the start. What the ADR lacks is not a correction but the **rule** that would
  have made the defect class un-writable (decision 3).
- Builds on cr-0160's `shared/membership/MembershipStore` (the query-only leaf) and lands after
  [cr-0162](cr-0162-membership-boundary-first-guard-ordering.md), whose hoisted membership guard already runs
  above these owner checks — this CR changes only the condition the owner check evaluates.
- **No contract change.** Statuses and codes are unchanged for every caller; `not_tenant_owner` keeps its meaning
  and finally becomes reachable for the callers it was written for.
- **No crypto, no key path, no migration** (see `## Migration`).

## Decisions

1. **The condition is the root scope.** A tenant owner is a user holding an `OWNER` membership on the tenant
   **root scope** (`scopeId == tenantId`, the keying from cr-0026). `tenantId` alone expresses *belonging*, never
   *authorization*.

2. **One home, a named delegate.** Add `MembershipStore.isTenantOwner(userId, tenantId) = isScopeOwner(userId, tenantId)`
   — the correct query already exists as `isScopeOwner`; what is missing is the *named* place where the
   `scopeId == tenantId` keying knowledge lives. `TenantStore.isOwner` and `ScopesStore.isTenantOwner` delegate to
   it and lose their own queries. This mirrors the already-correct `TenantStore.tenantOwnerCount → ownerCount(tenantId)`
   exactly, and takes the three copies of the query to one.
   *Rejected — call `isScopeOwner(caller, tenantId)` directly at the sites.* Saves a method, but spreads the
   "the tenant root scope is keyed `scopeId == tenantId`" premise back across the call sites, which is the shape
   that produced three drifting copies in the first place. The gate should read as what it gates.

3. **Add the binding rule to [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md):** **`tenantId`
   expresses belonging; every role check keys on `scopeId`.** That is the defect *class*; the three copies were
   its instances. Nothing else in the ADR changes — it already describes the model correctly.

4. **The guard is a probe test, not a grep.** Same reasoning cr-0162 recorded: the defect is a *condition* inside
   a query, so no grep expresses it. The invariant is behavioural — "an ordinary tenant member is refused at every
   owner-gated endpoint" — and only a test that seeds that actor can hold it.

## Inventory

| Site | Today | After |
|---|---|---|
| `TenantStore.isOwner` — gates 5× `TenantHandler` (invite, list invites, reissue, revoke, removeMember) | `userId + tenantId + role=OWNER` | delegates to `MembershipStore.isTenantOwner` |
| `TenantStore.isOwner` — gates `TenantTermsHandler.update` | same query | same delegate |
| `ScopesStore.isTenantOwner` — gates the owner-only email in `ScopesHandler.listMembers` (ADR 0023) | verbatim copy of the same query | same delegate |
| `MembershipStore.isTenantOwner` | does not exist | the single home (thin, over `isScopeOwner`) |
| `TenantStore.tenantOwnerCount`, `TenantHandler.removeMember`'s target-side lookup | already key on `scopeId` | **unchanged — the reference pattern** |

Line numbers deliberately omitted (they drift); the symbols above are greppable.

**Test blast radius — expected to be zero flips.** Only two assertions pin `not_tenant_owner`
(`MemberRemoveSliceTest`, `TenantTermsSliceTest`) and both seed a *bare* outsider (no personal scope, no group),
so they pass today for the wrong reason and keep passing for the right one. Per cr-0162's retro the blast radius
keys on **which actor a test seeds**, not on which code it asserts: the actor that flips is "tenant member **with
a scope of their own**", and a grep of the seed helpers says no test seeds one at an owner-gated endpoint. That
absence *is* the finding — it is why the suite never saw this. **If an existing assertion flips, stop:** it means a
legitimate owner lost access and the condition is wrong.

## Surface impact

- **web** — after the fix, a member who is not a tenant owner no longer receives the `email` field on member
  directory rows (ADR 0023 as designed; they receive it today by mistake). Implement must grep the member panels
  for reliance on `email` and confirm the group-owner panels render without it. Group-owner capabilities are
  gated by `isScopeOwner`, not by this gate, and are unaffected.
- **apple / android** — parked (web-first V1); no client behaviour is defined by this gate.

## Plan

Single phase, backend + docs — no contract change, so no `contracts → backend` seam to walk.

1. **ADR 0007** — add the `tenantId`-is-belonging rule (decision 3).
2. **Backend** — `MembershipStore.isTenantOwner` + both call sites delegating; the two feature-store queries
   deleted. Done when: `./gradlew check` green **with no existing assertion changed**.
3. **The guard + coverage** — `TenantOwnerGateTest` (below) + coverage refs for the four `satisfies:` use-cases.
   Done when: it reds on a reverted condition and `python3 scripts/validate_coverage.py` is green.

## Verify

- `./gradlew cleanTest check` in `backend/` — green, **no existing assertion touched**.
- **Enforcement guard (permanent, carries the invariant):** a table-driven `TenantOwnerGateTest` seeding an
  **ordinary tenant member who owns scopes** — personal scope auto-born *and* a self-created group, i.e. exactly
  the actor no test seeds today — and firing every owner-gated endpoint: the 5 `TenantHandler` ops,
  `TenantTermsHandler.update`, and `listMembers` (asserting `email` is **null** on the rows). All must refuse.
  Verified to bite: reverting the condition to the `tenantId`-only query must red it. Mirrors
  `OperatorBlindProbeTest` (cr-0162) in shape — the same lesson, the other axis: that one varies the *scope id*,
  this one varies the *actor*.
- A real tenant owner still passes every one of those endpoints (the same test, second actor) — the fix must not
  over-tighten.
- Live check on `veyq.localhost:8081` per `## Surface impact`: a non-owner member's directory renders without
  emails.
- `python3 scripts/validate_changes.py` + `validate_doc_links.py` + `validate_coverage.py` green.

## Migration

**None.** The escalation is *computed per request*, not persisted — there is no wrong row to repair, and the
`OWNER` rows on personal/group scopes are correct data (they are owners *of those scopes*). Only side effects a
member actually performed while wrongly passing the gate would be real (minted invites, removed members, terms
edits); there is no production instance and no audit log, so there is nothing to reconstruct. Recorded here so the
question is not re-opened silently.

## Definition of done

- No owner gate keys on `tenantId` alone — the condition exists **once**, in `shared/membership/`.
- An ordinary tenant member who owns their own scopes is refused at every owner-gated endpoint and sees no member
  emails — proven by `TenantOwnerGateTest` over the full endpoint table, not by inspection.
- A real tenant owner's access is unchanged (no existing assertion flipped).
- ADR 0007 carries the `tenantId`-is-belonging rule.
- `docs/inbox.md` #86 closed with the corrected severity (every member, not only group creators).

## Retrospective

Done. `./gradlew cleanTest check` green; **no existing assertion flipped** (the CR's stop-signal never fired) —
`MemberRemoveSliceTest` 9/9 and `TenantTermsSliceTest` 4/4 stayed green, confirming the fix refuses only the
escalation actor. `TenantOwnerGateTest` 2/2, **verified to bite** (reverting `isTenantOwner` to the `tenantId`-only
query reds the escalation case). Three copies of the owner query → one `MembershipStore.isTenantOwner`; ADR 0007
carries the belonging-vs-authorization rule; all guards green. **Live smoke passed** (user-run, no restart —
backend-only): a non-owner member's directory renders without member emails on `veyq.localhost:8081`.

- **The CR's blast-radius note was right in conclusion but wrong in detail — and the detail mattered.** It said
  the two `not_tenant_owner` tests "seed a *bare* outsider (no personal scope, no group)". Grounding against the
  code showed `seedTenantMember` actually seeds an OWNER-less **MEMBER row on the root scope** (`scopeId ==
  tenantId, role = MEMBER`), and `MemberRemoveSliceTest` even seeds its refusing caller via `seedTenantMember`, not
  a bare `seedUser`. The conclusion held (role=MEMBER matches neither the old nor the new query → no flip), but
  only because I checked what the helper *actually* seeds rather than trusting the CR's paraphrase. Had
  `seedTenantMember` defaulted to OWNER, an existing assertion would have flipped and "zero flips" would have been
  wrong. The lesson is this session's thesis in miniature: a refine-time claim about a seed helper ("seeds a bare
  outsider") is a checkable fact — I verified it, and it was imprecise. **Next time:** the blast-radius table
  should cite the seed helper's *actual role/scope output* (greppable), not a prose characterisation of the actor.
- **The escalation actor the suite never had is now a first-class fixture.** The whole defect survived because no
  test seeded "tenant member who owns a non-root scope". Writing `TenantOwnerGateTest` around exactly that actor —
  personal-scope OWNER + self-group OWNER, no root-scope OWNER — is the durable value; it is the actor a future
  owner-gate regression would need, pre-seeded.
- **The restore protocol from cr-0165's retro worked on first use.** Snapshot the working file (`command cp -f …
  /tmp/…snap`) before the bite-injection, restore from the snapshot, never `git checkout` a file with uncommitted
  work. The bite-proof reverted cleanly with zero drama — the counter-example to cr-0164's `cp -i` hang and
  cr-0165's `git checkout` over-generalisation. Two blown restores, then a written protocol, then a clean one:
  evidence that the fix has to be an executable protocol, not a remembered lesson (the point we're carrying into
  the workflow rework).
- **Refinement quality was high and it showed.** The CR was refined at this session's *start*, before any of the
  code moved (cr-0164/0165 landed between). Yet its inventory — both buggy queries verbatim-identical, `isScopeOwner`
  present, `isTenantOwner` absent, `tenantOwnerCount` already the reference pattern, 7 call sites, 2 test files —
  matched the current code **exactly** on grounding. That is what a refine that names symbols (and greps that they
  exist) buys: a clean-room implement session proceeded from the doc with no surprises. The one gap (the seed-helper
  paraphrase above) is small and specific — the model to keep.
