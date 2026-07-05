---
id: cr-0032
title: Group scope — create, list, switch, browse (G1)
status: done             # planned → in-progress → done (kept as a record)
satisfies: [en.spaces.group-scope, uc.spaces.switch-spaces]
adrs: ["0014", "0015"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a member **create a named group, see it in the sidebar, switch into it, and browse its tree** — the G1
substrate of the groups epic ([ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md)). A group is a
`kind=GROUP` scope born warm by its owner: a random `K_SC`, its **name sealed under `K_SC`**, and a self-grant.
Solo for now — **adding other members is G2**. The content tree + favorites already work per-scope, so this is
mostly scope plumbing + the space switcher. Web-only (ADR 0010); native parity is a later increment.

## Context & constraints

- Durable truth: [en.spaces.group-scope](../docs/features/spaces/enablers/en.spaces.group-scope.md),
  [uc.spaces.switch-spaces](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md),
  [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) (governance — confidential even from the
  tenant owner; the name is sealed), [ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md) (`@group`
  fragment routing, client-side resolution).
- Reuses: [en.spaces.personal-scope](../docs/features/spaces/enablers/en.spaces.personal-scope.md) (client-driven
  birth/open, the hybrid-KEM self-grant), the per-scope Memory tree (cr-0027) + favorites (cr-0031). **No new
  crypto.**
- Contract: `contracts/api/scopes.openapi.yaml` — a group-create op (`encryptedName` + `wrappedGrant`) + add an
  optional `encryptedName` to the scope-list row so the client can decrypt a group's name for the switcher.
- Invariant at risk: **operator-blind** — the group name must be **ciphertext** (sealed under `K_SC`); the
  server mints the scope + self-membership but never sees the key or the name. The `@group` slug must live only
  in the `#fragment` (ADR 0015).

## Phases

- [x] 1 contracts — `POST /api/scopes/groups { encryptedName, wrappedGrant }` → `{ scopeId, kind:GROUP }`; add
  optional `encryptedName` to the `Scope` list row. · Done when: `openApiGenerateSpecs` + web `generate:api`
  produce the models/api clean.
- [x] 2 backend — `createGroup` (Store/Handler/Resource mirroring `createPersonal`: mint GROUP scope + OWNER
  self-membership, store the opaque `encryptedName`); `GET /api/scopes` returns `encryptedName` for group rows.
  Slice test (create → list shows the group with verbatim ciphertext name + the self-grant; non-tenant-member
  rejected). · Done when: `./gradlew test` green. Migration `V24__scope_encrypted_name.sql` (nullable column).
- [x] 3 web — `createGroup(name)` (gen `K_SC`, seal name, self-grant, POST) + `listMyGroups()` (decrypt names);
  **generalize `activeSpace` from personal-only to `enterScope(scopeId)`** (added `openScopeById` + the `@<id>`
  branch); sidebar **GRUPPEN** section (vendored `.sidebar-groups-section`, desktop + compact); a
  **create-group** dialog (`CreateGroupDialog.vue`); `@group` fragment resolution (open the group `K_SC`, switch
  the content host). The group content host's **root label** is the decrypted group name (was personal-only).
  · Done when: `npm run check` green + live: create a named group → it appears in GRUPPEN → switch in
  (`#@<id>`) → browse its empty tree; `pathname` stays `/`. Live-verified on `veyq.localhost` + operator-blind
  confirmed at the DB (the `veyq_scope` row carries only ciphertext `encrypted_name`, no plaintext leak).
- [x] 4 coverage — extend `uc.spaces.switch-spaces.yaml` with the group create/list criteria (backend
  `…SliceTest` group-create/list refs + the web group data-layer test) + the `http/spaces/scopes.http` group
  boundary. · Done when: `python3 scripts/validate_coverage.py` green.

## Definition of done

A member creates a named group and works in it exactly like their personal space (browse, create folders/
Memories, favorite) — switching via the sidebar + `@group` fragment. Operator-blind verified: the group name is
sealed (the server row carries only ciphertext); a second account that isn't a member cannot open it. Coverage:
backend slice test + web check green; live smoke. **Out of scope (later):** add a member (G2), member
management + grace-delete + rotation (G3), native parity.
