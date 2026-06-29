---
id: cr-0012
title: Centralize HTTP authentication & authorization (Quarkus SecurityIdentity + @RolesAllowed)
status: done             # planned → in-progress → done (then keep as a record)
satisfies: []            # cross-cutting refactor — no use-case; realigns the developer-styleguide (inbox #44)
adrs: ["0001", "0003", "0004"] # relates to slicing + auth model; this CR introduces ADR 0008
invariants: [operator-blind]
---

## Goal

Today every authenticated Resource re-implements the same boundary by hand: `@Context lateinit var headers`,
`bearerToken(headers)`, a private `unauthorized()` (and `forbidden()`), and a handler that takes a raw
`accessToken: String?` and carries `Unauthenticated` / `NotOwner` variants in its sealed outcome. The
token → userId resolution (`SessionService.resolveUserId`) is duplicated in every handler **and** in
`ServerOwnerGuard`.

This CR moves authentication and authorization to **one** place using Quarkus' security SPI: a custom
`HttpAuthenticationMechanism` extracts the bearer token, a `IdentityProvider` resolves it **once** into a
`SecurityIdentity` (principal = `userId`, roles from `veyq_user.server_role`), and Resources declare intent
with `@Authenticated` / `@RolesAllowed(SERVER_OWNER)` / `@PermitAll`. Two JAX-RS `ExceptionMapper`s remain the
single home of the `ErrorResponse` body for 401/403. Handlers stop seeing tokens — they receive a resolved
`userId` and lose their auth outcome variants, becoming purely domain-focused.

**Externally observable behaviour is unchanged** (same status codes, same `ErrorResponse` bodies, same
endpoints) — so `http/` and `web/` stay green, with **one intentional, documented exception**: because eager
`@RolesAllowed` runs before `@Valid`, an anonymous request with a malformed body now returns a uniform `401`
instead of `400` (a strict security improvement — no pre-auth validation leak). This is a backend-internal
refactor otherwise.

## Context & constraints

- Conventions home: [developer-styleguide](../docs/crosscutting/developer-styleguide.md) (the "guards / mappers"
  patterns this realigns) — inbox **#44**. ADR [0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)
  (thin adapter → handler), ADR [0003](../docs/decisions/0003-operator-blind-password-auth.md) (Model B, server
  never sees the password) and ADR [0004](../docs/decisions/0004-session-tokens.md) (opaque session tokens, the
  `resolveUserId` authenticated-read guard).
- **Operator-blind is the at-risk invariant.** The `SecurityIdentity` may carry **only** userId + role metadata
  + the opaque token as a credential — **never** a content key. Roles gate *management* actions; they never
  confer content readability (that stays the scope-key grant). Re-read
  [security-model](../docs/crosscutting/security-model.md) before touching the auth path.
- **Custom token model, not OIDC.** veyq's bearer is an opaque session token resolved against the DB
  (`SessionService.resolveUserId`, which also enforces the family is ACTIVE) — not a JWT. The mechanism +
  `IdentityProvider` wrap exactly that existing resolution; no new trust assumptions.
- **Authz declared in code, not in the spec.** Decided (this refinement) **against** a `security:` block in the
  OpenAPI specs / generating `@RolesAllowed`: it would leak the backend role string into the contract and force a
  jaxrs-spec template fork. `@RolesAllowed` is a policy binding, not a DTO, so hand-placing it on the concrete
  Resource override does not violate the "never hand-write a generated DTO" rule.
- **`@Context` deprecation (the original trigger)** dissolves for free — protected Resources stop reading
  `HttpHeaders` entirely once auth is central.
- Affected slices: `account/keymaterial` (`AccountResource`, `AccountWrapsResource`), `account/login`
  (`LoginResource`, `TokenResource`, `SessionResource`), `admin/setup` (`SetupResource`), `admin/tenant`
  (`TenantsResource`); `shared/identity/ServerOwnerGuard` is folded into the `IdentityProvider`; the
  `shared/http/bearerToken` helper survives for the one lenient endpoint (logout).

### Default-deny: today nothing decides this centrally
Right now there is **no** central place that decides whether a URL needs a token — no
`quarkus.http.auth.permission.*`, no filter, no annotations. An endpoint is "public" merely because its method
*doesn't* call `bearerToken(...)`, and "protected" because it does (deep in the handler). Quarkus permits any
unsecured endpoint by default, so forgetting the token read on a new endpoint silently ships it **open** — the
wrong failure direction. This CR makes the decision declarative *and* flips the default to deny.

### Quarkus settings
- **Default-deny unannotated endpoints** (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`). Every
  endpoint without a security annotation becomes a 401, so public endpoints must be *actively* opened with
  `@PermitAll`. Forgetting an annotation then fails "too secure" (401), never "too open" — the safe direction.
  This makes the annotation the single, mandatory place the token-or-not decision is taken, per method.
  **Sequencing:** this flag is flipped **last, at the close of phase 3** — only once *every* endpoint is
  annotated. Enabling it earlier would 401 every still-unannotated endpoint and break the phased rollout. Until
  then `proactive=false` alone keeps phases 1–2 backward-compatible.
- **Proactive auth must be off** (`quarkus.http.auth.proactive=false`, set in phase 1). With the default (on), a present-but-bad
  token would 401 even on `@PermitAll` — which would break `logout` (must stay idempotent 204 with any token).
  Off ⇒ auth runs only when an annotation demands it; `@PermitAll` ignores the header.
- **Blocking resolution.** `resolveUserId` is blocking `@Transactional` JPA — the `IdentityProvider` must offload
  via `AuthenticationRequestContext.runBlocking { … }`, not run on the IO thread.

## Phases

- [x] **1 — auth infrastructure (backward-compatible scaffolding).** Add `io.quarkus:quarkus-security`. New
  `shared/auth` package: `BearerAuthenticationMechanism : HttpAuthenticationMechanism` (extract `Authorization:
  Bearer` → `TokenAuthenticationRequest`, anonymous `nullItem()` when absent), `SessionTokenIdentityProvider :
  IdentityProvider<TokenAuthenticationRequest>` (`runBlocking` → `resolveUserId` + `VeyqUser.serverRole` →
  `QuarkusSecurityIdentity` with principal = userId and role `SERVER_OWNER` when applicable; throw
  `AuthenticationFailedException` on a bad token), and two `@Provider ExceptionMapper`s
  (`AuthenticationFailedException`/`UnauthorizedException` → 401 `authentication_failed`; `ForbiddenException` →
  403 `not_server_owner`). Set `quarkus.http.auth.proactive=false`. **No Resource or handler touched yet** — with
  no security annotations, every endpoint still permits all, so behaviour is unchanged. **Done:** `./gradlew
  check` green — new `shared/auth` package (`BearerAuthenticationMechanism`, `SessionTokenIdentityProvider`,
  `AuthExceptionMappers` with mappers for `AuthenticationFailedException`/`UnauthorizedException` → 401 and
  `ForbiddenException` → 403), `quarkus.http.auth.proactive=false` set; `SessionTokenIdentityProviderTest` **5/5**
  (owner token → identity with `SERVER_OWNER` role; member token → no owner role; unknown / expired / revoked-
  family token → `AuthenticationFailedException`). All pre-existing slice tests stay green (lazy auth ⇒ the
  mechanism is invoked only on demand; no Resource/handler touched yet). The provider runs the blocking lookup off
  the IO thread via `runBlocking` + `QuarkusTransaction.requiringNew()`; principal = `QuarkusPrincipal(userId)`.

- [x] **2 — migrate the owner-gated slice (tenant).** Class-level `@RolesAllowed(SERVER_OWNER)` on
  `TenantsResource` (all five endpoints are owner-only). The handler needed **no** userId — Slice A records no
  `created_by` — so nothing is threaded in; authz was the token's only consumer. Dropped `accessToken` params and
  the `Unauthenticated` / `NotOwner` variants from `CreateTenantOutcome` / `InviteOutcome` / `RevokeOutcome`;
  collapsed `ListTenantsOutcome` (single variant left) → `list()` returns `List<TenantView>`. Removed the
  per-Resource `unauthorized()` / `forbidden()` and `@Context headers`. Deleted `ServerOwnerGuard`; relocated its
  `SERVER_OWNER` role constant to a new `shared/identity/ServerRoles` (so `@RolesAllowed` and the IdentityProvider
  share one compile-time constant). **One intentional behaviour delta** (decided with the user, a strict security
  improvement): with eager `@RolesAllowed`, **auth now precedes `@Valid`**, so an anonymous request with a
  malformed body returns a uniform **401** instead of the old **400** — no validation detail leaks pre-auth. The
  boundary-400 contract moved to `TenantsSliceTest` (asserted with a real owner token). **Done:** `./gradlew
  check` green — `TenantsSliceTest` **13/13** (the 11 originals with identical owner→2xx / member→403 /
  anonymous→401 / bogus→401 outcomes now produced centrally, plus 2 owner-token boundary-400s for blank-name and
  malformed-email); `http/admin/tenant.http` **10/10** green against a fresh dev MariaDB instance (the four
  anonymous+malformed-body cases re-pointed to assert the new uniform 401).

- [x] **3 — migrate authenticated + public slices.** `account/keymaterial`: `AccountResource` +
  `AccountWrapsResource` → `@Authenticated`, inject `SecurityIdentity`, pass `identity.principal.name`;
  `KeyMaterialHandler` dropped its `SessionService` dep and token params (`forAccessToken`→`forUser`,
  `addWrap`/`deleteWrap` take `userId`), `AddWrapOutcome.Unauthenticated` gone, `deleteWrap`→`Unit`. An
  authenticated caller with no material is now a controlled `500 internal_error` (a server inconsistency), not a
  misleading 401. Removed `unauthorized()` + `@Context`/`bearerToken` from both. Public → `@PermitAll`:
  `SetupResource`, `LoginResource`, `TokenResource`. `SessionResource.logout` → `@PermitAll` keeping its manual
  `bearerToken(headers)` read (the one lenient endpoint — must stay 204 for any token). Flipped
  `deny-unannotated-endpoints=true`. **Same eager-security delta as phase 2** for the `addWrap` boundary: a
  bogus/anonymous caller with a malformed body now 401s before `@Valid` (the 400 contract moved to two new
  owner-token cases in `AccountWrapsSliceTest`). **Done:** `./gradlew check` green — **59 tests** incl.
  `AccountKeyMaterialSliceTest` 7, `AccountWrapsSliceTest` 11 (+2 boundary-400), and a new `DefaultDenyTest`
  proving an unannotated probe endpoint → **401** (default-deny live). `http/account/unlock.http` **3/3** and
  `trust-device.http` **7/7** green against the running dev instance (the three bogus-bearer+malformed-body cases
  re-pointed to the uniform 401). `http/account/login.http` + `http/admin/setup.http` hit only `@PermitAll`
  endpoints (no auth-behaviour change) and need a fresh DB to seed an owner; left **slice-covered** by
  `LoginSliceTest` (9) + `SetupServerSliceTest` (14), which exercise the same endpoints over the real HTTP stack
  (per the user's call — no dev-DB wipe).

- [x] **4 — docs.** New ADR [0008 — centralized HTTP authentication & authorization](../docs/decisions/0008-centralized-http-auth.md)
  (custom `HttpAuthenticationMechanism` + `IdentityProvider` → `SecurityIdentity`; annotation-driven authz +
  default-deny; lazy-auth / proactive-off; the spec-`security:` rejection; the eager-security 401-before-400
  delta; operator-blind boundary of the identity; the two-layer authz model). Updated
  [developer-styleguide](../docs/crosscutting/developer-styleguide.md) §2: the old "authorization through small
  guards" bullet split into **(a)** central annotation-driven auth at the boundary (default-deny, no token in
  resources, logout the lenient exception) and **(b)** fine-grained content-level domain guards that stay in
  handlers. Advanced inbox **#44** → done. **Done:** styleguide + ADR consistent with the shipped code; no doc
  still describes the per-Resource `unauthorized()` / `ServerOwnerGuard` pattern as current.

## Definition of done

Authentication and authorization live in one place: a bearer token is resolved once into a `SecurityIdentity`,
Resources declare `@Authenticated` / `@RolesAllowed(SERVER_OWNER)` / `@PermitAll`, and the 401/403 contract body
comes from two central `ExceptionMapper`s. No Resource reads `HttpHeaders` for auth except the deliberately
lenient `logout`; no handler takes a token or carries an `Unauthenticated`/`NotOwner` variant; `ServerOwnerGuard`
is gone. The `SecurityIdentity` carries only userId + role + opaque token — operator-blind intact. **Default-deny
is active** (`deny-unannotated-endpoints=true`): the per-method annotation is now the single mandatory place the
token-or-not decision is taken, and any unannotated endpoint fails closed. All backend +
http checks green across the four migrated slices, with externally observable behaviour unchanged **save the one
documented delta**: anonymous + malformed body → uniform `401` (auth precedes validation), not `400`.
ADR 0008 + the styleguide record the new pattern. Keep this file as a record after merge.
