# Concern: Public-auth rate limiting

- **ID:** cc.security.rate-limit
- **Type:** **Cross-cutting concern** — a horizontal security mechanism that protects many flows; it has no
  standalone end-user goal (not a use-case) and is not a feature substrate (not an enabler). It is recorded
  here so its **mechanism + its verification are traceable** from the threat model, rather than living as an
  orphan test next to the code (the gap a 2026-06 coverage audit found).
- **Status:** mechanism shipped; verification **complete** — every governed endpoint, window reset and per-IP
  isolation are asserted (closed by [cr-0044](../../changes/cr-0044-rate-limit-tests-and-auth-gaps.md)).

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

## Governs

Two public-auth resources carry a **class-level** `@RateLimited`, so every method they expose is governed:
`LoginResource` (login challenge/verify) and `TokenResource` (token refresh). The **authoritative** endpoint
list is the OpenAPI spec (`contracts/api/auth.openapi.yaml`) plus the annotation on those two resources — this
concern deliberately does **not** restate each path. A hand-kept endpoint matrix here would only duplicate the
spec and drift out of sync with it (which is exactly what it did: cr-0044 had to delete a phantom `POST
/api/token` row). When you need the exact set, read the source, not a copy.

> The **runtime** tests below hit the real endpoints, so they prove both the mechanism **and** the
> `@RateLimited` wiring in one — no separate static "is the annotation present?" check is needed at this scale.
> If the governed set grows large enough that runtime-testing each endpoint hurts, revisit a static
> annotation check then (pain before tool).

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

## Related

- Concept: [security-model](security-model.md) (threat model — this is the "online brute-force / DoS" row's
  mechanism + verification)
- Code: `backend/.../shared/ratelimit/{RateLimited,AuthRateLimitFilter}.kt`
- Change-request: [cr-0044](../../changes/cr-0044-rate-limit-tests-and-auth-gaps.md) (closes the verification gap)
