# veyq hardening & remediation plan

Consolidated, deduplicated plan from the multi-platform code review (internal review agents + Codex),
with the findings verified against `main`. Execute roughly in order; each phase is meant to be one PR.

Legend: `[x]` done · `[ ]` open · `[~]` partially done / queued.

---

## Phase 0 — Decisions + doc/cleanup — DONE (commit b78b77ea)
- [x] **D1 — document the threat model.** `security-model.md`: 2SKD protects *content* (KEK folds in the
  Secret Key); the server-side login verifier is a separate password-only Argon2id proof, so a DB dump
  permits offline brute-force of the *login* password → a session + operational metadata, never content.
  Deliberate trade-off. (The Argon **cost** hardening is the code part → Phase 2.)
- [x] **D2 — deferred (documented).** Public-share upload is authorized by `shareId` alone (junk/DoS, not a
  confidentiality break). Full write-proof (share-key MAC / upload-capability token) deferred; interim
  mitigation = per-share rate-limit + byte quota (lands with Phase 1 rate-limiting).
- [x] **D3 — retire the Hocuspocus collaboration sidecar.** Plaintext, not operator-blind, no client points
  at it. Moved `collaboration/` → `legacy/collaboration/` and unwired it from the active stack
  (docker-compose service + `JOURNAL_LIVE_*` envs + traefik routing, build-compose entry, CI
  build/publish jobs, CodeQL path, `.env.template` profile lines).

## Phase 1 — Security-critical (backend) — DONE
- [x] **P1.1 Session-token hashing.** `Session` now stores `token_hash = SHA-256(token)` (base64url), looks
  up by hash; the raw token only ever reaches the client. Migration V23 (rename column + clear sessions).
- [x] **P1.2 `ScopeAccessGuard` + close the membership gap.** New `ScopeAccessGuard` (grant **and**, for
  tenant scopes, ACTIVE membership). The three gap sites now route through it: `ScopeGrantRewrapHandler`
  (get/rewrap), `MyGrantsHandler` (filters stale tenant grants), `JournalRelay.onOpen` (relay room). Slice
  test asserts a removed member's stale tenant grant is hidden (`/me/grants`) + unreadable (403 NOT_A_MEMBER).
  *(Migrating the other ~9 content handlers' identical inline check onto the guard = M1, deferred to Phase 4.)*
- [x] **P1.3 Rate limits.** `RateLimiter` (fixed-window) + `RateLimitFilter` (per-IP, 429) on login,
  password-reset, invite, public-share upload. Off in dev/test (`veyq.ratelimit.enabled`) so the suites stay
  green; covers the D2 interim mitigation. Unit-tested.

## Phase 2 — Client correctness (cross-platform)
- [x] **P2.1 Android raw-JSON → escaped.** `Login.kt`, `SetupServer.kt`, `Tenant.kt`,
  `RegisterViaInvitation.kt` now wrap every free-text field in `JSONObject.quote(...)`, so `"`/`\`/newline
  can't corrupt or inject the body. Regression: the Android setup e2e registers + logs in with `"`/`\` in
  the display name + password.
- [x] **P2.2 Argon2 8 → 32 MiB.** `Argon2Verifier` + all client setup/register/reset paths (web/iOS/Android,
  incl. `v1.ARGON` which drives web reset) + `LoginSliceTest` now use `m=32768` — above the OWASP floor while
  keeping JS login responsive. This also fixed a latent bug — web reset already minted a verifier the 8192
  backend couldn't verify. The client authVerifier-creation params must match the backend verifier (it
  hardcodes them, no kdfParams on the verifier), so they move in lockstep; register+login verified
  cross-stack on web + Android. (The invite-temp-key Argon stays 65536 — it's a separate params-carried KDF,
  not the auth verifier.)
- [~] **P2.3 Live-journal bugs.** The seed race is largely resolved by 6a (all platforms now reconstruct from
  the store instead of dual-seeding from markdown; the bootstrap only seeds a legacy empty journal). Still
  minor: cursor unit mismatch (awareness UTF-16 vs char-array/byte offsets → caret drift past emoji) and
  iOS `handleStep2(payload) {}` reading `currentText` before the completion. Deferred (cosmetic).
- [x] **P2.4 Live-journal GCM nonce.** `V1.journalSeal`/`journalOpen` on all three platforms: each frame/blob
  is `salt(16) || nonce(12) || AES-256-GCM(HKDF(contentKey, salt, "veyq/v1/journal"), nonce, pt)` — a FRESH
  per-message sub-key, so every GCM key is used exactly once and the random 96-bit nonce can never collide
  under the long-lived content key (birthday bound eliminated). Applied to the CRDT layer (relay frames +
  persisted snapshot + increments). The low-volume Markdown read projection stays on the plain
  `nonce||AEAD(contentKey)` envelope (it's read by the plain getJournal reader). Conformance vector
  (`journal_open`) pins byte-identical opening across web/iOS/Android; persistence + relay + share e2e pass
  on all platforms.

## Phase 3 — Performance (backend)
- [x] **P3.1 Gallery / share-media N+1.** `MediaHandler.toApiBatch` + `PublicShareMediaHandler.toApiBatch`:
  a gallery maps in a fixed 3 queries (media + `listByMediaIds` IN + `listByIds` IN) instead of +1 blob +1
  derivative-list +N derivative-blob per medium (~1200 → 3 for 200 photos).
- [~] **P3.2 Blob budget O(n²)→O(1).** Done: a `staged_bytes` running counter on the (row-locked) upload row
  replaces re-summing every staged chunk on each PUT. **Deferred:** moving blob IO out of `@Transactional`
  + a streaming `InputStream` (vs `readAllBytes`+base64) — a BlobStore-interface refactor, its own change.
- [x] **P3.3 Share-chunk ACL.** `getShareChunk` no longer rebuilds the whole subtree blob set per chunk;
  `blobBacksSubtreeMedia` checks in at most two IN queries (subtree media, then their derivatives).
- [x] **P3.4 Indexes + tenant purge.** V25 adds `idx_media_scope`, `idx_blob_upload(scope_id,status)`,
  `idx_blob_upload(status,created_at)`; tenant purge is one transaction per tenant (GC lists ids, then calls a
  per-tenant `@Transactional purgeTenant` cross-bean — a backlog can't become one giant write tx).

## Phase 4 — Maintainability / cleanup
- [x] **P4.0 Finish the `ScopeAccessGuard` migration (M1).** Phase 1 created the guard and routed the three
  gap sites through it; migrated the remaining content handlers' identical inline `requireKeyHolder`
  (ScopeContentKeyHandler, JournalHandler, TrackHandler, MemoryTreeHandler, CurateHandler, MediaHandler,
  ShareHandler, ScopeItemsHandler, BlobUploadHandler) onto it so the check can't drift again. The
  membership/admin handlers keep their own `grants`/`memberships` reads (different gate, out of scope).
- [x] **P4.1 Account/KeyBag factory.** Extracted `AccountFactory` (`shared/identity`) — the single place a
  new account's identity keypair + master-key unlock wraps + auth verifier + self-owned PERSONAL_ROOT scope
  (and account-material suite/wrap/recovery validation) is persisted. Both `SetupServerHandler` (ServerOwner)
  and `RegisterViaInvitationHandler` (ServerMember) route through it. Verified live: register-via-invitation
  + media web E2E green against a fresh backend.
- [~] **P4.2 Shared helpers.** Fixed the hardcoded `if (suiteId != "v1")` in `BlobUploadHandler` →
  `suites.isKnown(...)`; the upload now records its frames' `suite_id` (V26) and the finalized blob + chunk
  reads carry the real suite (no more hardcoded `"v1"`). Remaining: centralize `toWrap`/`requireSuite`/Base64/
  `unauthorized()` (cosmetic, deferred — folded into P4.4).
- [→] **P4.3 String → typed enums** for roles/status/scope-kind in entities (`Tenancy.kt:21`). **Deferred to
  backlog** — pure type hygiene (`@Enumerated(EnumType.STRING)` stores identical values, zero behavior/security
  change) but ~60 touch points across the membership guard, invite redemption and scope/tenant lifecycle, all
  needing the full 4-platform E2E matrix to re-verify. Not worth the regression risk vs. Phase 5.
- [x] **P4.4 Base64 decode → 400 `INVALID_BLOB`** centrally. Added `MalformedInputMapper`
  (`ExceptionMapper<IllegalArgumentException>` in `platform/error`) → 400 `INVALID_BLOB`, logged at WARN.
  Safe because deliberate validation always throws `ApiException`; the only raw IAEs are bad base64 / enum
  values from untrusted input. Verified live: a malformed `verifier` on `/api/setup/owner` now returns 400.
- [x] **P4.5 `js-yaml` advisory** bump `^4.1.1` → `^4.2.0` (fixes GHSA-h67p-54hq-rp68, `npm audit` clean);
  `scripts/validate-feature-coverage.mjs` still runs green.

## Phase 5 — Tests / CI
- [x] **P5.1 Fix `HomeView.test.ts`.** Root cause: the test's `vi.mock('../../features/home/home')` omitted
  `resolveRoute`/`ancestorChain`, which the component gained for path-URL routing — so `restoreFromUrl()` called
  `undefined` and the grid never populated. Completed the mock (`resolveRoute → {folders:[],memory:null}`,
  `ancestorChain → []`). Both tests + the full 62-test unit suite green.
- [x] **P5.2 E2E in CI (web).** Added `scripts/run_web_e2e.py` — boots a fresh backend fast-jar per e2e file
  (each suite registers its own single-owner-per-boot owner; `jdbc:h2:mem` only resets on JVM restart) with
  rate-limiting off + mail mocked, runs that one file, tears it down, and aggregates pass/fail. Wired as the
  `web-e2e` CI job (build fast-jar → `npm ci` → orchestrator). Fixed 10 stale suites that omitted the now-
  required tenant `domain` (V21) → `POST /api/tenants` 400. Verified locally: all 28 web e2e files green
  (geo/seed intentionally skip on extra env gates).
  **Backlog:** Apple/Android e2e in CI need dedicated macOS-Xcode / Android-SDK+emulator runners against the
  live backend — a larger lift than the web path; tracked separately.

## Phase 6 — Operator-blind journal persistence (snapshot + encrypted increments)
Today the journal is a single sealed-markdown blob (`veyq_memory_journal`, `sealed_content`,
last-write-wins on PUT). The retired Hocuspocus sidecar held the authoritative doc and merged
non-overlapping sessions server-side — *in plaintext*. Restore that convergence **operator-blind**:

Design note: **hybrid** — the sealed Markdown body stays as the read projection every reader already uses
(detail view, public share — untouched); the CRDT (sealed Yjs snapshot + sealed increments) is added
alongside for editors. Increments anchor on the Yjs snapshot (not Markdown), so convergence works;
compaction writes both projections atomically, so they can't diverge.

- [x] **Schema (backend).** V24: added `sealed_snapshot` + `snapshot_suite_id` + `base_seq` + `next_seq` to
  `veyq_memory_journal` (Markdown `sealed_content` now nullable) and a new append-only
  `veyq_memory_journal_update(node_id, seq, sealed_update, author_id, created_at)`. Server stores ciphertext
  + seq + author-id + timestamps only (the "op-log offsets and author-ids" already server-visible).
- [x] **Endpoints (backend).** `GET .../journal/live` (snapshot + updates>baseSeq), `POST .../journal/updates`
  (append, server assigns monotonic seq under a row lock), `PUT .../journal/snapshot` (compaction: write
  snapshot + Markdown projection, prune updates ≤ coveredSeq). Old Markdown `PUT .../journal` removed — the
  compaction endpoint is the single writer. `GET .../journal` (Markdown) + public share read unchanged.
  Routed through `ScopeAccessGuard`. Slice test covers append→seq, live-state, compaction+prune, BAD_SEQ.
- [ ] **Client open:** load snapshot + updates(seq > baseSeq), decrypt, apply → current state (no live peer
  needed); track the max applied seq.
- [ ] **Client edit:** append a sealed Yjs increment (`POST .../journal/updates`) *and* broadcast live via the
  relay (unchanged).
- [ ] **Compaction:** after N increments / on close, write a new snapshot + Markdown projection
  (`PUT .../journal/snapshot`, coveredSeq = max applied seq).
**6a — member editor on the CRDT model** (web + e2e proving cross-session convergence, then iOS + Android).
Subsumes some of the P2.3 seed-race/cursor cleanup.
- [x] Web: `journalPersistence.ts` (load `journal/live` into a Y.Doc, append coalesced sealed increments,
  compact on close); the live editor + the plain draft save both go through it; the Markdown reader path
  (detail view) stays. e2e proves non-overlapping sessions converge via snapshot+increments and the Markdown
  projection follows. The removed Markdown PUT is fully off the web.
- [x] iOS: `JournalPersistence.swift` (load/append/compact + whole-doc save) + provider load/flush/compact;
  live editor + plain draft save route through it; Markdown read path stays. e2e proves convergence; app builds.
- [x] Android: `JournalPersistence.kt` (load/append/compact + whole-doc save) + provider load/flush/compact;
  Compose live editor + plain draft save route through it; Markdown read path stays. e2e proves convergence;
  :core + :app compile. **6a complete on all three platforms.**

**6b — live read in shares** (so readers/shares are always current, not just at compaction).
- [x] Relay `onOpen` also accepts a share token (`?share=`) gated by `canRead` + subtree; share connections
  are receive-only (`onMessage` drops their frames — journal editing stays member-only).
- [x] `GET /public/shares/{id}/nodes/{n}/journal/live` (sealed snapshot + update log). Slice test: VIEWER
  gets it (200), a node outside the subtree is 404.
- [x] Web share viewer reconstructs the journal from the public store (always current, not stale-at-compaction)
  and subscribes to the relay for live owner edits. share e2e proves the operator-blind guest reconstruct.
  *(Real-time push to a connected guest is wired but not e2e-covered; mobile share-live read = backlog, since
  share guests are browsers.)*

---

### Backlog (future, not scheduled)
- **Publish the Android Yrs binding as an AAR.** iOS already consumes a published library (YSwift,
  github.com/y-crdt/yswift) — nothing to publish there. Android uses our own `yniffi` (Rust yrs + uniffi,
  cross-compiled `.so` per ABI + generated Kotlin binding), currently committed into the repo
  (`android/app/.../jniLibs`, `android/core/.../uniffi/yniffi/yniffi.kt`, host `.dylib`). Packaging it as a
  `yrs-android` AAR (native libs + Kotlin binding + JNA) published to Maven Central / GitHub Packages would
  let the app depend on it normally (drop the committed binaries) and give the ecosystem a ready Kotlin Yrs
  binding (which it lacks — the reason we built one). Needs a publishing Gradle module + a cross-compile/
  publish CI job (the existing `scripts/build-yrs.sh` does the build). The operator-blind relay/persistence
  layer (JournalLiveProvider / JournalPersistence) is veyq-specific app logic, not a generic library.

### Dropped / refuted during verification
- Session GC "missing" → **exists** (`SessionGc.kt @Scheduled 1h`).
- Apple `randomBytes` "not a CSPRNG / catastrophic" → uses `SystemRandomNumberGenerator` (documented CSPRNG
  on Apple); `SecRandomCopyBytes` is merely the cleaner idiom → Low, optional in Phase 4.
- Reset sidecar (`infra/reset`) → correctly marked disposable test tooling; doc note only.
