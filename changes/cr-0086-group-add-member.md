---
id: cr-0086
title: Add a member to a group — direct-add by username (G2)
status: planned
satisfies: [uc.spaces.add-group-member]
adrs: ["0014", "0020", "0021", "0023", "0007"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a **GroupOwner add an existing tenant member to a group by username** — the member then finds themselves in
the group once the key is delivered. The G2 slice of the groups epic ([ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md)),
on top of the solo group of cr-0032. Admission is a **direct membership record** (owner-gated, by username);
**delivery is the already-built primitive** — a grant-less GROUP membership is picked up by the running
grant-delivery ([cr-0076](cr-0076-background-task-mechanism.md)/[cr-0079](cr-0079-web-grant-delivery-polling.md)), any group
key-holder wraps `K_SC` to the newcomer, first-online-wins. **Direct-Add only, Plain v1, add-only** — the consent
opt-in is G2b, member management (promote/demote/remove) + rotation + orphan rule are G3. Web-only; native parity
is a later increment.

## Context & constraints

- Durable truth: [uc.spaces.add-group-member](../docs/features/spaces/use-cases/uc.spaces.add-group-member.md)
  (the flow + the exception cases + the "what this means for you"), [en.spaces.group-scope](../docs/features/spaces/enablers/en.spaces.group-scope.md)
  (the scope this adds members to), [en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md)
  (the per-tenant username the owner searches by).
- Reuses (no new crypto, no new delivery): the **grant-distribution primitive** ([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)
  — `asymWrap(K_SC)` to a foreign identity), the **member directory** (`GET /api/scopes/{scopeId}/members`,
  cr-0077, already returns each member's `identityPublicKey` + `hasGrant`), **grant-delivery** on the background
  mechanism ([ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md), cr-0076/0079 — already fires
  for any shared scope on entry), and the **per-tenant username** ([ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md),
  cr-0083). The membership row already supports a **grant-less** state (V21, `wrapped_grant` nullable).
- New surface (the only genuinely new server capability): a **member admission** op on a group scope —
  `POST /api/scopes/{scopeId}/members { username }` — that (a) authorizes the caller as the scope **OWNER**, (b)
  resolves the `username` **within the scope's own tenant** to a tenant member, and (c) records a grant-less
  GROUP membership. The username resolve is the **tenant-scoped** variant [ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)
  reserved for the group case — **never** a global user-search.
- Invariants at risk:
  - **Operator-blind** — admission stores only a membership row (userId/role, `wrapped_grant=null`); the resolve
    returns only operational metadata the server already holds (userId, and — for the delivering key-holder — the
    directory's pubkey). The group's **name stays sealed**; `K_SC` never crosses the wire. No path may surface
    the name or a key to the server.
  - **Admission authority** — only a group **OWNER** may add a member; the **tenant owner has no say** over
    groups ([ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md) §4). Enforced by code, not the spec
    ([ADR 0008](../docs/decisions/0008-authorization-by-code.md)).
  - **Auth ≠ content** — admission grants a *role*, not the key; the newcomer is *awaiting access* until a
    key-holder delivers ([ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md)).

## Phases

- [ ] 1 contracts — `POST /api/scopes/{scopeId}/members { username }` → `201 { userId }` (records a grant-less
  GROUP membership; owner-gated). Errors: `400` invalid username, `403 not_scope_owner`, `404` (scope or username
  not found in the tenant), `409 already_member` (or idempotent `200` — decide in the op). Reuses the existing
  `ScopeMember` shape for reads (no directory change). · Done when: `openApiGenerateSpecs` + web `generate:api`
  produce the models/api clean.
- [ ] 2 backend — `addGroupMember` (Store/Handler/Resource): assert the caller **OWNs** the `GROUP` scope; resolve
  `username` → userId **within the scope's tenant** (extend the tenant-profile store with a by-username lookup,
  cr-0083); insert a grant-less `GROUP` membership (`role=MEMBER`, `wrapped_grant=null`); idempotent if already a
  member; reject a non-tenant-member username. **Delivery needs no new code** — the grant-delivery originator
  (cr-0076) already reconciles grant-less memberships into jobs. Slice test (owner adds by username → directory
  shows the pending member `hasGrant=false`; non-owner → 403; unknown username → 404; already-member idempotent;
  the group name/`K_SC` never touched). · Done when: `./gradlew test` green.
- [ ] 3 http — add the group-member-add boundary to `http/spaces/scopes.http` (unauth `401`; the owner-gated
  happy-path + authz paths stay integration-covered, as the primitive needs the full grant flow). · Done when:
  the file runs against a live instance (or the unauth assertions hold headless).
- [ ] 4 web — `addGroupMember(scopeId, username)` data layer; a **group members** surface reachable from the open
  group (list current members via the directory + an **add-by-username** dialog, the add action **owner-only**) —
  ported in-style from `TenantMembersPanel`/`InviteMemberDialog`, data layer swapped to the group scope. No new
  delivery wiring: `enterScope` already runs grant-delivery polling for a shared scope, so the owner's own device
  delivers the key after the add. · Done when: `npm run check` green + live (needs a **second account**): owner
  adds a fellow tenant member by username → they appear in the group directory as *awaiting access* → grant is
  delivered → the added member, on their device, opens the group and browses its tree.
- [ ] 5 coverage — fill `uc.spaces.add-group-member.yaml` (drop `status: planned`): map `resolve-username` /
  `admit-group-member` / `auto-deliver-group-grant` / `operator-blind-group-add` to the backend `…SliceTest`
  refs + the web data-layer test + the `http/spaces/scopes.http` boundary; keep apple/android as gaps. · Done
  when: `python3 scripts/validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A GroupOwner adds an existing tenant member to a group by username; the member lands as *awaiting access* and,
once any group key-holder (the owner suffices) has wrapped `K_SC` to them, opens the group and works in it like
any space. Operator-blind verified: the server stored only a grant-less membership row + relayed pubkeys/opaque
grant — never `K_SC`, never the group's plaintext name; the tenant owner gained no power over the group. Coverage:
backend slice test + web check green; live two-account smoke. **Out of scope (later):** the per-user consent
opt-in — "ask before adding me" + soft membership + accept/decline (G2b); promote/demote a co-owner, remove a
member, the last-owner guard, `K_SC` rotation, and the orphan rule (G3); native parity.
