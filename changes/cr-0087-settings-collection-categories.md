---
id: cr-0087
title: Settings hub — collapsible collection categories (Groups/Tenants) with per-entity detail pages
status: done             # planned → in-progress → done (kept as a record)
satisfies: []            # cross-cutting web IA — realizes ADR 0022's collection fan-out; re-homes group/tenant management, no new use-case
adrs: ["0022", "0014", "0007"]
invariants: [operator-blind-e2ee]
---

## Goal

Grow the settings hub from flat, active-scope-contextual sections into a **management hub for collections**:
the section rail gains **collapsible categories** — **Gruppen** (every group the caller **owns**) and, for a
Server Owner, **Tenants** (every tenant they administer) — each expanding to list its entities; selecting one
opens **that entity's own detail page** (`/settings/group-<id>` / `/settings/tenant-<id>`) — the collection
fan-out of [ADR 0022](../docs/decisions/0022-settings-information-architecture.md) §2, as an **accordion in the
rail** (two panes) rather than a third column.
The top-bar **gear becomes context-aware**: opening it in a group lands directly on **that group's** page. Groups
and tenants land together; a member-only group isn't listed (nothing to manage) but its page stays reachable
read-only. Web-only; native inherits the same category → entity → detail via its split-view scaffold (ADR 0010),
a later increment.

## Context & constraints

- Durable truth: [ADR 0022](../docs/decisions/0022-settings-information-architecture.md) (the settings IA — §1
  the context-aware gear, §2 the accordion collection fan-out, §3 the management-hub scope + role-per-scope),
  building on the hub itself ([cr-0078](cr-0078-web-settings-hub.md)). The group member detail is [uc.spaces.add-group-member](../docs/features/spaces/use-cases/uc.spaces.add-group-member.md)
  (cr-0086); the tenant detail is [uc.admin.create-tenant](../docs/features/admin/use-cases/uc.admin.create-tenant.md)
  (cr-0009/0050). This CR **re-homes + restructures** their existing web panels — it adds no new server capability
  beyond the one below.
- Reuses: the built `GroupMembersPanel`/`TenantMembersPanel` (cr-0086/0077), the tenant-admin data layer
  (`listTenants`/reassign/resend/revoke, cr-0050) + `CreateGroupDialog`/`CreateTenantDialog`, `listMyGroups`
  (cr-0032), the `/settings/:section?` route + the NavigationSplitView shell (cr-0078). `TenantAdminPanel` is
  **dissolved** into the Tenants category (list + create) + the new per-tenant `TenantDetailPanel`.
- New surface (the only new server capability): `GET /api/scopes` surfaces the caller's **own `role`** per scope
  on `ScopeSummary`, so the client can list the groups it **owns** without a directory round-trip per group.
  Role is already stored on the membership ([ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md));
  exposing *my own* role is operational metadata — **operator-blind untouched**.
- Invariants at risk:
  - **Operator-blind** — the scope list still returns only operational metadata (id/kind/tenant/grant/name/**role**),
    never a key; the group/tenant detail panels show only pubkeys/roles/grant-present/tenant name+domain. No path
    surfaces a key or a sealed name to the server.
  - **Owner-gating stays code-enforced** ([ADR 0008](../docs/decisions/0008-authorization-by-code.md)) — the client
    listing only owned groups is a convenience; the add/admit + tenant-admin ops remain 403-gated server-side
    regardless of what the rail renders.

## Phases

- [x] 1 contracts + backend — add optional **`role`** to `ScopeSummary` (the caller's role in that scope:
  OWNER/MEMBER); thread it through `ScopeRow` + `ScopesStore.listMine` (from the caller's membership row) +
  `ScopesResource`. Slice test: `GET /api/scopes` carries the caller's role per scope (OWNER on their own
  group/personal, etc.). · Done when: `openApiGenerateSpecs` + web `generate:api` clean + `./gradlew test` green.
- [x] 2 web — groups collection + per-group detail. The settings rail's category headers become **collapsible**;
  a **Gruppen** category lists the caller's **owned** groups (`listMyGroups` filtered by `role=OWNER`, names
  decrypted on-device) as items + a **＋ new group** action (the existing `CreateGroupDialog`); selecting one
  routes to `/settings/group-<scopeId>` → `GroupMembersPanel` **parameterized by the route scopeId** (not
  `activeSpace`), so any owned group is manageable without entering it. The **gear** (`/settings`, no param)
  resolves its default to the active scope's entity — in a group → `group-<activeScopeId>`. The sidebar **＋ new
  group** stays; the Gruppen category offers it too. The active-group-contextual "Gruppe → Mitglieder" section is
  retired (replaced by the category). · Done when: `npm run check` green + live: gear in a group lands on that
  group's page; the Gruppen category lists owned groups → open one → the member directory + add works; a
  member-only group's page opens read-only (no add form).
- [x] 3 web — tenants collection + per-tenant detail (Server Owner). Split `TenantAdminPanel` into a **Tenants**
  category (lists tenants via `listTenants` + a **＋ create tenant** action) and a **per-tenant detail** panel at
  `/settings/tenant-<tenantId>` (that tenant's status + edit invite / resend / revoke, the existing ops for one
  tenant). The gear resolves to the tenant members page when the Tenant scope is active (unchanged axis). · Done
  when: `npm run check` green + live: the Tenants category lists tenants → open one → its admin ops work; create
  still works from the category.
- [x] 4 coverage — extend `uc.spaces.switch-spaces.yaml` (the scope list carries the caller's `role`, backend
  slice ref) + update the `SettingsHubView`/`GroupMembersPanel` web tests for the category/detail nav. · Done
  when: `python3 scripts/validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

The settings rail shows collapsible **Gruppen** (owned groups) and **Tenants** (managed, Server Owner) categories;
selecting an entity opens its own detail page, so any owned group's members / any managed tenant is configurable
without switching the active scope. The top-bar gear opens directly on the active scope's entity page (a group →
that group's page). Operator-blind verified: the scope list adds only the caller's own role; no key or sealed
name reaches the server; owner/admin ops stay 403-gated server-side. Coverage + the three trust-chain guards
green; live smoke. **Out of scope (later):** group rename/delete/roles (G3) as further sections on the group
detail page; native parity (own CR); the desktop overlay look for the hub (ADR 0022 §1, cosmetic).

## Retrospective

- **Planning — dissolving `TenantAdminPanel` cascaded further than the phase text implied.** "Split into a
  category + detail" sounded contained, but it deleted a component whose test carried three `uc.admin.create-tenant`
  coverage refs — so the real work included re-pointing coverage (create → `CreateTenantDialog`, list →
  `SettingsHubView`, invite-link/revoke → new `TenantDetailPanel`) and porting its test. Caught by the coverage
  guard, not by the plan. Next time: when a phase says "split/dissolve an existing panel", grep its coverage refs
  up front and list the re-point as an explicit sub-task.
- **Execution — one real bug, caught before it shipped: the `tenant-` prefix ate `tenant-members`.** The detail
  resolver matched `id.startsWith('tenant-')` before `id === 'tenant-members'`, so the Tenant members section
  would have routed to a bogus `TenantDetailPanel(tenantId='members')`. Fixed by ordering the exact match first.
  Root cause: overlapping id namespaces (`tenant-members` vs `tenant-<uuid>`); a cleaner scheme would namespace
  collection entities distinctly (e.g. `tenants/<id>`), worth considering if a third collection lands.
- **Process — went well.** Refined state (ADR 0022, in place — no addendum, honoring the just-pinned rule) landed
  before the CR; the one new server field (`role`) stayed minimal and everything else was reuse/restructure; the
  three guards + both platform gates stayed green; live-smoked both collections end to end.
- **Next time:** for accordion/collapsible nav, the test must expand a collapsed category before asserting on its
  items — I hit empty-item lookups twice writing the tests. Bake an `expandCategory()` helper into the harness.
