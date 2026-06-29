---
id: cr-0011
title: Scheduled purge of expired session tokens — extend the @Scheduled cleanup seam to auth
status: done             # planned → in-progress → done (then keep as a record)
satisfies: []            # cross-cutting infra cleanup — no use-case (like cr-0012); realigns the session lifecycle (ADR 0004)
adrs: ["0004"]
invariants: []           # no operator-blind risk — purely server-side opaque token-hash rows, no content
---

## Goal

Session/auth rows accumulate **forever** today: expired access tokens (15-min TTL), expired refresh tokens,
stale login challenges, and the session families behind logged-out / reuse-revoked sessions are only ever
**status/expiry-gated at read** (ADR 0004) — never physically deleted. cr-0009 introduced the first
`@Scheduled` cleanup (`TenantInvitePurge`); this CR extends that **same seam** to the auth tables with a
`SessionPurge` that deletes what is no longer needed, on a configurable schedule.

## Context & constraints

- Pattern to mirror: [`TenantInvitePurge`](../backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantInvitePurge.kt)
  (`@Scheduled` bean → store delete methods, config-driven interval). ADR [0004](../docs/decisions/0004-session-tokens.md)
  (opaque token hashes, rotating refresh, reuse-detection); the tables are `V5__login_sessions.sql`
  (`veyq_login_challenge`, `veyq_session_family`, `veyq_refresh_token`, `veyq_access_token`).
- **No operator-blind risk:** every purged row is an opaque token **hash** or an opaque nonce + status —
  server-side operational metadata, never content. No schema change (deletes only).
- **The reuse-detection window must be preserved.** A presented `SUPERSEDED` refresh token revokes its whole
  family (the replay tripwire, `SessionService.refresh`), and that check runs **before** the expiry check.
  So the purge must **keep `SUPERSEDED` refresh tokens until they EXPIRE** — only then is a replay harmless
  (an expired token is useless and rejected regardless). Purging strictly by `expires_at < now` satisfies
  this: the reuse window equals the refresh TTL (`REFRESH_TTL_DAYS`).
- **FK order:** access/refresh tokens reference the family, so purge **tokens first, then** families that
  have no tokens left.

## Phases

- [x] **1 — backend.** Add `SessionPurge` (`@Scheduled(every = "{veyq.session.purge.every}")`) in
  `features/account/login`, delegating to new `SessionService` delete methods (all `@Transactional`,
  matching by `expires_at < now`):
  - **access tokens** where `expiresAt < now`;
  - **refresh tokens** where `expiresAt < now` (covers expired `CURRENT` *and* expired `SUPERSEDED` — the
    reuse window is the TTL, so an expired replay is already rejected);
  - **login challenges** where `expiresAt < now`;
  - **session families** with **no remaining tokens** (after the two token purges) — cleans both `REVOKED`
    (logout / reuse-revoked) and abandoned-but-`ACTIVE` families whose tokens all expired.
  Config default `veyq.session.purge.every=1h` in `application.properties`. **Done:** `SessionService` got
  `purgeExpired{Access,Refresh}Tokens` / `purgeExpiredChallenges` (by `expiresAt < now`) +
  `purgeEmptyFamilies` (HQL `id not in (select familyId …)` over both token tables); `SessionPurge` bean
  chains them (tokens → families). `./gradlew check` green — `SessionPurgeTest` proves expired
  access/refresh/challenge rows deleted + the token-less family gone, while the live session, the
  **unexpired SUPERSEDED** refresh (reuse-detection intact) and the live challenge are kept.
- [x] **2 — docs.** **Done:** inbox **#42** → done; added a developer-styleguide §13 pattern bullet
  ("Scheduled cleanup → a `@Scheduled` purge bean", naming `TenantInvitePurge`/`SessionPurge` + the
  reuse-window + FK-order rules). No use-case / coverage file (infra cleanup).

## Definition of done

Expired/abandoned auth rows are physically removed on a schedule (the second `@Scheduled` purge, mirroring
cr-0009): expired access + refresh tokens, stale login challenges, and token-less session families are
deleted, while live tokens and **unexpired superseded** refresh tokens (the reuse-detection tripwire) are
preserved. No schema change, no operator-blind impact. `./gradlew check` green with `SessionPurgeTest`;
inbox #42 done. Keep this file as a record after merge.
