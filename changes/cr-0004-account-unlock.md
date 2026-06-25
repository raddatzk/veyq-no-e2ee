---
id: cr-0004
title: Account unlock (PASSWORD wrap — open the Master Key client-side)
status: done             # planned → in-progress → done (then delete the file)
satisfies: [uc.account.unlock]
adrs: [0003, 0005]
invariants: [operator-blind]
---

## Goal

Take the **authenticated-but-locked** state cr-0003 leaves the web client in and make **unlock** work
end-to-end against the operator-blind backend: the authenticated client fetches its stored **PASSWORD
`MasterKeyWrap`** (+ KDF params) and **`wrappedIdentityPrivateKey`**, derives the **Unlock KEK** =
`Argon2id(secretKey + password, params)` entirely on-device, AEAD-unwraps the **Master Key**, then unwraps
the **identity private key** — both held **in memory only**, the KEK discarded. The server serves only
ciphertext and learns nothing. Scoped to the **PASSWORD** method only — passkey / recovery / trusted-device
wraps each open the *same* Master Key via their own wrap and are **separate, planned use-cases**. Native
(apple/android) unlock is **out of scope** (no client yet).

## Context & constraints

- Use-case: [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) (PASSWORD floor
  path; auth ≠ unlock; ephemeral by default — no DEVICE wrap / persistence here).
- Concepts: [Account & Key Model](../docs/crosscutting/account-key-model.md) (one Master Key / many wraps,
  the Unlock KEK, 2SKD), [Security Model](../docs/crosscutting/security-model.md) (Secret Key never reaches
  the server, operator-blind).
- Decisions: [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) (the auth-vs-unlock split),
  [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md) (PASSWORD is the floor of the
  wrap-method spectrum; re-lock & precedence).
- **Already in place (no change needed):**
  - Backend stores, per account at setup: the PASSWORD wrap (`wrappedKey` + `kdfParams`), a RECOVERY wrap,
    `wrappedIdentityPrivateKey`, `identityPublicKey`, `recoveryMasterKeyTag`
    (`backend/.../shared/identity/MasterKeyWrapEntity.kt`, `VeyqUser`, `V1__setup.sql` +
    `V3__add_wrap_kdf_params.sql`).
  - Secret Key is born client-side at setup and shown only in the **Emergency Kit** PDF — never sent to the
    server (`web/.../setupServer/setupServer.ts`, `web/.../emergencyKit/emergencyKit.ts`).
  - Web crypto primitives exist and are conformance-pinned: `kekPasswordSecret`, `symUnwrap`,
    `decodeKdfParams`, `deserializeIdentityPrivate` (`web/src/shared/crypto/v1.ts`). **No new crypto** — this
    CR only *composes* them. The wrap was written by setup as
    `symWrap(kekPasswordSecret(secretKey, password, salt), masterKey, 'master-key')` and the identity bundle
    as `symWrap(masterKey, identityPriv, 'identity-priv')`; unlock **must** mirror both **byte-for-byte**
    (same arg order, same context strings, params read from the wrap's `kdfParams`).
- **Missing (= this CR builds it):** an authenticated endpoint to fetch the wraps; a backend
  **access-token → user** guard (this is the *first* authenticated read — `logout` only reads the bearer to
  revoke, it never resolves a user); a web **unlock orchestrator + UI**; coverage.
- **Invariants at risk:**
  - **operator-blind** — the endpoint serves only ciphertext + the suite id; every derivation/unwrap is
    client-side; the password and Secret Key never leave the device; the KEK never persists.
  - **no enumeration / no cross-account read** — the endpoint is authenticated and returns **only the
    caller's own** material, keyed off the access token, never an email or id in the request.
  - **auth ≠ unlock holds under token theft** — a stolen access token yields only **ciphertext** from this
    endpoint; without the password **and** Secret Key (2SKD) it opens nothing. The endpoint therefore does
    not widen the attack surface beyond what login already gates.
- **Verified de-risked before planning (no work needed):** `suiteId` is already a column on `veyq_user`
  (so the endpoint returns it with **no migration**); the `bearerAuth` security scheme already exists in
  `contracts/api/auth.openapi.yaml` (logout uses it); the generated web client auto-attaches the bearer via
  `authConfig()` (`accessToken: () => session.accessToken`); and the **401→refresh retry** helper
  `withRefresh()` already lives in `web/.../login/login.ts` — cr-0004 is its **first real consumer**.

## Design decisions (settled)

- **Endpoint = one authenticated GET returning all of the caller's key material.**
  `GET /api/account/key-material` → `{ wraps: [{ method, wrappedKey, kdfParams }], wrappedIdentityPrivateKey,
  suiteId }`. Returns **all** wraps (not just PASSWORD) so passkey/recovery/device methods reuse the same
  endpoint later; this CR's client just selects the PASSWORD wrap. Reuse the existing `MasterKeyWrap` schema
  (promote it to a shared component if a cross-file `$ref` is awkward). `identityPublicKey` /
  `recoveryMasterKeyTag` are **not** returned here — the AEAD unwrap of the identity bundle already
  authenticates the Master Key, so they're unnecessary for the PASSWORD floor (add in-style if a later
  method needs them).
- **Backend auth guard is real, reusable infra.** Add `SessionService.resolveUserId(accessToken): String?`
  — hash the bearer, look up `AccessTokenEntity`, reject if missing / expired / its family is not `ACTIVE`,
  else return `family.userId`. The new resource reads `Authorization: Bearer …` exactly like
  `SessionResource.logout`. Missing/invalid/expired token → **401**. Every future authenticated endpoint
  reuses this.
- **Password is carried from a just-completed login (transient, memory-only).** Per the user's call: the
  login flow hands the freshly-entered password to the unlock step so the unlock card is pre-filled — no
  second password entry right after login. The plaintext password lives **only** in an in-memory store, is
  **cleared the moment the Unlock KEK is derived** (and on sign-out / reload), and is **never** persisted or
  sent. A fresh app session (after reload) currently falls back to re-login — the remembered-session boot
  path that would re-prompt for the password without a full login is not built yet (boot.ts is trimmed), so
  the UC's "re-prompted on a fresh app session" branch is satisfied today by simply logging in again.
- **Secret Key entry = paste only.** A text field to paste the Secret Key from the Emergency Kit. QR-scan
  and `veyqkit:` file import are **deferred** to their own use-cases.
- **Unlocked state lives in a new memory-only store, NOT `session.ts`.** `session.ts` deliberately holds
  only session tokens ("Auth ≠ unlock — these gate a session, never the Master Key"). Add a separate
  `vault`/`unlock` store holding the Master Key + identity private key in memory; cleared on sign-out and
  not persisted across reloads (ephemeral by default, per the UC).

## Phases

- [x] **1 — contracts.** Added self-contained `contracts/api/account.openapi.yaml`:
  `GET /api/account/key-material` (`bearerAuth`; generic `401` on missing/invalid/expired/revoked token) →
  `AccountKeyMaterial { suiteId, wraps: [MasterKeyWrap], wrappedIdentityPrivateKey }`. `MasterKeyWrap` +
  `ErrorResponse` duplicated identically (self-contained-spec pattern; backend overwrites to one model
  harmlessly). Wired the web `generate:api` third pass (→ `src/api/generated/account`); backend
  auto-discovers the spec. **Done:** both clients generate (`AccountApi.getAccountKeyMaterial`), web
  typecheck green, backend `compileKotlin` green.
- [x] **2 — backend.** Added `SessionService.resolveUserId(accessToken)` (hash lookup; reject missing /
  expired / non-ACTIVE family) — the reusable authenticated-read guard — and an `account/keymaterial`
  slice (`AccountResource` implements the generated `AccountApi`; `KeyMaterialStore` reads `VeyqUser` +
  its wraps) serving the caller's wraps + `wrappedIdentityPrivateKey` + stored `suiteId` (no migration).
  6 slice tests green: valid token → caller's PASSWORD+RECOVERY wraps + identity blob; **A's token returns
  only A's material, never B's**; missing / malformed / expired token → generic 401; revoked family → 401.
  **Done:** `./gradlew check` green.
- [x] **3 — http.** Added `http/account/unlock.http` (3 assertions, **ijhttp green** against a live
  instance): `GET /api/account/key-material` with **no** bearer, a **bogus** bearer, and a **malformed**
  Authorization header → all a single generic 401 `authentication_failed` (no-anonymous-read + bad-bearer
  boundaries). **Parity gap (slice-only, in the file header):** the authenticated happy path (200 with the
  caller's wraps) needs a valid access token, which requires a signed login no `.http` handler can produce
  (same limit as cr-0003) — covered by the phase-2 slice + phase-4 web test.
- [x] **4 — web.** Added `unlockMasterKey(password, secretKey)` (`features/account/unlock`): fetches
  `key-material` **through `withRefresh()`** (its first real consumer), picks the PASSWORD wrap,
  `decodeKdfParams` → `kekPasswordSecret` → `symUnwrap(…, 'master-key')` → `symUnwrap(masterKey, …,
  'identity-priv')` → `deserializeIdentityPrivate`, stores Master Key + identity key in a new **memory-only
  vault** (`app/vault.ts`, raw bytes kept out of Vue's reactive proxy; only an `unlocked` flag is reactive),
  and clears the carried password + Secret Key the moment the KEK is derived. `LoginView.vue` now shows an
  **Unlock** card after sign-in (password reused from login, Secret-Key paste, "Unlocking…" busy state); E1
  wrong password/Secret Key and E2 no-PASSWORD-wrap both surface one **generic** failure. Also completed the
  long-intended `authPrefill` wiring so first-run setup pre-fills the Secret Key (paste path unchanged for
  returning users). 4 web unit tests (setup-shaped round-trip unlock; wrong password / wrong Secret Key /
  no-PASSWORD-wrap each → generic failure, no key held). **Done:** `npm run check` green (40 tests). Preview
  smoke on a fresh quarkusDev: setup → login → **Unlock** card → wrong Secret Key gave the generic failure
  (vault stayed locked) → correct password+Secret Key reached the **Unlocked** state with
  `vault.unlocked = true` and the password + Secret Key cleared to length 0.
- [x] **5 — verify + coverage.** Filled in
  `docs/coverage/features/account/use-cases/uc.account.unlock.yaml` (6 acceptance criteria → the web
  round-trip + backend slice + http boundaries; `gaps` for apple/android, with a note that the authed
  happy-path isn't http-expressible), and added the `uc.account.unlock` row to
  [platform-parity.md](../docs/crosscutting/platform-parity.md) (backend/http/web ✅, apple/android ◻).
  **Done:** `python3 scripts/validate_coverage.py` green (3 use-cases mapped, all criteria covered).

## Definition of done

A web user who has just logged in can **unlock** with their password + pasted Secret Key: the client fetches
only ciphertext, derives the Unlock KEK on-device, opens the Master Key and identity private key into a
memory-only store, and discards the KEK and password — verified by tests, with a **generic failure** on a
wrong password or Secret Key (server uninvolved) and **no cross-account read** at the endpoint. The locked
card is replaced by a working unlock flow; coverage for `uc.account.unlock` is green and the parity matrix
reflects it (backend/http/web). Passkey / recovery / trusted-device unlock, content decryption (no content
feature yet), and native unlock remain separate, planned work. After merge, delete this file.

## Review follow-ups (post-merge of phases 1–5)

Security review of the cr-0004 diff surfaced three fixes, applied on top:
- **#1 (bug):** `withRefresh` matched `instanceof ResponseError` from the `generated/auth` package, but the
  account client throws its OWN `ResponseError` class — so the 401→refresh-retry was dead for the unlock
  fetch (its first real consumer). Switched to a package-agnostic duck-typed 401 check
  (`name === 'ResponseError' && response.status === 401`); regression test drives a real `account`
  `ResponseError` through `withRefresh`.
- **#2 (secret hygiene):** the pasted/pre-filled Secret Key lingered in the reactive ref on failure paths.
  Now a setup-provided Secret Key is parked off the reactive ref until login succeeds, and a failed unlock
  clears the Secret-Key ref (the password is kept — no field to re-enter it).
- **#4 (altitude):** re-lock (`clearVault`) was bolted onto the view's `signOut`. Moved into the `logout()`
  primitive so every sign-out path drops the unlocked keys; test covers it.

Second batch (robustness/cleanup), all applied:
- **#3:** `AccountResource` maps suite/method by enum **value** (not `Enum.valueOf` by name): unknown wrap
  methods are skipped (forward-compatible), an unknown suite is a controlled 500 — no uncaught exception.
- **#5:** shared `com.veyq.app.shared.http.bearerToken(headers)` extractor; `AccountResource` and
  `SessionResource.logout` both route through it (kills the `takeIf` vs `isNullOrEmpty` drift).
- **#6:** added `KeyMaterialHandler` (Resource → Handler → Store per ADR 0001); the resource is now a thin
  adapter.
- **#7:** `clearVault()` zero-fills the Master Key + identity-key `Uint8Array`s before dropping the refs.
- **#8:** `client.ts` shares one `bearerParams()` source across all authed clients.
- **#10:** `KeyMaterialHandler.forAccessToken` is `@Transactional`, so the token resolve + material read
  share **one** transaction.

- **#9:** moved both Argon2id derivations (login keypair, unlock KEK) off the main thread into a Web
  Worker (`shared/crypto/argon.worker.ts` + `argonWorker.ts` async wrappers). `v1.ts` is untouched and the
  worker runs the SAME pinned suite; a synchronous fallback keeps non-Worker environments (vitest/node)
  byte-identical, so the conformance + unit tests still drive v1 directly. Verified: 44 web tests green
  (incl. fallback-equivalence), a separate `argon.worker` build chunk, and a preview smoke confirming the
  real worker loads (`argon.worker.ts?worker_file`) and carries login + unlock end-to-end with no console
  errors.

All ten review findings are now resolved.

A second review pass (over the fix commits themselves) found three more, all applied:
- **R2-#1:** a Web Worker `error` was not fatal — a dead/failed worker was reused, so a later call could
  hang forever with no fallback. The worker is now retired on error (`workerUnavailable`) and each
  outstanding request finishes on the **synchronous fallback** (a per-request `fallback()` thunk); a
  sync `postMessage` failure falls back too. Test drives a dying worker → sync result, byte-identical.
- **R2-#2:** `refreshSession` now `clearTokens()` on a rejected refresh, so a dead refresh token lands the
  session in a clean logged-out state instead of looping 401→refresh→throw on every authed call. Tested.
- **R2-#3:** the unlock failure path no longer wipes the Secret-Key field — a transient (network/5xx) error
  must not throw away a correct, hard-to-re-paste key; the field is the user's active input. Inputs are
  still cleared on success and on sign-out.

The two remaining latent items from the second pass, also applied:
- **R2-#4:** an unrecognised master-key-wrap method is now WARN-logged when skipped (was a silent
  `mapNotNull` drop), so a corrupt/critical wrap can be told apart from a forward-compatible new method.
  Slice test seeds a `FUTURE_METHOD` wrap and asserts it's skipped (200, PASSWORD preserved, no 500).
- **R2-#5:** the vault getters (`masterKey()` / `identityPrivate()`) now return a fresh **copy**, so the
  in-place zero-fill in `clearVault()` can never corrupt a reference a caller still holds (use-after-clear).
  Test holds a copy across `clearVault()` and asserts it still has the real bytes.
