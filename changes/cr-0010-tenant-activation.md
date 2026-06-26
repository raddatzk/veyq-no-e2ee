---
id: cr-0010
title: Activate a tenant (Slice B) — invite acceptance (incl. invite-gated registration), cold TENANT-scope birth & the first membership/grant
status: planned          # planned → in-progress → done (then keep as a record)
satisfies: [uc.admin.create-tenant]   # Slice B (activation); Slice A structural was cr-0009
adrs: [0006, 0007]
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
  membership is born here).
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

- [ ] **1 — contracts.** Extend `contracts/api/tenants.openapi.yaml` (all under the `tenants` tag; the
  bootstrap secret is the gate, so preview/register are unauthenticated):
  - `POST /api/tenants/accept/preview` → body `{ bootstrapSecret }` → `200 { tenantId, tenantName, domain, ownerEmail, hasAccount }` so the invitee sees what they're accepting **and** whether the invited email already has an account (→ which path the UI takes); `404` unknown, `410` expired/consumed/superseded (reuse-detected).
  - `POST /api/tenants/accept` (**existing user**, bearer) → body `{ bootstrapSecret, grant }` → `200 { tenantId, status:"ACTIVE" }`; `401` unauth, `403` email mismatch (caller ≠ invited owner), `404`, `409` already active, `410`.
  - `POST /api/tenants/accept/register` (**new user**, no bearer) → body `{ bootstrapSecret, account, grant }` where `account` is the cold-birth material (suiteId, identityPublicKey, wrappedIdentityPrivateKey, authPublicKey, masterKeyWraps[], recoveryMasterKeyTag — same shape as `CreateServerOwnerRequest`, email taken from the invite) → `201 { tenantId, status:"ACTIVE", userId }`; `404`, `409` email already has an account, `410`. The new user logs in afterward (no session issued here, mirroring setup).
  - **Done when:** both clients regenerate; generation + `compileKotlin` + web `typecheck` green.
- [ ] **2 — backend.** `V8__membership.sql`: `veyq_membership { id PK, user_id FK, tenant_id FK, role,
  wrapped_grant TEXT, created_at }` (the TENANT root scope is keyed by the tenant id for now; generalising
  to an explicit scope id is future). A shared **invite-acceptance core**: verify the bootstrap-secret
  **hash** → invite `ACTIVE`? (a non-`ACTIVE` hit is a **replay → reject + log**, mirroring session
  reuse-detection) → store the `OWNER` membership + grant, flip the tenant `ACTIVE`, mark the invite
  `CONSUMED` — **atomic** (one transaction). Two entry points:
  - **existing-user accept:** resolve the caller (bearer), require `caller.email == invite.owner_email`
    (else `403`), then run the core.
  - **register-and-accept:** the email has **no** account → create the user cold (`server_role = MEMBER`)
    from the `account` material, then run the core — all in one transaction (no half-registered account if
    anything fails). Reuse/extract the owner-registration persistence from `SetupStore` (generalise the
    `SERVER_OWNER`-hardcode to a role param); the single-owner setup lock is **not** touched.
  - Preview handler (no auth, secret-gated; returns `hasAccount`).
  **Done when:** `./gradlew check` green — slice tests cover **both** paths to `ACTIVE` (existing-user accept
  and register-and-accept → `OWNER` membership + grant stored, MEMBER user created), **replay of a
  consumed/superseded secret → rejected**, email-mismatch `403`, email-already-registered `409` on the
  register path, expired `410`, already-active `409`, and the preview happy/again paths.
- [ ] **3 — http.** Extend `http/admin/tenant.http` (or a new `tenant-accept.http`) with the no-signature
  surface: unauthenticated `accept` → `401`; unknown/expired/consumed bootstrap secret on `accept:preview`
  → `404`/`410`. (The authed cold-birth round-trip needs a signed session + unlocked vault → covered by the
  slice test + the web smoke.) **Done when:** ijhttp green.
- [ ] **4 — web.** Port the legacy invitee view (`legacy/frontend/src/views/TenantJoinView.vue`) at route
  `/tenant/accept`: read the `#fragment` bootstrap secret, call `accept/preview`, then branch on
  `hasAccount`:
  - **new user** → a registration form (reuse `setupServer.ts`'s cold key-birth — generalise
    `buildCreateServerOwnerRequest` into a shared account-build that takes the email + role; the email is
    fixed from the invite), show + confirm the **Emergency Kit**, then cold-birth the scope and
    `POST accept/register`.
  - **existing user** → prompt sign-in + **unlock** (identity key from the vault), then cold-birth the
    scope and `POST accept`.
  Cold-birth is shared: `scopeRootKey = randomBytes(32)`;
  `grant = asymWrap(ownIdentityPublic, scopeRootKey, "scope-grant", …)`. Show success → the tenant is
  active. i18n en+de. **Done when:** `npm run check` green + browser smoke for **both** paths — (a) the
  new-user case: operator invites a **different** email → open link → register (Emergency Kit) → tenant
  shows `ACTIVE`; (b) the existing-user case: operator invites their **own** email → open link → sign-in +
  unlock → `ACTIVE`.
- [ ] **5 — docs.** Add Slice B acceptance criteria to
  [uc.admin.create-tenant.yaml](../docs/coverage/features/admin/use-cases/uc.admin.create-tenant.yaml);
  tick **Slice B built** in the use-case (the `PENDING → ACTIVE` transition is now real); advance inbox
  **#13**; run `validate_coverage.py`. **Done when:** validator green.

## Definition of done

A `PENDING` tenant becomes `ACTIVE` when its invited owner accepts — whether they **register cold** (no
account yet: a `MEMBER` account is born on the device, gated by the bootstrap secret + bound to the invited
email) or **sign in** (existing account, email matching the invite). Either way the client cold-births the
`TENANT` scope and seals the first grant to the owner's own identity; the server records the first
`TenantMembership` (`OWNER` + wrapped grant), flips the tenant `ACTIVE`, and consumes the invite — atomically.
The operator holds only ciphertext grant + role + membership existence — never the scope key. Replayed /
expired / superseded invites are rejected (reuse-detection). Delivered across contracts → backend → http →
web with green checks; keep this file as a record after merge.
