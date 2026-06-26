---
id: cr-0010
title: Activate a tenant (Slice B) — invite acceptance (incl. invite-gated registration), cold TENANT-scope birth & the first membership/grant
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.admin.create-tenant]   # Slice B (activation); Slice A structural was cr-0009
adrs: [0006, 0007, 0008]
invariants: [operator-blind]
---

## Goal

Turn a `PENDING` tenant `ACTIVE` by letting its invited owner **accept**. The owner opens the invite link
(the bootstrap secret rides in the URL `#fragment`) and, in **two paths**:

- **No account yet (the canonical email-invite case):** the invitee **registers cold** — a new account is
  born on the device (identity keypair, Master Key + wraps, Emergency Kit; `server_role = MEMBER`),
  **gated by the bootstrap secret and bound to the invited email** (veyq has no open signup — accounts are
  created only by setup or by invitation).
- **Existing account:** the invitee **signs in** (the email must match the invite).

Either way the client then **cold-births the `TENANT` scope** — a fresh scope root key with the **first
grant** sealed to the owner's **own identity public key** (reusing the existing `v1.asymWrap`). The server
verifies the bootstrap secret, stores the owner's **first `TenantMembership`** (`OWNER` role + the wrapped
grant — [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md)), flips the tenant to
**`ACTIVE`**, and marks the invite **`CONSUMED`** with **reuse-detection** on replays. This is the **first
scope-key / grant / membership code** — and the first **invite-gated account registration** — in the fresh
build.

**Deferred (not this CR):** group/personal scopes, per-subtree content keys, and member/group invites by
username (existing users, invite-wrap). The cold registration here is **tenant-owner-invite-specific**; a
generic "invite a person to the server" flow can factor it out later, but there is no other registration
path today to share it with.

## Context & constraints

- Use-case: [uc.admin.create-tenant](../docs/features/admin/use-cases/uc.admin.create-tenant.md) — this CR
  delivers **Act 2** (activation); Act 1 was cr-0009.
- Concepts: [Scopes & Tenancy](../docs/crosscutting/scopes-and-tenancy.md) (grants, membership = role +
  grant), [ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md) (cold scope birth; the bootstrap
  secret is **not** a key), [ADR 0007](../docs/decisions/0007-membership-role-grant-relation.md) (the first
  membership is born here), [ADR 0008](../docs/decisions/0008-centralized-http-auth.md) (central
  annotation-driven auth — this CR's new endpoints plug into it; see below).
- **Auth is central + default-deny now (cr-0012 / ADR 0008).** `ServerOwnerGuard` is gone; the bearer is
  resolved once into a `SecurityIdentity`, and every endpoint declares intent with an annotation. The new
  endpoints carry **method-level** annotations that override `TenantsResource`'s class-level
  `@RolesAllowed(SERVER_OWNER)`: `accept/preview` + `accept/register` → **`@PermitAll`** (secret-gated, no
  session — the invitee may have none yet), `accept` → **`@Authenticated`** (*any* signed-in user; the
  email-match is domain logic, not a role). The handler stays purely fachlich — the existing-user path gets
  the caller's `userId` from `identity.principal.name`, never a raw token.
- **The grant primitive already exists** — `v1.asymWrap` / `asymWrap` (hybrid X25519 + ML-KEM-768,
  conformance-pinned). The grant is `asymWrap(ownerIdentityPublicKey, scopeRootKey, "scope-grant", …)`; **no
  new crypto suite work, no new conformance vector.**
- **Acceptance needs the identity key client-side** to seal the grant — **freshly born** on the new-user
  register path, or **unlocked from the vault** on the existing-user path (the first content-key-adjacent
  operation; cr-0009's tenant admin needed only a session).
- **Register path reuses setup's crypto + persistence:** generalise `setupServer.ts`'s
  `buildCreateServerOwnerRequest` and `SetupStore.persistOwner` to take an email + `server_role` (so a
  `MEMBER` account is born the same operator-blind way as the first owner), **without** touching the
  single-owner setup lock.
- **Operator-blind holds:** the server stores only the **wrapped grant (ciphertext)** + the role + that a
  membership exists; it never sees the scope key. The bootstrap secret stays hashed (cr-0009).

## Phases

- [x] **1 — contracts.** Extend `contracts/api/tenants.openapi.yaml`, **all under the `tenants` tag** (one
  `TenantsResource`, so the literal `accept` segment is preferred over the admin `/{tenantId}` template — no
  greedy-`@Path` split). Authz is **declared in code, not the spec** (ADR 0008) — the spec documents auth in
  prose; the resource methods carry the annotations above.
  **Done:** added `preview`/`accept`/`accept/register` ops + schemas (`AcceptPreviewRequest`,
  `InvitePreview`, `AcceptInviteRequest`, `TenantActivated`, `RegisterAndAcceptRequest`, `AccountRegistration`,
  `TenantActivatedWithUser`, and a `MasterKeyWrap` duplicate **field-identical** to setup's — incl. `id` — so
  the last-generated model doesn't strip a field the account code reads). Backend `openApiGenerateSpecs` emits
  the 3 new `TenantsApi` methods + models; `TenantsResource` got **501 `not_implemented` stubs** with the
  method-level `@PermitAll`/`@Authenticated` overrides (phase 2 fills them) → `./gradlew check` green (all
  existing tests pass); web `generate:api` → new models, `npm run typecheck` green.
  - `POST /api/tenants/accept/preview` (`@PermitAll`) → body `{ bootstrapSecret }` → `200 { tenantId, tenantName, domain, ownerEmail, hasAccount }` so the invitee sees what they're accepting **and** whether the invited email already has an account (→ which path the UI takes); `404` unknown, `410` expired/consumed/superseded (reuse-detected).
  - `POST /api/tenants/accept` (`@Authenticated`) → body `{ bootstrapSecret, grant }`, caller from `SecurityIdentity` → `200 { tenantId, status:"ACTIVE" }`; `401` (central) unauth, `403` email mismatch (caller ≠ invited owner — a **tenant-specific code**, e.g. `not_invited_owner`, **not** `not_server_owner`), `404`, `409` already active, `410`.
  - `POST /api/tenants/accept/register` (`@PermitAll`, **new user**, no session) → body `{ bootstrapSecret, account, grant }` where `account` is the cold-birth material (suiteId, identityPublicKey, wrappedIdentityPrivateKey, authPublicKey, masterKeyWraps[], recoveryMasterKeyTag — same shape as `CreateServerOwnerRequest`, email taken from the invite) → `201 { tenantId, status:"ACTIVE", userId }`; `404`, `409` email already has an account, `410`. The new user logs in afterward (no session issued here, mirroring setup).
  - **Done when:** both clients regenerate; generation + `compileKotlin` + web `typecheck` green.
- [x] **2 — backend.** `V8__membership.sql`: `veyq_membership { id PK, user_id FK, tenant_id FK, role,
  wrapped_grant TEXT, created_at }` (the TENANT root scope is keyed by the tenant id for now; generalising
  to an explicit scope id is future). A shared **invite-acceptance core**: verify the bootstrap-secret
  **hash** → invite `ACTIVE`? (a non-`ACTIVE` hit is a **replay → reject + log**, mirroring session
  reuse-detection) → store the `OWNER` membership + grant, flip the tenant `ACTIVE`, mark the invite
  `CONSUMED` — **atomic** (one transaction). Two entry points:
  - **existing-user accept:** the Resource passes the caller's `userId` (`SecurityIdentity.principal.name`,
    `@Authenticated`) to the handler; require `caller.email == invite.owner_email` (else a domain outcome →
    `403 not_invited_owner`, mapped by the Resource — *not* the central `not_server_owner`), then run the core.
  - **register-and-accept:** the email has **no** account → create the user cold (`server_role = MEMBER`)
    from the `account` material, then run the core — all in one transaction (no half-registered account if
    anything fails). Reuse/extract the owner-registration persistence from `SetupStore` (generalise the
    `SERVER_OWNER`-hardcode to a role param); the single-owner setup lock is **not** touched.
  - Preview handler (no auth, secret-gated; returns `hasAccount`).
  **Done:** `./gradlew check` green — new `MembershipEntity` (+ `V8`), `AccountStore` (extracted operator-blind
  account creation; `SetupStore` now delegates to it — `SetupServerSliceTest` **14/14** unchanged),
  `TenantAcceptHandler` (preview / accept / register-and-accept with `isLive` reuse-detection),
  `TenantStore.activate` (atomic OWNER membership + ACTIVE + CONSUMED), and `TenantsResource` real impls
  (`@PermitAll` preview+register, `@Authenticated` accept; caller from `SecurityIdentity`). **`TenantAcceptSliceTest`
  9/9**: both paths to `ACTIVE` (register-and-accept → MEMBER account + OWNER membership; sign-in accept),
  preview + `hasAccount`, replay of a consumed secret → `410`, email-mismatch → `403 not_invited_owner`,
  email-already-registered → `409 email_taken`, unknown secret → `404`, expired → `410`, unauthenticated
  accept → `401`. `TenantsSliceTest` 13/13 unaffected.
- [x] **3 — http.** Boundary surface added to `http/admin/tenant.http` (zero-precondition): `accept` with no
  session → **401** (central, cr-0012); the public `accept/preview` + `accept/register` reach the handler →
  unknown secret **404 `invite_not_found`**, blank/malformed body **400**. The **happy-path is not
  http-expressible** (the bootstrap secret lives only in the invite email / mailer log, never in an API
  response — and the grant needs the ML-KEM `asymWrap` the vendored `http/_lib/` Ed25519 can't produce), so
  the signed accept round-trip is covered by `TenantAcceptSliceTest` + the web smoke. **Done:** `ijhttp
  15/15` green against a fresh backend (V8 applied on the dev MariaDB).
- [x] **4 — web.** **Done:** extracted the shared cold account-birth into `features/account/register/accountBirth.ts`
  (`buildAccount`); `setupServer.ts` now delegates to it (`setupServer.test.ts` green). Added `v1.hybridPublic`
  (derive the identity public bundle from the vaulted private — for the existing-owner self-grant; pure, no
  wire change). Facade `tenants.ts`: `previewInvite` / `acceptInvite` / `registerAndAccept` + `scopeGrant`
  (`asymWrap` a fresh scope root key to the owner's identity). New **`TenantJoinView.vue`** at `/tenant/accept`
  (reads the `#fragment` secret, previews, branches on `hasAccount`: register-with-kit OR sign-in+unlock,
  shared cold scope-birth), i18n `tenant.join.*` en+de. `npm run check` green (typecheck + **65 tests** + build).
  **Browser smoke (new-user path, end-to-end):** operator invites a *different* email → invitee opens the
  link → preview shows "invited to own Reise 2026" + register branch → name+password → Emergency Kit shown +
  confirmed → cold scope-birth → **"Reise 2026 ist jetzt aktiv und gehört dir"**. DB verified: tenant `ACTIVE`,
  `invitee@example.com` born as `MEMBER`, `OWNER` membership with a stored wrapped grant, invite `CONSUMED`;
  no console errors. The **existing-owner sign-in path** shares the whole view + `acceptInvite` and is covered
  by `TenantAcceptSliceTest.existingUserAcceptActivatesTenant`; a browser smoke of it needs the owner's Secret
  Key, which the setup→login auto-prefill never surfaces as a value, so it stays slice-covered.
- [x] **5 — docs.** Added 9 Slice B acceptance criteria to
  [uc.admin.create-tenant.yaml](../docs/coverage/features/admin/use-cases/uc.admin.create-tenant.yaml)
  (activate-by-register / -by-signin, preview, reuse-detection, invited-owner-only, email-taken, unknown,
  requires-auth → `TenantAcceptSliceTest` + `tenant.http`); ticked **Slice B ✅ built** in the use-case;
  advanced inbox **#13** to done. **Done:** `validate_coverage.py` green (5 use-cases, all criteria covered).

## Definition of done

A `PENDING` tenant becomes `ACTIVE` when its invited owner accepts — whether they **register cold** (no
account yet: a `MEMBER` account is born on the device, gated by the bootstrap secret + bound to the invited
email) or **sign in** (existing account, email matching the invite). Either way the client cold-births the
`TENANT` scope and seals the first grant to the owner's own identity; the server records the first
`TenantMembership` (`OWNER` + wrapped grant), flips the tenant `ACTIVE`, and consumes the invite — atomically.
The operator holds only ciphertext grant + role + membership existence — never the scope key. Replayed /
expired / superseded invites are rejected (reuse-detection). Delivered across contracts → backend → http →
web with green checks; keep this file as a record after merge.
