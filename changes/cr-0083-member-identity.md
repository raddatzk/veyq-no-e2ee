---
id: cr-0083
title: Member identity — per-tenant username + sealed real names, drop account displayName
status: planned
satisfies: [en.spaces.member-identity]
adrs: ["0023", "0007"]
invariants: [operator-blind-e2ee]
---

## Goal

Replace the global, free-form `veyq_user.display_name` with a **per-`(user, tenant)` identity profile**: a
tenant-unique **`username`** (plaintext, a pseudonymous handle) + **`firstName`/`lastName` sealed under
`K_tenant`** (operator-blind real name), with the directory showing username + names to every member and `email`
only to a tenant **OWNER** ([ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md)). This is the
foundation the group member-add **username search** (G2) and the later `~username` **profile view** build on.
`email` stays the only instance-global key (login unchanged).

## Context & constraints

- Durable truth: [en.spaces.member-identity](../docs/features/spaces/enablers/en.spaces.member-identity.md),
  [ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md) (the model),
  [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md) (membership = role + grant; email is
  owner-only, reach-by-username).
- **Operator-blind at risk:** the real name (`firstName`/`lastName`) must be **ciphertext** sealed under
  `K_tenant` (same `symWrap` name-sealing as node names, [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md));
  the server stores only the blob. `username` + `email` are the only plaintext identity fields; `email` is
  owner-only in the directory.
- **Uniqueness is per-tenant:** `UNIQUE(tenant_id, username)` — the server sees `username` plaintext to enforce
  it. Instance-global uniqueness stays with `email` alone.
- **Auth unchanged:** login is by email ([ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)); the
  username touches no crypto path.
- **Chicken-and-egg:** a grant-less member cannot seal a name until they hold `K_tenant` (post grant-delivery,
  [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)) — until then the directory shows only
  `@username`. `username` itself is set at join (needs no key).
- Contracts: `contracts/api/*.openapi.yaml` — the account/register + accept flows carry a `username`; the member
  directory (`ScopeMember`) returns `username` + a sealed name blob + **optional** owner-only `email`; a
  profile op sets/updates the sealed name + changes the username.

## Phases

- [ ] 1 contracts — add `username` to the register-and-accept **and** existing-account accept requests
  (`409 username_taken`); replace `ScopeMember.displayName` with `username` + `sealedName` (ciphertext, nullable)
  + make `email` **optional** (owner-only); a profile op to set the sealed name + change the username. · Done
  when: `openApiGenerateSpecs` + web `generate:api` produce clean models/clients.
- [ ] 2 backend — migration: drop `veyq_user.display_name`; add a **tenant-profile** table keyed `(user_id,
  tenant_id)` with `username` (`UNIQUE(tenant_id, username)`) + `sealed_name` — one row per person per tenant
  (not per membership; a member has several scope memberships in one tenant). Capture + uniqueness-check the
  username in both join flows; a profile handler/endpoint (change username with re-check, store the sealed-name
  blob); the directory joins the profile and returns username + sealedName, gating `email` on the caller being a
  tenant OWNER. Slice tests (join sets a unique username; duplicate → 409; owner sees email, member does not;
  sealed name stored verbatim). · Done when: `./gradlew check` green.
- [ ] 3 web — the join/register forms capture `username` (+ first/last); seal first/last under `K_tenant` once
  granted; the member directory renders `@username` + locally-decrypted names; a profile settings surface (change
  username, edit names); switch the personal-scope route **`~me` → `~<own-username>`** (sidebar + `location.ts`
  parse/build) ([ADR 0015](../docs/decisions/0015-operator-blind-readable-urls.md)); i18n EN+DE. · Done when:
  `npm run check` green + live: join picks a username, directory shows `@handle` + name, personal opens at
  `#~<username>`.
- [ ] 4 coverage — map the identity criteria onto the consuming use-cases (`uc.spaces.add-member` directory +
  the tenant-join in `uc.admin.create-tenant`): username uniqueness, sealed-name round-trip, owner-only email,
  `~username` routing. · Done when: `python3 scripts/validate_coverage.py` + `validate_changes` +
  `validate_doc_links` green.
- [ ] 5 verify — live browser smoke of a fresh join + directory + `#~username`.

## Definition of done

A member chooses a tenant-unique `@username` on join and a first/last name that is **sealed under `K_tenant`**;
the directory shows every member's handle + (locally decrypted) name, and the email only to a tenant owner; the
personal scope is addressed by `#~<username>` (no more `~me`). Operator-blind verified: the server row carries a
ciphertext name (never plaintext), a duplicate username in a tenant is `409`, the same handle is free in another
tenant. **Out of scope (later):** the group member-add username search (G2), the `~username` **profile view** +
the per-Memory participants tag it needs, at-rest encryption of server-visible `email`.
