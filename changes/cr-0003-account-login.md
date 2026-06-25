---
id: cr-0003
title: Account login (operator-blind password challenge-response)
status: planned          # planned → in-progress → done (then delete the file)
satisfies: [uc.account.login]
adrs: [0003]
invariants: [operator-blind, non-enumerable]
---

## Goal

Make **password login** work end-to-end against the operator-blind backend: the client derives the
Ed25519 **auth keypair** from the password, the server issues a single-use **nonce**, the client signs
it, the server verifies against the stored `authPublicKey` and issues a **session**. Scoped to the
**server-auth layer only** — no Master-Key unlock (separate use-case). Replaces the **login stub** the
web client carries since [cr-0002](cr-0002-web-setup.md). Native (apple/android) login is **out of
scope** here — those surfaces have no client yet; a later CR adds them.

## Context & constraints

- Use-case: [uc.account.login](../docs/features/account/use-cases/uc.account.login.md) (server-auth only;
  auth ≠ unlock).
- Concepts: [Account & Key Model → Auth vs. unlock](../docs/crosscutting/account-key-model.md),
  [Security Model](../docs/crosscutting/security-model.md) (operator-blind, non-enumerability),
  [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) (password-derived keypair +
  challenge-response).
- Web UI source (reuse, read-only): `legacy/frontend` login view + app shell/router; the current `web/`
  login route is a **stub** to replace. Reuse UI, swap only the data layer (frontend rule).
- Crypto already present (web): `deriveAuthSalt` + `deriveAuthKeypair` were added in cr-0002 (crypto v1).
- **Invariants at risk:**
  - **operator-blind** — the password never reaches the server; the server handles only the public key,
    a nonce, and a signature — never a verifier or credential.
  - **non-enumerable** — challenge issuance and failures must be **identical** for existing vs.
    non-existing accounts (deterministic `authSalt`, always issue a nonce, generic failure).
- **Carried-forward decision to resolve — `instanceSalt`.** cr-0002 deferred ADR 0003's
  `authSalt = HKDF(instanceSalt, normalizedEmail)` to `HKDF(normalizedEmail)` because nothing distributes
  `instanceSalt`. **This CR owns that reconciliation** (see Open decision). The setup owner minted in
  cr-0002 was write-only for auth (login stubbed), so changing the salt source is non-breaking on a fresh
  instance.

## Open decision (settle in phase 1)

Adopt the full ADR formula `authSalt = HKDF(instanceSalt, normalizedEmail)` and **distribute
`instanceSalt`** as instance-wide public config (same value for everyone → not an enumeration vector).
Two placements:
- **A (recommended):** return `instanceSalt` in the **challenge response** alongside the nonce — the
  client needs it right before deriving the keypair to sign, so one round-trip carries both.
- **B:** expose it on a public instance-config/bootstrap endpoint fetched at boot.

Recommendation: **A** — fewest moving parts, no extra endpoint, salt arrives exactly when needed.

## Phases

- [ ] **1 — contracts.** Define the two login operations and the session shape in `contracts/`:
  `POST /api/login/challenge { email } → { nonce, instanceSalt }` (always 200, non-enumerable) and
  `POST /api/login/verify { email, signature } → 200 { session } | 401 generic`. Settle the Open
  decision (instanceSalt placement) and the session token form. **Done when:** the spec lints and the
  backend/web clients generate from it.
- [ ] **2 — backend.** Implement nonce issuance (single-use, time-bounded, server-stored), Ed25519
  signature verification against the stored `authPublicKey`, session issuance, and `instanceSalt`
  exposure; adopt `authSalt = HKDF(instanceSalt, normalizedEmail)`. Slice tests: valid login → session;
  wrong password → generic 401; unknown account → byte-identical response to wrong password; expired /
  replayed nonce → rejected. **Done when:** `./gradlew check` green.
- [ ] **3 — http.** Add `http/account/login.http` with response-handler assertions for what the surface
  can drive without an Ed25519 signer: challenge issuance is **non-enumerable** (known vs. unknown email
  → identical), replayed/expired nonce → rejected, malformed requests → 400. **Parity note:** the full
  signed happy-path round-trip is **not** expressible in a `.http` handler (no Ed25519 signing) — it is
  covered by the backend slice (phase 2) and the web e2e (phase 4); mark this gap explicitly.
  **Done when:** the ijhttp run is green.
- [ ] **4 — web.** Replace the login stub with the real flow: derive the auth keypair, request the
  challenge, sign the nonce, verify, store the session. Post-auth lands in an **authenticated-but-locked**
  state (Master-Key unlock is a separate use-case — stub the unlock step). **Done when:** `npm run check`
  green and a preview smoke shows web → backend login happy path **and** wrong-password → generic failure.
- [ ] **5 — verify + coverage.** Add `docs/coverage/features/account/use-cases/uc.account.login.yaml`
  mapping each acceptance criterion to the tests above (with explicit `gaps` for the http signed-path and
  for apple/android), and add the `uc.account.login` row to
  [platform-parity.md](../docs/crosscutting/platform-parity.md) (backend/http/web ✅, apple/android ◻
  planned). **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

A web user logs in against the operator-blind backend via challenge-response and receives a session,
with **non-enumerability** and **replay-resistance** verified by tests; the web login stub is gone;
coverage for `uc.account.login` is green and the parity matrix reflects it. Master-Key unlock and native
login remain separate, planned work. After merge, delete this file.
