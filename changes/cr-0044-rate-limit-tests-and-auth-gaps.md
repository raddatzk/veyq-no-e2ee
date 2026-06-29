---
id: cr-0044
title: Rate-limit test depth + auth-gap tests (cc.security.rate-limit)
status: planned
satisfies: [cc.security.rate-limit]
adrs: []
invariants: []
---

## Goal

Close the verification gap a 2026-06 coverage audit found: **rate-limiting** has exactly one shallow test
(`AuthRateLimitTest.challengeIsThrottledOverTheLimit` — only that the 429 fires on `/login/challenge`) and is
**invisible from the threat model**, and two smaller auth properties are claimed but unasserted. The mechanism
already works and all four public-auth endpoints carry `@RateLimited` — this CR is **backend tests + the
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

- [ ] 1 rate-limit depth — extend `AuthRateLimitTest`: **`verify`** and **`token`/`refresh`** are throttled
  (the other governed endpoints, proving their `@RateLimited` wiring), **window reset** (the count clears once
  the window elapses), **per-IP isolation** (one IP tripping the limit does not throttle a second IP). · Done
  when: `./gradlew test` green with the new methods asserting each.
- [ ] 2 auth-gap tests — **nonce expiry** (`LoginSliceTest`: a stale login-challenge nonce → 401, not just the
  single-use replay already covered) and **DoS-bystander** (strengthen `unknownRefreshTokenIsPlain401`: seed a
  live refresh family, fire a bogus unknown refresh, assert the **innocent family still refreshes** — i.e. the
  unknown token revokes nobody). · Done when: `./gradlew test` green.
- [ ] 3 traceability — move the new methods in [cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md)
  *Verified by* from "to add" to "now"; confirm the `security-model` brute-force/DoS row points at the concern.
  · Done when: `python3 scripts/validate_doc_links.py` green.

## Definition of done

The public-auth rate limiter is verified for **all four** governed endpoints, **window reset** and **per-IP
isolation** (not just a single 429), and is traceable from the threat model via
[cc.security.rate-limit](../docs/crosscutting/cc.security.rate-limit.md). Nonce-expiry and the DoS-bystander
property are asserted. `./gradlew test` green; `validate_doc_links.py` green. No wire/behaviour change.

## Retrospective

_(to be filled on close)_
