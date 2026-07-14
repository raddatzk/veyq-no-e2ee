# Concern: Public rate limiting

- **ID:** cc.security.rate-limit
- **Type:** **Cross-cutting concern** — a horizontal security mechanism that protects many flows; it has no
  standalone end-user goal (not a use-case) and is not a feature substrate (not an enabler). It is recorded
  here so its **mechanism + its verification are traceable** from the threat model, rather than living as an
  orphan test next to the code (the gap a 2026-06 coverage audit found).
- **Status:** two independent bounds shipped — the **per-IP auth bound** (cr-0044/cr-0130) and the **per-share
  password-attempt bound** ([cr-0165](../../changes/cr-0165-share-password-attempt-bound.md)). Verification is
  **partial by design**: the auth challenge/verify/refresh endpoints carry runtime `429` assertions + window-reset
  + per-IP isolation; the three **method-level** account-creation endpoints (cr-0130) are pinned by a **static**
  annotation-presence check, **not** a runtime `429` test; the share bound has runtime assertions (cr-0165).

## Protects against

**Online** brute-force / credential-stuffing / DoS against the **public** authentication surface — the
endpoints reachable without a session, where an attacker can hammer login or spin token churn. (Offline
brute-force of the password is a *separate* property, handled cryptographically — see
[security-model](security-model.md): Secret Key / 2SKD and the Ed25519 auth-key stance.)

## Mechanism

A **fixed-window, per-client-IP** counter (`backend/.../shared/ratelimit/AuthRateLimitFilter.kt`): a JAX-RS
resource/method marked **`@RateLimited`** is counted per IP per window; past the configured limit the request
is rejected with **`429` + `rate_limited`**. Limit + window are config (`application.properties`,
`veyq.ratelimit.auth.max-requests` / `veyq.ratelimit.auth.window-seconds`).

## Governs (per-IP auth bound)

**Five** governed endpoints across **four** resources in **three** specs. Two carry a **class-level**
`@RateLimited` (every method governed): `LoginResource` (login challenge/verify) and `TokenResource` (token
refresh) — `auth.openapi.yaml`. Three are **method-level**: `SetupResource.createServerOwner`
(`setup.openapi.yaml`), and `TenantsResource.previewTenantInvite` + `.registerAndAcceptTenantInvite`
(`tenants.openapi.yaml`) — the unauthenticated bootstrap-secret and account-creation oracles. The
**authoritative** set is the `@RateLimited` annotation in the source, never a copy here — a hand-kept matrix
drifts (as it did: cr-0044 deleted a phantom `POST /api/token` row). When you need the exact set,
`grep -rn '@RateLimited'`.

> The **runtime** tests below hit the class-level endpoints, proving mechanism **and** `@RateLimited` wiring in
> one. The three **method-level** account-creation endpoints are pinned instead by a **static**
> annotation-presence check (`RateLimitedEndpointsTest`, cr-0130) — the very "revisit a static check when the set
> grows" tool the earlier note anticipated, now built; it asserts the annotation is present without a runtime
> `429` per endpoint.

## Known limits (honest, defense-in-depth — not a hard guarantee)

- **Per-instance / in-memory.** The counter is not shared across replicas; behind a load balancer each
  instance counts independently.
- **Resets on restart.** A process restart clears the windows.
- **Trusts the client IP.** Behind a proxy this depends on correct forwarded-IP handling; a spoofable/NAT'd
  source can blur per-IP isolation.

These are **deferred** hardening items (a distributed store, hardened proxy-IP trust), recorded so the limit
isn't mistaken for a strong guarantee. It raises the cost of automated abuse; it does not make it impossible.

## Verified by

Runtime tests (RestAssured) hit the **real** governed endpoints, so a `429` can only fire where `@RateLimited`
is actually wired — proving mechanism **and** configuration in one. Each test presents its own client IP via
`X-Forwarded-For` (the test profile enables `proxy-address-forwarding`), so the cases are order-independent and
the per-IP property is asserted directly.

- `AuthRateLimitTest.challengeIsThrottledOverTheLimit` — past the limit `/login/challenge` → `429` +
  `rate_limited`.
- `AuthRateLimitTest.verifyIsThrottledOverTheLimit` — `/login/verify` is throttled.
- `AuthRateLimitTest.refreshIsThrottledOverTheLimit` — `/token/refresh` is throttled.
- `AuthRateLimitTest.oneIpTrippingTheLimitDoesNotThrottleAnother` — **per-IP isolation**: one IP hitting the
  limit does not throttle a second IP.
- `AuthRateLimitWindowResetTest.theCounterClearsAfterTheWindowElapses` — **window reset**: past a 1-second
  window the per-IP counter clears and requests are allowed again.

**Out of scope for any test:** whether the configured *limit value* is the right number — that is a policy
judgement (review + config), not something a test asserts.

## Governs (per-share password-attempt bound, cr-0165)

A **second, independent** bound protects password-protected shares ([ADR 0037](../decisions/0037-password-protected-shares.md))
from online password guessing. It is **not** `@RateLimited` and **not** per-IP: a share's anonymous guests sit
behind one proxy address, so a per-IP counter would collapse to a global cap on the share (throttling honest
visitors) while an IP-rotating attacker evades it. The attack targets one `shareId`, so the counter keys on that.

- **Mechanism:** `backend/.../features/sharing/SharePasswordAttemptLimiter.kt` — a per-`shareId` fixed window,
  mirroring `AuthRateLimitFilter` (in-memory, per-instance). It is consumed **only on a wrong attempt**, inside
  `SharingHandler.passwordCheck`: a correct password is admitted *before* the counter is touched (a legitimate
  guest is never locked out), and a tokenless challenge-fetch never spends budget. Over the cap the guest gets
  `429 rate_limited` instead of the `403 share_password_incorrect` oracle. Config
  `veyq.share.password.rate.max-per-window` (default **10**) / `.window-seconds` (default **60**).
- **Rides all six guest endpoints:** since cr-0164 the same `passwordCheck` fronts resolve, blob GET, and the four
  guest-write ops — so the bound applies wherever a wrong password can be presented, not just the resolve.

**Verified by** `SharePasswordAttemptBoundTest` (runtime, RestAssured; drives the budget down to 3 via its own
`@TestProfile`): N wrong → `403`, the N+1st → `429`; a tokenless resolve never consumes budget; one share's
exhausted budget does not block another (the per-share axis); and a guest **write** op inherits the bound.

## Related

- Concept: [security-model](security-model.md) (threat model — this is the "online brute-force / DoS" row's
  mechanism + verification)
- Code: `backend/.../shared/ratelimit/{RateLimited,AuthRateLimitFilter}.kt`,
  `backend/.../features/sharing/SharePasswordAttemptLimiter.kt`
- Change-requests: [cr-0044](../../changes/cr-0044-rate-limit-tests-and-auth-gaps.md) (auth verification),
  [cr-0130](../../changes/cr-0130-auth-rate-limit-account-creation.md) (account-creation endpoints + static check),
  [cr-0165](../../changes/cr-0165-share-password-attempt-bound.md) (per-share password-attempt bound)
