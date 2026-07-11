---
id: cr-0136
title: Auth hardening — atomic single-use login-challenge consume (audit P2f)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Close the read-then-delete race on the single-use login challenge (the LOW-severity item deferred from
[cr-0130](cr-0130-auth-rate-limit-account-creation.md), audit bundle 69 / P2f). `LoginHandler.verify` read the
challenge row, then deleted it, then verified the signature — with no lock between the read and the delete, so
two concurrent verifies of the **same** signed nonce could both observe the row before either delete landed and
both succeed: a captured signature replayed in parallel could mint two sessions off one challenge. This makes
the consume atomic with a pessimistic row lock.

## Context & constraints

- **Fix mirrors the existing pattern.** `SessionService` already row-locks with
  `LockModeType.PESSIMISTIC_WRITE`; `verify` now does the same on the challenge lookup
  (`find("email", …).withLock(PESSIMISTIC_WRITE).firstResult()`), inside its existing `@Transactional`. The
  first verify locks the row, deletes it and commits; a racing verify blocks on the lock, then re-reads nothing
  and falls into the generic-failure branch. Portable across the H2 ∩ MariaDB subset (both honor `SELECT … FOR
  UPDATE` via JPA pessimistic locking) — no dialect-specific SQL.
- **Semantics unchanged otherwise.** The challenge is still consumed whether or not the signature verifies
  (one attempt per challenge, the anti-brute-force property), the timing-equalized dummy-verify branch is
  untouched, and the failure stays a generic 401.
- **Test proves the race is real and closed.** Added `LoginSliceTest.concurrentVerifyConsumesTheChallengeOnce`
  — two threads fire `verify` with the same signature through the real HTTP + transaction path (aligned on a
  `CyclicBarrier`) and assert exactly one 200 + one 401. Verified it **fails without the lock** (both requests
  got 200) and passes deterministically with it (checked stable across 4 reruns). Added as a second ref on the
  `nonce-single-use` coverage criterion (strengthened to name the concurrent case).
- **No `satisfies:`** — a hardening of the already-shipped `uc.account.login`, not a new use-case (matches
  cr-0130). The remaining P2f item, the Android "No Login" `KeyguardManager.isDeviceSecure()` gate, stays
  deferred to its own mobile slice.
- **Verify:** `cd backend && ./gradlew check` + the trust-chain guards.

## Phases

- [x] 1 backend — `PESSIMISTIC_WRITE` lock on the challenge consume in `LoginHandler.verify`; add the
  concurrency slice test; strengthen the `nonce-single-use` coverage. · **Done:** `./gradlew check` →
  **BUILD SUCCESSFUL**, 299 tests / 0 failures / 1 skipped (incl. the new test); test confirmed to fail without
  the fix; `validate_coverage.py` green.

## Definition of done

`./gradlew check` green incl. `concurrentVerifyConsumesTheChallengeOnce`; the challenge consume is atomic under
concurrent verifies; the login coverage cites the concurrency test; trust-chain guards green.

## Retrospective

- **Validated the test against the bug, not just the fix.** Rather than trust a green run, I removed the lock
  and confirmed the concurrency test actually fails (two 200s) — so it's a real guard, not a false-green. That's
  the right rigor for a race whose whole point is that it's invisible when it doesn't trigger.
- **Reused the codebase's own locking idiom** (`SessionService`'s `PESSIMISTIC_WRITE`) instead of inventing a
  conditional-delete-count gate — same guarantee, consistent with the existing pattern, portable across H2 and
  MariaDB.
- **Next time:** nothing notable — a small, well-scoped hardening with a test that earns its keep.
