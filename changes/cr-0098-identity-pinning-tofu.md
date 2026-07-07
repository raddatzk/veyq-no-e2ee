---
id: cr-0098
title: Identity pinning — the sealed pin-store + TOFU at the wrap sites (detect, notice/accept, worker decline)
status: done
satisfies: [uc.spaces.verify-identity]
adrs: ["0028", "0009", "0021", "0020"]
invariants: [operator-blind-e2ee]
---

## Goal

The **foundation** of [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md)'s authenticity
model: give clients an **operator-blind, cross-device memory** of each contact's identity key
([en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md)) and enforce
**trust-on-first-use** at the three wrap-to-foreign-identity sites (rotation re-grant, the grant-delivery worker,
the invite name-key). First-use **pins + proceeds**; a **matching** key proceeds; a **changed** key is never
silently trusted — the UI surfaces a **notice** (accept → re-pin) and the human-less **grant-delivery worker blocks
+ declines the job**. **No new crypto primitive** — the pin-store is `symWrap(masterKey, blob, "identity-pins")`
(a new context, vector-pinned). The **QR-compare verification ceremony, the `verified` state, the account-global
strict mode, and the directory badges** are a **follow-up CR** — this one lands the store + detection + the honest
change-responses.

## Context & constraints

- Durable truth: [uc.spaces.verify-identity](../docs/features/spaces/use-cases/uc.spaces.verify-identity.md) (the
  criteria this CR fills: `tofu-pin-on-first-use`, `change-surfaced-not-trusted`, `operator-blind-pinning`),
  [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md) (the sealed
  store), [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) (the model),
  [ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md) (vector-pin the seal context),
  [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md) (grant-delivery — the decline seam),
  [ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md) (the grant wrap this gates).
- Invariant at risk: **operator-blind.** The pin store is **one ciphertext** sealed under the Master Key; the
  server stores/relays it verbatim, never a `userId`, a key, or the count. New server state: the sealed blob + a
  version, and a **grant-delivery decline** status (a worker refused a job) — operational metadata, no trust graph.
- Builds on [en.account.key-model](../docs/features/account/enablers/en.account.key-model.md) (the Master Key that
  seals the blob) + the existing account-sealed-material sync pattern (vault / master-key wraps).
- **Scope boundary (honest):** TOFU-pin + change-**detection** + the **default** responses only. A changed key in
  the UI is a notice + a minimal **accept → re-pin** (no QR compare yet); the human-less worker blocks + declines.
  The **QR verification, the `verified` elevation, the strict mode, and badge polish** are the follow-up CR — until
  it ships, an *un-accepted* change is a dead-end for that contact (rare; pre-production).

## Phases

- [x] 1 contracts — crypto: pin the pin-store seal `symWrap(masterKey, blob, "identity-pins")` as a **conformance
  vector** (native byte-identical, ADR 0009 — even though native parity is deferred). API: a per-account
  **identity-pins** sealed blob — `GET /api/account/identity-pins` → `{ blob, version }` and `PUT` `{ blob,
  version }` with **optimistic concurrency** (a stale `version` → `409 stale_version`); the **grant-delivery
  decline** — a worker marks a claimed job **declined** (with a reason) so it is not re-offered **to that worker**.
  · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [x] 2 backend — persist the sealed pin-store blob **per account** (a new row: account → `sealed_blob` + `version`;
  `version` bumped on `PUT`, `409` on mismatch; born empty). The **grant-delivery decline**: release the claim +
  record `(worker, job)` declined so the reconciler does **not** re-offer it to that worker — the job **stays
  available to other workers** (per-device, not a global veto, ADR 0028 § Consequences). Slice tests: blob
  round-trips + the optimistic-concurrency `409`; a declined job is not re-offered to the same worker **but is** to
  another. · Done when: `./gradlew test` green.
- [x] 3 http — the `identity-pins` `GET`/`PUT` (incl. the `409 stale_version`) + the grant-delivery decline
  boundaries with response-handler assertions. · Done when: the `.http` assertions pass against a seeded instance.
- [x] 4 web — the **pin-store data layer** (open/unseal under the Master Key; version-guarded `PUT` with a
  client-side **union-merge** on `409`); a **`checkAndPin(userId, key) → firstUse | match | changed`** helper; wire
  it into the **three wrap sites** — rotation re-grant (`scopes.ts`), the grant-delivery worker
  (`grantDeliveryWorker.ts`), the invite name-key (`inviteName.ts` / `scopes.ts`): first-use **pins + proceeds**,
  match proceeds, **changed** → the **worker blocks + declines the job**, the **UI paths surface a notice + accept
  → re-pin** (minimal; the QR ceremony is the follow-up CR). Unit tests: pin round-trip + merge-on-409; the
  first-use / match / changed branches at a wrap site; the worker declines (does not wrap) on a changed key.
  · Done when: `npm run check` green.

## Definition of done

Clients **pin** each contact's identity key on first wrap — operator-blind: one Master-Key-sealed blob, synced,
**no trust graph on the server**; a later wrap to a **matching** key proceeds; a **changed** key is never silently
trusted — the UI surfaces a notice (accept → re-pin) and the human-less grant-delivery worker **blocks + declines
the job** (per-device, not a global veto). Fills `uc.spaces.verify-identity`'s `tofu-pin-on-first-use` /
`change-surfaced-not-trusted` / `operator-blind-pinning` criteria (the use-case stays `status: planned` — the
remaining criteria are the follow-up CR). Guards green (`openApiGenerateSpecs`, `./gradlew test`, `npm run check`,
coverage/changes/doc-link validators). **Out of scope (follow-up CR):** the QR-compare **verification** ceremony,
the **`verified`** state, the account-global **strict mode**, the directory **badges**; and (own refine)
identity + master-key **rotation** / compromise-recovery.

## Retrospective

Landed the TOFU foundation of ADR 0028 across all four phases, guards green.

- **Phase 1 — contracts.** New tag `account-identity-pins` → `GET/PUT /api/account/identity-pins` (`{ blob, version }`,
  `409 stale_version`); `POST /api/tasks/{jobId}/decline` (`DeclineRequest`) on the existing `tasks` tag. Crypto:
  `sym_identity_pins` vector (context `identity-pins`, kek = the Master Key, born-empty plaintext) — computed with
  Python AES-256-GCM and **cross-checked byte-identical against the existing `sym_master_key` vector** before
  committing, then wired into all three conformance suites (web `it()`, android + apple key lists). `openApiGenerateSpecs`
  + web `generate:api` clean; the web conformance suite is green (24 vectors incl. the new one).
- **Phase 2 — backend.** `veyq_identity_pins` (single row per account, `version BIGINT`) + `veyq_job_decline`
  (surrogate id + unique `(job, worker)`). The version-guarded PUT is an **atomic compare-and-set** (`where accountId
  = ? and version = ?`), not read-then-write — no `@Version` (matches house style). The decline releases the lease
  back to PENDING, **rolls back the claim's attempt increment** (`attempts - 1`, so declines never dead-letter a job),
  and the exclusion is one `not in (select … from JobDeclineEntity where workerId = ?)` line in the claim candidate
  query → per-device, not a global veto. `./gradlew test` green (incl. the born-empty→409 round-trip and the
  two-worker decline-then-still-claimable-by-B slice tests). **Bug caught in test:** the pin-store slice first used
  fixed userIds and failed on the JVM-lived shared H2 DB (a written pin row bled across tests) — fixed by seeding a
  UUID-unique account per test, mirroring `TasksSliceTest`.
- **Phase 3 — http.** `identity-pins.http` (self-seed → born-empty → PUT v0 → 409 stale v0 → PUT v1) + decline
  boundaries appended to `tasks.http`. Ran both `ijhttp` suites against a **freshly-wiped** MariaDB instance (each
  file self-seeds the single owner, so the DB volume was removed between the two runs): identity-pins 10/10, tasks
  15/15, RUN SUCCESSFUL.
- **Phase 4 — web.** `identityPins.ts`: seal/unseal under the Master Key, version-guarded PUT with a **union-merge
  on 409** (re-read → re-apply the additive change → retry), `checkAndPin → firstUse | match | changed`,
  `acceptKeyChange` (re-pin), `IdentityChangedError`. Wired the three foreign-wrap sites: the **human-less
  grant-delivery worker declines** the job on a changed key; **rotation re-grant excludes** a changed member (keeps
  its `number` return so the two existing callers/tests are untouched — the rotation still proceeds, the excluded
  member stays awaiting-access); the **invite name-key** throws `IdentityChangedError` → `GroupMembersPanel` surfaces
  a notice + an accept→re-pin→retry button (en+de). Real-crypto unit tests for the round-trip, the three branches at
  each site, the worker decline, and the union-merge; `npm run check` green (465 tests).

**Scope held.** Exactly the store + detection + honest change-responses shipped. The QR safety-number ceremony, the
`verified` elevation, the account-global strict mode, the directory badges, and identity/master-key rotation are the
follow-up CR — `uc.spaces.verify-identity` deliberately stays `status: planned` (3 of 6 criteria filled).

**Honest gaps (as designed).** The first-use gap (a key substituted before first pin is invisible until manual
verify/KT) stands. Rotation's changed-member exclusion is silent on web today (no per-member banner — the member is
visibly awaiting-access, the ADR-0028 anomaly); the visible rotation notice rides the follow-up. The concurrent
*first* write of a born-empty store loses the second insert to the PK constraint rather than a clean 409 (benign,
pre-production; the loser retries and takes the normal merge path) — documented in `IdentityPinsStore.put`.
