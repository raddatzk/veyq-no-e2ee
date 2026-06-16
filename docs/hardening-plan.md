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

## Phase 1 — Security-critical (backend)
- [ ] **P1.1 Session-token hashing.** `Session.kt:21` stores the bearer token in plaintext and looks it up
  raw → a DB dump grants live API access until expiry. Store `SHA-256(token)`, look up by hash, return the
  raw token only to the client. Migration.
- [ ] **P1.2 `ScopeAccessGuard` + close the membership gap.** Most content handlers also require
  `memberships.find(tenantId, userId)?.status == "ACTIVE"` (e.g. `ScopeContentKeyHandler.kt:67`), but
  `ScopeGrantRewrapHandler.kt:35/47` and `rotateidentity/MyGrantsHandler` (both return the **wrapped scope
  private key**) and `JournalRelay.onOpen` gate on the grant alone. Extract one guard and route all through
  it (also fixes the copy-paste drift M1).
- [ ] **P1.3 Rate limits.** No throttle on `LoginHandler:46`, password-reset request, invite-claim, or
  public-share upload. Add a shared (IP+user) limiter; covers the D2 interim mitigation.

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

- [ ] **Schema:** keep `veyq_memory_journal` as the **snapshot** (sealed Yjs state instead of markdown) and
  add an append-only `veyq_memory_journal_update(node_id, seq /*server-monotonic*/, sealed_update, author_id,
  created_at)`. Server stores only ciphertext + seq + author-id + timestamps (matches the
  "op-log offsets and update author-ids" already listed as server-visible in security-model.md).
- [ ] **Client open:** load snapshot + increments with `seq > base_seq`, decrypt, apply → current state
  (works with no live peer).
- [ ] **Client edit:** append a sealed Yjs increment (`POST .../updates`) *and* broadcast live via the relay.
- [ ] **Compaction:** after N increments / on close, a client writes a new snapshot
  (`encodeStateAsUpdate`) and the server prunes increments ≤ the new seq.
- [ ] Touches backend (schema + journal endpoints) + all three clients' journal load/save + the three live
  providers. Sequence **after Phase 1**, ideally **before P2.3** (it subsumes some of the seed-race/cursor
  cleanup). This is "the missing half" of operator-blind live journaling that the sidecar used to cover.

---

### Dropped / refuted during verification
- Session GC "missing" → **exists** (`SessionGc.kt @Scheduled 1h`).
- Apple `randomBytes` "not a CSPRNG / catastrophic" → uses `SystemRandomNumberGenerator` (documented CSPRNG
  on Apple); `SecRandomCopyBytes` is merely the cleaner idiom → Low, optional in Phase 4.
- Reset sidecar (`infra/reset`) → correctly marked disposable test tooling; doc note only.
