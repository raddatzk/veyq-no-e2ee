---
id: cr-0044
title: Rate-limit test depth + auth-gap tests (cc.security.rate-limit)
status: done
satisfies: [cc.security.rate-limit]
adrs: []
invariants: []
---

## Goal

Close the verification gap a 2026-06 coverage audit found: **rate-limiting** has exactly one shallow test
(`AuthRateLimitTest.challengeIsThrottledOverTheLimit` — only that the 429 fires on `/login/challenge`) and is
**invisible from the threat model**, and two smaller auth properties are claimed but unasserted. The mechanism
already works and all governed public-auth endpoints carry `@RateLimited` — this CR is **backend tests + the
traceability doc**, no wire/behaviour change. Mechanism + matrix: [cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md).

## Context & constraints

- **Implementation vs. configuration (the lean split).** The rate-limiter's *correctness* (window, per-IP,
  429) is the **mechanism** — tested **once**, here. Whether an endpoint is *wired* (`@RateLimited`) is the
  **configuration** — proven by running the same tests against the **real** governed endpoints (a 429 can't
  fire unless the annotation is there), so **no static annotation checker** is built at this scale (pain
  before tool — revisit if the governed set grows large).
- **No new record-type machinery.** We deliberately did **not** add a validator-enforced `cc.` trust-chain
  type or a per-use-case `Governed by` requirement — the cross-cutting concern is a lightweight doc
  ([cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md)) + the threat-model row, not new
  tooling.
- **Authz is out of scope** — the audit found it already well-covered (default-deny per ADR 0008 + per-slice
  boundary tests: 401/404/403, no cross-account read). The real gap was rate-limit.
- **Correctness of the limit value is not tested** — that the configured number is *right* is a policy
  judgement (review + config), not a test assertion.
- Backend-only. Verify: `cd backend && ./gradlew test`.

## Phases

- [x] 1 rate-limit depth — extend `AuthRateLimitTest`: **`verify`** and **`token/refresh`** are throttled
  (the other governed endpoints, proving their `@RateLimited` wiring), **window reset** (the count clears once
  the window elapses), **per-IP isolation** (one IP tripping the limit does not throttle a second IP). · Done
  when: `./gradlew test` green with the new methods asserting each.
- [x] 2 auth-gap tests — **nonce expiry** (`LoginSliceTest`: a stale login-challenge nonce → 401, not just the
  single-use replay already covered) and **DoS-bystander** (strengthen `unknownRefreshTokenIsPlain401`: seed a
  live refresh family, fire a bogus unknown refresh, assert the **innocent family still refreshes** — i.e. the
  unknown token revokes nobody). · Done when: `./gradlew test` green.
- [x] 3 traceability — move the new methods in [cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md)
  *Verified by* from "to add" to "now"; confirm the `security-model` brute-force/DoS row points at the concern.
  · Done when: `python3 scripts/validate_doc_links.py` green.

## Definition of done

The public-auth rate limiter is verified for **all three** governed endpoints, **window reset** and **per-IP
isolation** (not just a single 429), and is traceable from the threat model via
[cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md). Nonce-expiry and the DoS-bystander
property are asserted. `./gradlew test` green; `validate_doc_links.py` green. No wire/behaviour change.

## Retrospective

**What the planning got wrong (doc → execution).** The CR (and the `cc.security.rate-limit` matrix) framed the
governed surface as **four** endpoints, listing a `POST /api/token` row. There is no such endpoint — the auth
spec exposes only `challenge`, `verify`, `token/refresh` and (authed) `logout`. The phantom row came from
reading `TokenResource`'s class-level `@RateLimited` as two endpoints. Execution corrected it: the matrix now
lists **three**, and the CR text/DoD say "all three". Two further doc drifts surfaced while editing the concern
and got fixed in passing — the config key was documented as `veyq.auth.rate-limit.*` but is actually
`veyq.ratelimit.auth.*`, and the status/Verified-by were stale. Lesson: when a doc asserts a concrete matrix,
verify it against the spec/source before deriving a work-order from it.

Two tooling links also needed closing, neither anticipated by the plan: `validate_changes.py` only resolved
`satisfies:` against `uc.*`/`en.*`, so this CR's `satisfies: [cc.security.rate-limit]` was already red at HEAD
— extended the resolver to recognise `docs/crosscutting/cc.*.md` concern docs as first-class trust-chain
targets (the concern is exactly what the CR closes, so the link must resolve). And the DoS-bystander rename
(`unknownRefreshTokenIsPlain401` → `…AndRevokesNobody`) broke the `uc.account.login` coverage map, which pins
test-method names — updated the reference. Renaming a covered test now means updating its coverage yaml.

**What execution got right that the plan under-specified.** The plan said "extend `AuthRateLimitTest`" but
didn't address that **every RestAssured call originates from `127.0.0.1`** — so adding sibling methods to one
`@QuarkusTest` instance would share a single per-IP bucket and make the tests order-dependent and flaky. The
fix (enable `proxy-address-forwarding` in the test profile and give each method its own `X-Forwarded-For`
client IP) both isolates the methods *and* is exactly the primitive the per-IP-isolation assertion needs — one
mechanism, two payoffs. Window-reset needs a 1-second window (different build-time config), so it lives in a
second profile/class (`AuthRateLimitWindowResetTest`) with a bounded real-time poll rather than a fixed sleep,
to stay robust against window-boundary straddle.

**Net.** No wire/behaviour change. `./gradlew test` green (full suite); `validate_doc_links.py` green. The
limiter is now verified for all three governed endpoints + window reset + per-IP isolation, and nonce-expiry /
DoS-bystander are asserted — all traceable from the threat model.

**Next time.** Two follow-ups landed from this retro: (1) the concern's endpoint matrix was **deleted** and
replaced with a pointer to the canonical source (OpenAPI + the two `@RateLimited` resources) — a hand-kept
restatement of the spec can only drift; (2) a new rule in [changes/README](../changes/README.md) —
*verify checkable claims (paths, config keys, counts) against the source before they become a work-order
premise; don't duplicate a canonical source in prose*. The 127.0.0.1 shared-bucket gap stays a one-off
judgement call (shared process-wide state ⇒ think isolation), not a process.
