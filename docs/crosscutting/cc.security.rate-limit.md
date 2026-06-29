# Concern: Public-auth rate limiting

- **ID:** cc.security.rate-limit
- **Type:** **Cross-cutting concern** — a horizontal security mechanism that protects many flows; it has no
  standalone end-user goal (not a use-case) and is not a feature substrate (not an enabler). It is recorded
  here so its **mechanism + its verification are traceable** from the threat model, rather than living as an
  orphan test next to the code (the gap a 2026-06 coverage audit found).
- **Status:** mechanism shipped; verification **partial** — see *Verified by* (closed by
  [cr-0044](../../changes/cr-0044-rate-limit-tests-and-auth-gaps.md)).

## Protects against

**Online** brute-force / credential-stuffing / DoS against the **public** authentication surface — the
endpoints reachable without a session, where an attacker can hammer login or spin token churn. (Offline
brute-force of the password is a *separate* property, handled cryptographically — see
[security-model](security-model.md): Secret Key / 2SKD and the Ed25519 auth-key stance.)

## Mechanism

A **fixed-window, per-client-IP** counter (`backend/.../shared/ratelimit/AuthRateLimitFilter.kt`): a JAX-RS
resource/method marked **`@RateLimited`** is counted per IP per window; past the configured limit the request
is rejected with **`429` + `rate_limited`**. Limit + window are config (`application.properties`,
`veyq.auth.rate-limit.*`).

## Governs (the application matrix)

All four public-auth endpoints already carry `@RateLimited`:

| endpoint | resource | annotated |
|---|---|---|
| `POST /api/login/challenge` | `LoginResource` | ✅ `@RateLimited` |
| `POST /api/login/verify` | `LoginResource` | ✅ `@RateLimited` |
| `POST /api/token` | `TokenResource` | ✅ `@RateLimited` |
| `POST /api/token/refresh` | `TokenResource` | ✅ `@RateLimited` |

> The **runtime** tests below hit these **real** endpoints, so they prove both the mechanism **and** the
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

- **Now:** `AuthRateLimitTest.challengeIsThrottledOverTheLimit` — past the limit `/login/challenge` → `429`.
  Real assertion, but **narrow** (only `challenge`; no window-reset / per-IP / verify+token coverage).
- **To add ([cr-0044](../../changes/cr-0044-rate-limit-tests-and-auth-gaps.md)):**
  - `verify` and `token`/`refresh` are throttled (the other governed endpoints).
  - **window reset** — the count clears after the window elapses.
  - **per-IP isolation** — one IP tripping the limit does not throttle another IP.

**Out of scope for any test:** whether the configured *limit value* is the right number — that is a policy
judgement (review + config), not something a test asserts.

## Related

- Concept: [security-model](security-model.md) (threat model — this is the "online brute-force / DoS" row's
  mechanism + verification)
- Code: `backend/.../shared/ratelimit/{RateLimited,AuthRateLimitFilter}.kt`
- Change-request: [cr-0044](../../changes/cr-0044-rate-limit-tests-and-auth-gaps.md) (closes the verification gap)
