---
id: cr-0130
title: Auth hardening — rate-limit the public account-creation endpoints
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Extend the per-IP auth rate limiter to the public account-creation surface. Only `LoginResource` and
`TokenResource` carried `@RateLimited`; the three `@PermitAll` endpoints `SetupResource.createServerOwner`,
`TenantsResource.previewTenantInvite` (an unauthenticated bootstrap-secret lookup oracle) and
`registerAndAcceptTenantInvite` (unauthenticated account creation) had **no** per-IP bound. The limiter already
exists (`AuthRateLimitFilter`, per-IP fixed window, 429) and is name-bound via `@RateLimited`, so this is
purely applying that binding to three more endpoints — plus a reflection guard test so the wiring can't be
silently dropped.

## Context & constraints

- **Verified against source:** `@RateLimited` (a JAX-RS `@NameBinding`, `AnnotationTarget.CLASS,FUNCTION`) was
  present only on `LoginResource` + `TokenResource`; `AuthRateLimitFilter` binds to it. Ed25519 already makes
  login forgery infeasible, so the real value here is bounding DoS / hash-oracle churn / account-creation spam
  on the pre-auth surface.
- **Method-level, not class-level, on Setup:** `@RateLimited` goes on `createServerOwner` only —
  `getSetupStatus` is a frequent pre-auth read (every client boot) and behind a shared NAT could legitimately
  exceed the window, so it's deliberately left unlimited. On `TenantsResource` the two `@PermitAll` methods get
  it; the `@Authenticated` / `@RolesAllowed` methods are already bearer-protected and need no IP bound.
- **Filter mechanism already tested** (`AuthRateLimitTest` / `AuthRateLimitWindowResetTest` — per-IP window,
  429). This CR adds `RateLimitedEndpointsTest`, a reflection guard pinning that the three endpoints carry the
  annotation (a dropped one would un-limit an unauthenticated write with no other signal — the "green ≠ real"
  class the test audit flagged).
- **Deferred — the rest of the P2f bundle (own CRs):** the non-atomic single-use login-challenge (a row-lock
  on `LoginHandler.verify`, a LOW race), and gating Android "No Login" on `KeyguardManager.isDeviceSecure()` (a
  mobile MED). Both are separate from this backend rate-limit slice.
- **Verify:** `cd backend && ./gradlew check` + trust-chain guards.

## Phases

- [x] 1 backend — `@RateLimited` on `SetupResource.createServerOwner`, `TenantsResource.previewTenantInvite` +
  `registerAndAcceptTenantInvite`; added `RateLimitedEndpointsTest` (reflection guard). · **Done:** `./gradlew
  check` **BUILD SUCCESSFUL in 34s** (new guard test green — the annotations resolve at runtime); trust-chain
  guards green.

## Definition of done

`./gradlew check` green incl. `RateLimitedEndpointsTest`; the three public account-creation endpoints carry
`@RateLimited`; `getSetupStatus` deliberately does not.

## Retrospective

- **Pinned the harden, not just applied it:** rather than leave the annotation as an un-tested marker, added a
  reflection guard — a name-bound limiter dropped by accident is exactly the silent regression the guard exists
  to catch.
- **Scope discipline:** shipped the backend rate-limit slice of P2f and left the challenge-race row-lock + the
  Android no-login guard for their own CRs, rather than spread a half-fix across backend + mobile.
- **Next time:** nothing notable; a clean, verified hardening.
