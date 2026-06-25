---
id: cr-0003
title: Account login (operator-blind password challenge-response)
status: in-progress      # planned → in-progress → done (then delete the file)
satisfies: [uc.account.login]
adrs: [0003, 0004]
invariants: [operator-blind, non-enumerable]
---

## Goal

Make **password login** work end-to-end against the operator-blind backend: the client derives the
Ed25519 **auth keypair** from the password, the server issues a single-use **nonce**, the client signs
it, the server verifies against the stored `authPublicKey` and issues a **session** — opaque, rotating
**access + refresh** tokens in a named **session family** per [ADR 0004](../docs/decisions/0004-session-tokens.md).
Scoped to the **server-auth layer only** — no Master-Key unlock (separate use-case). Replaces the **login stub** the
web client carries since [cr-0002](cr-0002-web-setup.md), and **closes cr-0002's `instanceSalt`
deferral** — the auth keypair is derived with `instanceSalt` at **both** registration and login. Native
(apple/android) login is **out of scope** here — those surfaces have no client yet; a later CR adds them.

## Context & constraints

- Use-case: [uc.account.login](../docs/features/account/use-cases/uc.account.login.md) (server-auth only;
  auth ≠ unlock).
- Concepts: [Account & Key Model → Auth vs. unlock](../docs/crosscutting/account-key-model.md),
  [Security Model](../docs/crosscutting/security-model.md) (operator-blind, non-enumerability),
  [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) (password-derived keypair +
  challenge-response), [ADR 0004](../docs/decisions/0004-session-tokens.md) (opaque rotating
  access + refresh tokens, named session families, reuse-detection).
- Web UI source (reuse, read-only): `legacy/frontend` login view + app shell/router; the current `web/`
  login route is a **stub** to replace. Reuse UI, swap only the data layer (frontend rule).
- Crypto already present (web): `deriveAuthSalt` + `deriveAuthKeypair` were added in cr-0002 (crypto v1).
- **Invariants at risk:**
  - **operator-blind** — the password never reaches the server; the server handles only the public key,
    a nonce, and a signature — never a verifier or credential.
  - **non-enumerable** — challenge issuance and failures must be **identical** for existing vs.
    non-existing accounts (deterministic `authSalt`, always issue a nonce, generic failure).
- **Carried-forward decision to resolve — `instanceSalt`.** `instanceSalt` is **baked into the
  registered `authPublicKey`**: `authPub = Ed25519(Argon2id(password, HKDF(instanceSalt, email)))`. So it
  is consumed at **registration** (setup/join) *and* at **login**, and **both must use the same value** or
  the derived keypairs diverge and no signature verifies. cr-0002 dropped it (registered the owner with
  `authSalt = HKDF(email)`); **this CR owns the reconciliation on both ends** — see Open decision.
  - **`instanceSalt` is an instance-lifetime constant:** the server generates it **once at first-run
    setup**, persists it, and exposes it as instance-wide public config (same for everyone → not an
    enumeration vector; not secret — it is a salt). Changing it later invalidates **every** account's auth
    keypair, so it is fixed for the life of the instance.
  - **Breaking for the cr-0002 owner:** that owner's stored key was derived **without** `instanceSalt`;
    adopting the ADR formula means their login would fail (key mismatch). Acceptable only because this is
    greenfield/dev with no production accounts — the fix is to re-run setup on a fresh instance. Not a
    silent migration: call it out.

## Design decision — `instanceSalt` placement (settled)

Adopt the full ADR formula `authSalt = HKDF(instanceSalt, normalizedEmail)`. Since **registration also
needs `instanceSalt`** (not just login), it must live where *both* the setup client and the login client
read it — a single persisted server value exposed at two read points:
- **Setup/registration:** add `instanceSalt` to the `SetupStatus` response (`GET /api/setup`) — the setup
  client already calls it at boot, before deriving the owner's auth keypair.
- **Login:** return the same `instanceSalt` in the **challenge response** — the login client calls
  challenge before signing, so it needs no dependency on the setup endpoint.

This is **not** the originally-scoped "login distributes the salt" — it forces a **setup-side change**
too (generate + persist + expose `instanceSalt`, and derive the owner's `authSalt` with it). Folded into
phases 1–2 below.

## Phases

- [x] **1 — contracts.** Add `instanceSalt` to the `SetupStatus` schema (`GET /api/setup`) and define the
  auth + session operations per [ADR 0004](../docs/decisions/0004-session-tokens.md):
  `POST /api/login/challenge { email } → { nonce, instanceSalt }` (always 200, non-enumerable);
  `POST /api/login/verify { email, signature } → 200 { accessToken, refreshToken, family: { id } } | 401
  generic`; `POST /api/token/refresh { refreshToken } → 200 { accessToken, refreshToken } | 401`; and
  `POST /api/logout` (revoke the current family). The family is **nameless** here — the E2EE session name
  is unlock-gated and belongs to `uc.account.sessions`, not this CR. **Done when:** the spec lints and the
  backend/web clients generate from it.
- [x] **2 — backend.** *Setup side:* generate `instanceSalt` once at first-run, persist it (migration),
  and return it from `GET /api/setup`; the existing owner-create path already stores the client-derived
  `authPublicKey`, so no owner-side change beyond the salt now flowing. *Login side:* nonce issuance
  (single-use, time-bounded, server-stored), Ed25519 signature verification against the stored
  `authPublicKey`. Adopt `authSalt = HKDF(instanceSalt, normalizedEmail)` consistently. *Session side
  (ADR 0004):* on verify, create a **nameless session family** (opaque id) and issue opaque **access +
  refresh** tokens (stored as hashes); `refresh` **rotates** the
  pair and bumps the generation; presenting a **superseded** refresh token whose secret verifies ⇒
  **revoke the family** (reuse detection); `logout` revokes the family. Slice tests: valid login → family
  + tokens; wrong password → generic 401; unknown account → byte-identical to wrong password; expired /
  replayed nonce → rejected; refresh rotates + invalidates the old token; **replayed (superseded) refresh
  → family revoked**; an unverifiable stale token → plain 401, **not** a family-kill (DoS guard);
  `GET /api/setup` carries a stable `instanceSalt`. **Done when:** `./gradlew check` green.
- [x] **3 — http.** Added `http/account/login.http` (11 assertions, **ijhttp green** against a live
  instance): challenge **non-enumerability** (existing vs. unknown email → identical 200 shape), boundary
  400s (missing/malformed email, non-base64 signature, missing refresh token), a generic 401 on a wrong
  signature, the **DoS-safe** unknown-refresh → plain 401, idempotent logout, and a stable `instanceSalt`.
  **Parity gap (slice-only):** every path that needs a valid Ed25519 signature — the signed happy-path
  login, refresh **rotation success**, and **reuse → family-revoke** — cannot be driven from a `.http`
  handler (no signer), so they are covered only by the backend slice (phase 2). Marked explicitly in the
  file header and recorded as a coverage gap. *(Correction: the positive token flows are NOT
  http-expressible after all — obtaining a valid refresh token requires a signed session.)*
- [ ] **4 — web.** *Setup side:* correct the cr-0002 stopgap — derive `authSalt` from the `instanceSalt`
  now in `SetupStatus` (`HKDF(instanceSalt, email)`), so setup and login derive **identically**. *Login
  side:* replace the login stub with the real flow — derive the auth keypair, request the challenge, sign
  the nonce, verify, store the **access + refresh** tokens, and wire **retry-on-`401`→refresh**. (The
  E2EE session name is unlock-gated → `uc.account.sessions`, not here.) Post-auth lands in an
  **authenticated-but-locked** state — Master-Key unlock is
  [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md), stubbed here.
  **Done when:** `npm run check` green
  and a preview smoke shows, on a fresh instance, setup → login round-trip (the same email/password logs
  in), a silent access-token refresh, **and** wrong-password → generic failure.
- [ ] **5 — verify + coverage.** Add `docs/coverage/features/account/use-cases/uc.account.login.yaml`
  mapping each acceptance criterion to the tests above (with explicit `gaps` for the http signed-path and
  for apple/android), and add the `uc.account.login` row to
  [platform-parity.md](../docs/crosscutting/platform-parity.md) (backend/http/web ✅, apple/android ◻
  planned). **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

A web user logs in against the operator-blind backend via challenge-response and receives an opaque
**access + refresh** session in a (nameless) **family**, with **non-enumerability**, **replay-resistance**,
**refresh rotation** and **reuse-detection** (superseded refresh → family revoked) verified by tests; the
web login stub is gone; coverage for `uc.account.login` is green and the parity matrix reflects it.
Master-Key unlock, the **E2EE-named** session-management UI (`uc.account.sessions`), and native login
remain separate, planned work. After merge, delete this file.
