---
id: cr-0009
title: Create a tenant (Slice A — structural) — PENDING tenant, domain mapping, email-bound invite & Server Owner management
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.admin.create-tenant]   # Slice A only; activation (cold scope birth) = Slice B / cr-0010
adrs: ["0002", "0006"]
invariants: [operator-blind]
---

## Goal

Build the **structural, key-free** half of tenant creation: a **Server Owner** creates a tenant by entering
`{ tenant name, domain, owner email }`; the server validates the domain is free, creates the tenant in
**`PENDING`**, mints a **single-use, time-limited invite token bound to the owner email**, and produces an
invite link (delivered via a mailer port; dev logs it). The Server Owner can **list all tenants** and, for
`PENDING` ones, **resend** the invite, **reassign** the owner email, or **revoke** the tenant. No client
crypto, no scope keys — those are **Slice B (cr-0010)**: invite acceptance + the cold `TENANT`-scope birth
that flips a tenant to `ACTIVE`. Until then a `PENDING` tenant is a reserved domain mapping plus an
outstanding invite, holding no content.

## Context & constraints

- Use-case: [uc.admin.create-tenant](../docs/features/admin/use-cases/uc.admin.create-tenant.md) — this CR
  delivers **Act 1** + the *Server Owner management of pending tenants* section; Act 2 (activation) is
  cr-0010.
- Concepts: [Scopes & Tenancy](../docs/crosscutting/scopes-and-tenancy.md) (tenant = domain-scoped, resolved
  by `Host`; opaque IDs), [ADR 0002](../docs/decisions/0002-personal-scope-per-tenant.md),
  [ADR 0006](../docs/decisions/0006-cold-tenant-scope-birth.md) (the invite token is a *bootstrap secret,
  not a key*; the Server Owner holds no key — so this whole slice is operator-blind by construction).
- **Operator-blind is not threatened here:** domain + name + owner-email are **operational cleartext**
  (host routing needs the domain; no key exists at creation to encrypt anything). The invite token is stored
  **hashed**; it gates a future acceptance and carries no content key.
- **Authz is new:** these endpoints require the global **Server Owner** role (`veyq_user.server_role`); no
  role guard exists yet (`grep`: only `server_role` column + `VeyqUser`). Build a small Server-Owner guard,
  reusing the authed-session resolution from `SessionService` (the existing authenticated-read guard).
- **Mailer is greenfield:** no mailer in `backend/`. Introduce a thin `TenantInviteMailer` **port** with a
  **dev/log impl** that emits the invite link; real SMTP is an infra/runbook concern, out of scope.
- **Frontend reuses legacy UI/UX** (project rule): port `legacy/frontend/src/components/tenant/CreateTenantDialog*`
  and the client shape in `legacy/frontend/src/api/tenantClient.ts` — swap the data layer to the generated
  client, keep the UX. (The invitee-side `TenantJoinView` is Slice B.)
- **Greenfield, no prod data** — new tables via Flyway `V7`; no back-compat needed.

## Phases

- [x] **1 — contracts.** New `contracts/api/tenants.openapi.yaml`:
  - `POST /api/tenants` → create. Body `{ tenantName, domain, ownerEmail }` → `201 { tenantId, status:"PENDING", inviteExpiresAt }`. Errors: `409` domain taken (A1), `400` invalid domain/email.
  - `GET /api/tenants` → `200 [{ tenantId, name, domain, ownerEmail, status, inviteExpiresAt }]` (all tenants).
  - `POST /api/tenants/{tenantId}/invite/resend` → re-mint token + expiry (PENDING only; `409` if ACTIVE).
  - `POST /api/tenants/{tenantId}/invite/reassign` → body `{ ownerEmail }`, re-bind + re-mint (PENDING only).
  - `DELETE /api/tenants/{tenantId}` → revoke a PENDING tenant (`409` if ACTIVE).
  - All under a `tenants` tag, all **Server-Owner-only** (`bearerAuth` + a 403 on non-owner). Nested
    action paths under `/invite/...` (slash, not a `:verb`) so the one tag yields a clean class
    `@Path("/api/tenants")`. **Done:** backend `openApiGenerateSpecs` emits `TenantsApi` (class
    `@Path("/api/tenants")`, clean method paths — no greedy shadowing) + models, `compileKotlin` green
    (domain `Pattern` regex valid in Kotlin); web `generate:api` registered (own `tenants/` output) →
    `TenantsApi.ts` + models, `npm run typecheck` green. (No contract-conformance test suite exists in the
    fresh build yet — only `SetupRequestSanitizationTest` — so generation + compile is the gate.)
- [x] **2 — backend.** `V7__tenants.sql`: `veyq_tenant { id PK uuid, name, domain UNIQUE, owner_email,
  status, created_at }` **plus a separate `veyq_tenant_invite { id PK, tenant_id FK, owner_email,
  token_hash, status, expires_at, created_at }`** — the invite is its **own table**, multiple rows per
  tenant, mirroring the session refresh-token model (ADR 0004 / `V5`): store the **SHA-256 hash only**,
  never the token. Entity + `TenantStore` + a `tenants` slice implementing the generated JAX-RS interface
  (one op per tag to dodge the greedy `@Path` codegen gotcha). Server-Owner guard. Domain format +
  uniqueness validation (clean `409`). Invite `status` ∈ {`ACTIVE`,`SUPERSEDED`,`CONSUMED`,`EXPIRED`}:
  **resend/reassign soft-supersede** the current `ACTIVE` row (→ `SUPERSEDED`) and insert a fresh `ACTIVE`
  one — **never overwrite/delete**; build the invite link (targets the tenant domain accept route —
  marked `CONSUMED` in cr-0010, not here). A replayed non-`ACTIVE` token is **recognised → rejected +
  logged** (reuse detection, as with sessions). `TenantInviteMailer` port + dev/log impl. A **`@Scheduled`
  purge** (the instance's first) physically deletes terminal-state invites past a **retention window
  (config, not hardcoded)**. **Done:** `./gradlew check` green — `V7` applies on H2; `TenantsSliceTest`
  11/11 covers create (201/PENDING) / list / resend (old → `SUPERSEDED`, one `ACTIVE` kept) / reassign
  (email re-bound + supersede) / revoke (204 + gone, then 404) / domain-taken `409` / invalid-domain `400`
  / unknown-tenant `404` / **non-owner `403`** / unauthenticated `401`, plus the purge (overdue `ACTIVE` →
  `EXPIRED`, terminal rows deleted). New pieces: `ServerOwnerGuard` (first role guard, on `SessionService`),
  `TenantInviteMailer` port + `LoggingTenantInviteMailer` dev impl, `TenantInvitePurge` (`@Scheduled`, added
  `quarkus-scheduler`), config `veyq.tenant.invite.{ttl,retention,purge.every}`. Note: **reuse-detection on
  a replayed token moved to Slice B** — Slice A has no token-consuming endpoint, so there is nothing to
  replay yet; the store keeps the hash for B to look up. Also set `quarkus.http.test-port=0` (the default
  8081 collides with the Vite web dev server).
- [x] **3 — http.** `http/admin/tenant.http` with response-handler assertions. The HTTP client cannot
  produce an Ed25519 signature, so it cannot obtain a Server-Owner session (same limit documented in
  `login.http` / `trust-device.http`) — so this file covers the **no-signature surface**: anonymous +
  bogus-bearer `401` on all five endpoints, the body `400`s (invalid domain / blank name / malformed
  email, incl. reassign), and that **auth precedes tenant lookup** (a valid-UUID path with no token is a
  `401`, not `404`). The authed happy-path (create 201 → list → resend → reassign → revoke) and the
  non-owner `403` are covered by `TenantsSliceTest` + the web round-trip (phase 4). **Done:** `ijhttp
  10/10` green against a fresh instance (`V7` applied on the dev MariaDB).
- [x] **4 — web.** `tenantsApi()` in `api/client.ts`; facade `features/admin/createTenant/tenants.ts`
  (create / list / resend / reassign / revoke + a `tenantError` decoder); ported `CreateTenantDialog.vue`
  (name / domain / owner-email, data layer swapped to the generated client); **`TenantsView.vue`** — the
  app's **first authenticated content view** — a tenant list with PENDING/ACTIVE status + resend /
  reassign (dialog) / revoke (confirm) actions; route `/tenants` gated on a live session in `router.ts`;
  an entry button on the LoginView "unlocked" card; i18n `tenant.*` + `common.create` (en+de); facade unit
  test `tenants.test.ts`. **Done:** `npm run check` green — typecheck + **65 tests (10 files)** + build.
  **Interactive browser smoke green** (fresh instance, dev MariaDB wiped with the user's consent, backend
  jar on 8080, Vite on 8089): setup → login → unlock → **Tenants verwalten** → create (201, row renders
  **AUSSTEHEND**) → resend (200; backend mailer logged a *fresh* invite token to the tenant domain —
  supersede confirmed) → revoke (204, list empties). No console errors, no backend errors. The smoke
  caught a real layout bug — the row overflowed the 460px shell card (status badge overlapped the domain,
  "Widerrufen" clipped) — fixed by stacking the row + wrapping the actions. Note: the entry is shown to
  every signed-in user because today the only account that can exist is the Server Owner; a role-aware
  gate arrives with member accounts (the backend 403 is the real guard).
- [x] **5 — docs.** Added coverage file
  [uc.admin.create-tenant.yaml](../docs/coverage/features/admin/use-cases/uc.admin.create-tenant.yaml)
  (11 acceptance criteria → `TenantsSliceTest` + `tenant.http`; web/apple/android as gaps); marked Slice A
  **✅ built** in the use-case; advanced inbox **#13**. **Done:** `validate_coverage.py` green (5 use-cases
  mapped, all criteria covered).

## Definition of done

A Server Owner can create a `PENDING` tenant `{ name, domain, ownerEmail }` with a free, validated domain;
the server mints a hashed, TTL'd, email-bound invite and a dev-logged invite link; the Server Owner can list
all tenants and resend / reassign-email / revoke pending ones; every endpoint is Server-Owner-gated
(non-owner → `403`); ACTIVE tenants reject the pending-only management ops. Delivered across
contracts → backend → http → web with green checks on each. **No scope keys, no acceptance** — those are
cr-0010 (Slice B), which consumes the invite to cold-birth the `TENANT` scope and flip the tenant to
`ACTIVE`. Keep this file as a record after merge.
