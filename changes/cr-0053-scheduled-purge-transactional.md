---
id: cr-0053
title: Scheduled purges run in a transaction — fix "Session/EntityManager is closed" (backend)
status: done
satisfies: []
adrs: ["0004"]
invariants: [operator-blind-e2ee]
---

## Goal

Stop the two scheduled cleanup jobs from throwing `IllegalStateException: Session/EntityManager is closed` on
every fire. Both `@Scheduled run()` methods are **not** `@Transactional` and call several `@Transactional`
store/service methods in sequence on the scheduler worker thread — the known Quarkus `@Scheduled` + Panache
pattern: after the first nested transaction commits, its session is closed and the next call fails. The fix is
the documented one: make each `@Scheduled run()` itself `@Transactional` so all its DB work shares one
session/transaction.

Affected (both pre-existing, **not** from cr-0052):
- [`SessionPurge.run()`](../backend/src/main/kotlin/com/veyq/app/features/account/login/SessionPurge.kt) (cr-0011)
  — 4 purge calls (access/refresh tokens, challenges, empty families).
- [`TenantInvitePurge.run()`](../backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantInvitePurge.kt)
  (cr-0009) — expire-overdue + purge-terminal invites.

## Context & constraints

- **Low severity, no security/operator-blind impact.** The purges only *physically delete* expired/terminal
  rows; a failing fire leaves them in place (DB bloat), but expiry-gating + refresh reuse-detection keep working
  on those rows regardless (the `SessionService` purge note). No ciphertext or content is touched.
- **One transaction for the batch is fine.** Volumes are small and hourly; the nested `@Transactional` store
  methods join the outer transaction (REQUIRED propagation) → one session opened once. FK / step order is
  preserved within the single transaction (SessionPurge deletes tokens before families; TenantInvitePurge
  expires before deleting).
- **Verify:** `./gradlew -p backend check` green (the existing `SessionPurgeTest` / `TenantsSliceTest` purge
  tests still pass — they exercise the purge *logic* directly), **plus** a live confirmation against the running
  `quarkusDev` instance: temporarily shorten the purge interval and observe a clean steady-state fire with **no**
  `EntityManager is closed` in the log, then restore the interval.

## Phases

- [x] 1 transactional purges — **done.** `@Transactional` on both `@Scheduled run()` methods. · Verified:
  `./gradlew -p backend check` green (and the `TenantInvitePurge` closed-session stack trace that previously
  showed at test shutdown is gone); plus a live run against the dev instance — with the purge interval
  temporarily shortened, all inserted expired probe rows were purged and **zero** "Session/EntityManager is
  closed" errors appeared after the fix went live.

## Definition of done

Both scheduled purges fire without `Session/EntityManager is closed`; expired rows are deleted as intended.
`./gradlew -p backend check` green; the clean fire confirmed live.

## Retrospective

Done — two annotations fixed a pre-existing closed-session error in both scheduled purges; `./gradlew check`
green and a live run confirmed clean purges with no error.

- **The live verification was flaky and briefly misled me.** I checked "did the purge fire?" by inserting an
  expired row and *polling* for its deletion over fixed 30–70s windows — but the purge's fire cadence kept
  landing just outside each window, so I got repeated `probe_purged=no` false negatives and momentarily
  concluded the scheduler wasn't firing at all. The deterministic end-state query (all probe rows gone + zero
  closed-session errors) settled it. Root cause: I built the check on a **race** (poll vs an unknown async
  cadence) instead of a deterministic signal — ironic right after cr-0052's retro said "verify, don't assert"
  (I verified, but with a racy method). **Next time:** for scheduled/async behaviour, assert on the *end state*
  or force a deterministic trigger; never conclude from one noisy timing window, and prefer "is it gone now?"
  over "was it gone within N seconds?".
- **Shell-robustness papercuts cost time.** A `tail -n +"$(wc -l)"` broke on `wc`'s leading-whitespace, and a
  stray exit-127 made an early "probe gone?" read unreliable — both made the picture muddier than the fix
  actually was. Small, but they compounded the flaky-window confusion. **Next time:** sanity-check the harness
  output (does the count look real?) before trusting a negative.

Process note: this was a good catch by the human — surfacing the runtime error log turned a silently-failing
hourly cleanup (pre-existing from cr-0009/0011, not cr-0052) into a two-line fix.
