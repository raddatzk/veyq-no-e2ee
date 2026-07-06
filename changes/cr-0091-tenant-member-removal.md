---
id: cr-0091
title: Remove a tenant member — ACL-revoke cascade + the group orphan rule
status: done             # planned → in-progress → done (kept as a record)
satisfies: [uc.spaces.remove-tenant-member]
adrs: ["0014", "0025", "0007", "0008"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a **TenantOwner remove a member** from the tenant — cut their **shared** access at once (delete their tenant +
shared-group memberships; **no** tenant-scope rotation, [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md)),
apply the **orphan rule** to groups they solely owned (transfer to the oldest remaining member, else grace-tombstone,
[ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §6), and **grace-tombstone the member's own scopes
with read access kept** (their personal scope + any solo group) so they can read/export their own memories before
erasure ([uc.spaces.remove-tenant-member](../docs/features/spaces/use-cases/uc.spaces.remove-tenant-member.md)). The
**owner-removes-others** direction (Fork 2A); self-leave and the co-shared-group rotation backstop are follow-ups.

## Context & constraints

- Durable truth: [uc.spaces.remove-tenant-member](../docs/features/spaces/use-cases/uc.spaces.remove-tenant-member.md),
  [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §6 (orphan rule), [ADR 0025](../docs/decisions/0025-forward-secrecy-policy-per-scope.md)
  (tenant = ACL-revoke, no rotation), [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md) (last-owner).
- Reuses: the tenant member directory (`listScopeMembers` on the tenant scope, cr-0077) + per-tenant identity
  (cr-0083); the **cr-0090 soft-delete/grace/purge** (`ScopeEntity.deletedAt`, `ScopesStore.softDelete`, `ScopePurge`,
  `eraseScope`) — applied to the member's own scopes; the group role/membership helpers (`isTenantOwner`,
  `ownerCount`, `memberRole`, `setRole`, `removeMembership`, `scopeKind`, cr-0088); the tenant members panel
  (`TenantMembersPanel.vue`).
- Invariants at risk:
  - **Operator-blind — and the server legitimately does this.** Membership deletions + soft-deletes are access-
    control + erasure the blind server may perform (opaque rows + a timestamp; no key, no name). The **tenant
    content key is not rotated** (ADR 0025).
  - **Last-owner guard** — a tenant must keep ≥1 OWNER ([ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md));
    the tenant scope needs the guard the group scope already has (`ownerCount` on `scopeId == tenantId`).
  - **Humane erasure** — the member's **own** scopes are grace-tombstoned with their **membership kept** (read-only
    grace, then purge), not nuked on removal; only **shared** memberships are deleted instantly.
- New surface: a **`removeTenantMember`** op (the whole cascade), the **tenant last-owner guard**, store helpers to
  enumerate a user's tenant footprint + find the **oldest** remaining member, and the tenant-panel remove action.

## Phases

- [x] 1 contracts — `DELETE /api/tenants/{tenantId}/members/{userId}` (`operationId: removeTenantMember`):
  tenant-owner-gated (`403 not_tenant_owner`); the **last-owner guard** rejects removing the last tenant OWNER
  (`409 last_owner`); `404 member_not_found`. No `ScopeSummary` change — the member's own soft-deleted scopes surface
  via the existing `deletedAt` (cr-0090). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [x] 2 backend — the **`removeTenantMember`** handler + store cascade (owner-gated, last-owner-guarded): enumerate
  the target's memberships in the tenant (`userId + tenantId`); for each scope by kind —
  **TENANT + shared GROUP** (has other members) → **delete** the membership; a **GROUP the target solely owns with
  other members** → promote the **oldest** remaining member (`min createdAt`) to OWNER, then delete the target's
  membership; **PERSONAL + a solo GROUP** (target the only member) → **`softDelete`** (grace-tombstone) and **keep**
  the target's membership (read-only grace). Store helpers: `membershipsInTenant(userId, tenantId)`,
  `oldestOtherMember(scopeId, exceptUserId)`, `tenantOwnerCount(tenantId)`. Slice tests: shared memberships gone;
  sole-owned-with-members group transferred to the oldest; personal + solo group soft-deleted with membership kept;
  last-tenant-owner guard rejects; non-owner `403`; a subsequent `ScopePurge` erases the grace-tombstoned scopes.
  · Done when: `./gradlew test` green.
- [x] 3 http — `removeTenantMember` unauth + owner-gated boundary in `http/…/tenants.http` (happy path stays
  integration-covered). · Done when: the unauth assertions hold headless.
- [x] 4 web — a **remove** action in `TenantMembersPanel` (owner-only, inline confirm) + the `removeTenantMember`
  data layer (in `members.ts`). · Done when: `npm run check` green + live: an owner removes a member → they drop out
  of the tenant directory; (the removed member's read-only grace on their own space is the interactive two-account
  check).
- [x] 5 coverage — fill `uc.spaces.remove-tenant-member.yaml` (drop `status: planned`): remove-shared-access /
  orphan-transfer / orphan-tombstone / own-scope-grace-read / last-owner-guard / operator-blind → the backend
  `…SliceTest` refs + the web data-layer test + the `http` boundary. · Done when: `validate_coverage.py` +
  `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A TenantOwner removes a member → their **shared** (tenant + co-shared group) access is cut immediately; groups they
**solely owned** are transferred to the oldest remaining member or grace-tombstoned; their **own** scopes (personal +
solo groups) are grace-tombstoned **with read access kept**, then erased by `ScopePurge`. Tenant **last-owner
guarded**; operator-blind (only opaque rows + a timestamp; the tenant key is not rotated). Coverage + guards green.
**Out of scope:** the **async group-rotation backstop** for groups the member merely co-shared (Fork 1b — the
layer-1 cut is immediate; forward-secrecy rotation is completed later by a remaining member, a follow-up slice);
**self-leave** ("leave a tenant", Fork 2B); native (apple/android) parity.

## Notes

- **`DELETE /api/tenants/{id}/members/{userId}`, not the scope endpoint.** Removal is a tenant-level **cascade**
  (many memberships + the orphan rule + own-scope tombstoning), so it lives beside the tenant invite endpoints —
  distinct from the scope-level `removeMember` (a single group membership, cr-0088).
- **Why the co-shared-group rotation is deferred (Fork 1b).** Group rotation is client-driven/operator-blind; the
  removing tenant owner isn't necessarily a key-holder in those groups, so the server **cannot** rotate them. The
  layer-1 cut is immediate; the rotation rides the async backstop (a follow-up). This is the same "rotation queued
  until a key-holder is online" already accepted for group removal (uc.spaces.manage-group-members A2).
- **Read-only grace = free reuse.** The member's own soft-deleted scopes are read-only via the cr-0090 guard (fires
  on `deletedAt`); keeping their membership is the only twist, so they retain read access during the window.
