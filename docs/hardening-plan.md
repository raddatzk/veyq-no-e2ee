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
- [ ] **P2.1 Android raw-JSON → `JSONObject`.** `Login.kt:32`, `SetupServer.kt:49`, `Tenant.kt:36`,
  `RegisterViaInvitation.kt:85` interpolate free text (password/name) into JSON — `"`/`\`/newline corrupt
  or inject the body. Web/iOS escape correctly; the newer Android slices already use `JSONObject`.
- [ ] **P2.2 Argon2 8 → 64 MiB.** `Argon2Verifier.kt:35` + the `m=8192` literals in web/iOS/Android
  setup/register/reset → `m=65536` (the suite default; the invite path already uses 65536). Params travel
  in `kdfParams`, so per-account upgradable; conformance vectors stay valid.
- [ ] **P2.3 Live-journal bugs.** Seed race that can duplicate the initial text (web seeds on `onSynced`
  *and* an 800ms timer; iOS/Android seed on a timer with no "who seeds" election) — gate on lowest
  clientId / completed sync. Cursor unit mismatch (awareness reports UTF-16, diffs use char-array/UTF-16
  then UTF-8 byte offsets → wrong caret past emoji, possible op corruption) — pick codepoints end-to-end.
  iOS `handleStep2(payload) {}` reads `currentText` before the (ignored) completion → read inside the
  closure.
- [ ] **P2.4 Live-journal GCM nonce.** Fixed scope `contentKey` + random 96-bit nonce per relayed frame →
  birthday bound under heavy co-editing. Derive a per-session sub-key
  `HKDF(contentKey, "veyq/v1/journal", sessionRandom)` (or XChaCha20).

## Phase 3 — Performance (backend)
- [ ] **P3.1 Gallery / share-media N+1.** `MediaHandler.kt:49` `toApi`/`blobRef` does +1 blob +1 derivative
  per medium (~1200 queries / 200 photos); same in `PublicShareMediaHandler`. Batch via `listByMediaIds`
  + `IN` queries.
- [ ] **P3.2 Blob: O(n²) chunk sum + IO inside the transaction.** `ensureBudget` re-lists all chunks per
  PUT (`BlobUploadHandler.kt:189`) — keep a byte counter on the upload row. Move blob IO out of
  `@Transactional`; add a streaming `InputStream` instead of `readAllBytes` + base64-String.
- [ ] **P3.3 Share-chunk ACL.** `PublicShareMediaHandler.kt:60` rebuilds the whole subtree blob set on every
  chunk fetch — resolve once / cache per share.
- [ ] **P3.4 Indexes + tenant purge.** Add `veyq_blob_upload(scope_id,status)` and restore
  `veyq_media(scope_id)`; make `purgeDueTenants` one transaction per tenant.

## Phase 4 — Maintainability / cleanup
- [ ] **P4.0 Finish the `ScopeAccessGuard` migration (M1).** Phase 1 created the guard and routed the three
  gap sites through it; migrate the remaining ~9 content handlers' identical inline `requireKeyHolder`
  (ScopeContentKeyHandler, JournalHandler, TrackHandler, MemoryTreeHandler, CurateHandler, MediaHandler,
  ShareHandler, ScopeItemsHandler, BlobUploadHandler) onto it so the check can't drift again.
- [ ] **P4.1 Account/KeyBag factory.** Dedup `SetupServerHandler.kt:62` ↔ `RegisterViaInvitationHandler.kt:90`.
- [ ] **P4.2 Shared helpers.** Centralize `toWrap`/`requireSuite`/Base64/`unauthorized()`; fix the hardcoded
  `if (suiteId != "v1")` in `BlobUploadHandler.kt:97` (ignores `CryptoSuiteRegistry` → latent v2 bug).
- [ ] **P4.3 String → typed enums** for roles/status/scope-kind in entities (`Tenancy.kt:21`).
- [ ] **P4.4 Base64 decode → 400 `INVALID_BLOB`** centrally instead of a generic 500.
- [ ] **P4.5 `js-yaml` advisory** bump in `scripts/validate-feature-coverage.mjs` (dev-only).

## Phase 5 — Tests / CI
- [ ] **P5.1 Fix `HomeView.test.ts`.** `.memory-name`/`.folder-card` exist in `HomeView.vue:361/372`, so the
  failure is a rendering/setup break — diagnose, then fix.
- [ ] **P5.2 E2E in CI.** Run the `VEYQ_E2E=1` web/Apple/Android paths against a started backend stack.

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
- [ ] iOS + Android: same (mechanical port, identical wire format).

**6b — live read in shares** (so readers/shares are always current, not just at compaction).
- [ ] Relay `onOpen` also accepts a share token gated by `canRead` + subtree; share connections are
  receive-only (no broadcast — journal editing stays member-only).
- [ ] `GET /public/shares/{id}/nodes/{n}/journal/live` (snapshot + updates) so a guest reconstructs the
  current state client-side (Yjs), then subscribes to the relay for live deltas. Operator-blind throughout.

---

### Dropped / refuted during verification
- Session GC "missing" → **exists** (`SessionGc.kt @Scheduled 1h`).
- Apple `randomBytes` "not a CSPRNG / catastrophic" → uses `SystemRandomNumberGenerator` (documented CSPRNG
  on Apple); `SecRandomCopyBytes` is merely the cleaner idiom → Low, optional in Phase 4.
- Reset sidecar (`infra/reset`) → correctly marked disposable test tooling; doc note only.
