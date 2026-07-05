# ADR 0007 — Membership is the (user, scope, role, grant) relation

- Status: Accepted
- Date: 2026-06-26

## Context

Tenant activation (cr-0010, Slice B) creates the **first** membership and the **first** grant, so the
membership model must be settled first. Open questions: how do we model the **management role** vs.
**content access**? Is server-level membership n-m like tenants? How is a person **reached** when inviting
(email vs. username)? Who may see a tenant's **owner email**? See the
[Scopes & Tenancy](../crosscutting/scopes-and-tenancy.md) model and [ADR 0006](0006-cold-tenant-scope-birth.md).

## Decision

**Membership is a relation between a user and a scope, carrying both axes in one row:** a server-enforced
**management role** and a client-enforced **grant** (the scope private key wrapped to the member's identity
public key).

- **Role gates management; grant gates content.** A role without a grant is a hollow title; promoting an
  existing grant-holder is a pure role change (no key moves), and demoting one is too.
- **Ownership cardinality — many owners, never zero.** At **every** level (Server, Tenant, Group) a scope may
  have **multiple owners** and **must keep at least one**: removing or demoting the **last** owner is rejected (a
  "last-owner guard"). Orphaning is thus only possible when a sole owner *leaves* — handled by an automatic rule,
  not an admin power ([ADR 0014](0014-group-scopes-and-governance.md)).
- **Per scope kind:**
  - **Server role** is a single global column per user (`veyq_user.server_role`, `OWNER` / `MEMBER`) — a user
    belongs to exactly one server. **Multiple `SERVER_OWNER`s are allowed** (subject to the guard above); a flow
    to mint a second is a later use-case. A server-scope **grant** is added only if the operator domain is later
    modelled as a server-scope (a separate, parked idea).
  - **Tenant and Group memberships are n-m** (user × tenant / user × group-within-tenant), each with a
    role + a grant to that scope's key. Group roles are **OWNER / MEMBER** only — no `ADMIN`
    ([ADR 0014](0014-group-scopes-and-governance.md)).
  - **Personal** scope is self-held — no membership row beyond the member's own grant.
- **The first tenant membership (the owner's) is born at activation** (cold `TENANT`-scope birth, cr-0010);
  members extend the same relation later via the invite-wrap.
- **Reaching a person:** **email** is used only for an **outsider** with no account (the tenant-owner
  invite); an **existing user** is reached by **username** — a **per-tenant, unique-within-tenant** handle on the
  membership ([ADR 0023](0023-per-tenant-member-identity.md)), fully in-app. This keeps person lookup
  non-enumerable (bounded to the tenant's own members).
- **Owner-email visibility is a role rule, not crypto:** it is server-visible operational metadata,
  returned only to roles that need it (a `SERVER_OWNER`, or the tenant's own `OWNER`) and omitted for ordinary
  members — who see each other's per-tenant `username` + sealed real name instead
  ([ADR 0023](0023-per-tenant-member-identity.md)).

## Consequences

- **cr-0010 introduces the membership table + the owner's grant.** Member/group invites (by username,
  invite-wrap) are a later use-case that extends the same relation.
- **Consistent with [ADR 0006](0006-cold-tenant-scope-birth.md):** forced reassignment grants a role only;
  a key-preserving handoff still needs a grant from an existing key-holder.
- **Server role stays a single column** for now; promoting/minting additional `SERVER_OWNER`s is deferred.
- **Email exposure is minimised** — needed only to reach outsiders; in-system invites use a username.
- **Rejected — n-m server membership:** a user belongs to exactly one server, so the server role is 1-1.
- **Rejected — per-field email encryption-at-rest as part of this decision:** at-rest encryption of
  server-visible PII (under an operator-held key) is a **separate crosscutting** concern — it is *not*
  operator-blindness (the server must decrypt to send mail) and must cover **all** server-visible emails
  (incl. `veyq_user.email`), not just the tenant owner email.
