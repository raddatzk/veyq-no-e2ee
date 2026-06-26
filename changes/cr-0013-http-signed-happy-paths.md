---
id: cr-0013
title: Signed happy-paths on the http/ surface — Ed25519 in pre-request scripts (close the "no signature" gaps)
status: done             # planned → in-progress → done (then keep as a record)
satisfies: []            # no new use-case — completes the http verification surface for login/unlock/trust-device/create-tenant
adrs: [0003, 0004]       # exercises the operator-blind challenge-response auth + session lifecycle over the wire
invariants: []
---

## Goal

The `http/` ijhttp suites stop at **pre-auth boundaries** today: every file carries a "NOT covered here — the
HTTP client cannot produce an Ed25519 signature" caveat, so the authenticated happy-paths (signed login → token →
authed calls) are left to the backend slice tests and the web round-trip. The IntelliJ/ijhttp client **can**
import JavaScript and run it in request handlers — so it *can* sign. This CR vendors a tiny Ed25519 lib and adds
**separate `*-authed.http` files** that drive the full signed journey on a fresh instance, closing the gap and
removing the caveat. The existing boundary files stay **untouched** (zero preconditions, runnable against any
instance) — the authed files are additive and document their one precondition (a fresh DB to self-seed the owner).

This is a **parity / coverage** win, not a bug fix: the signed path is already exercised over real HTTP by
`LoginSliceTest` etc. What it adds is the **language- and client-independent** contract surface (`http/` at
parity per [developer-styleguide](../docs/crosscutting/developer-styleguide.md) §11) actually covering the
authed contract, not just its rejections.

## Context & constraints

- **The signature scheme is simple and already pinned by the backend** ([ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)):
  `/login/challenge` returns `nonce = base64(32 random bytes)`; the client signs the **raw decoded nonce bytes**
  with its Ed25519 auth key and sends `signature = base64(sig)` to `/login/verify`. `instanceSalt` is **not** part
  of the signed message (it is only the client-side password KDF salt). Verified by
  `Ed25519Verifier` over the raw 32-byte public key. `LoginSliceTest` drives the identical scheme in Kotlin.
- **Setup persists the real key.** `POST /api/setup/owner` stores the request's `authPublicKey` verbatim
  ([SetupStore](../backend/src/main/kotlin/com/veyq/app/features/admin/setup/SetupStore.kt)), so an http file can
  seed an owner whose private key it controls — the only way to obtain a valid session over the wire. Because
  setup is single-owner-locked, a seeding file is inherently **fresh-instance-only** (a documented precondition,
  exactly like `login.http`/`setup.http` today).
- **The other key-material fields are operator-blind ciphertext** — the server never interprets them, so the seed
  request uses valid-shaped dummy base64 for `identityPublicKey` / `wrappedIdentityPrivateKey` / the PASSWORD wrap,
  and only `authPublicKey` is a real key derived from the fixed test seed.
- **Vendored lib, fixed test seed.** `http/_lib/` holds tweetnacl@1.0.3 + tweetnacl-util@0.15.1 (UMD) and an ESM
  facade `ed25519.js` (each UMD wrapped in an IIFE with a local `module`, exporting `publicKeyB64` /
  `signNonceB64`), built by `build_ed25519.py`. The seed is a **non-secret test key**, clearly labelled — it
  never represents a real account.
- **Boundary files unchanged** (the user's call): `login.http` / `unlock.http` / `trust-device.http` /
  `tenant.http` keep their zero-precondition, any-instance boundary checks; this CR only *removes their "cannot
  sign" caveat text* and points it at the new authed files.
- **Grouping:** one authed file per area, grouped by shared session journey (a single fresh instance + one login
  yields the token reused across the area's endpoints) — not one per use-case, to avoid re-seeding three times.

## Phases

- [x] **1 — vendor the lib + ESM facade (spike-proven).** `http/_lib/{nacl-fast.min.js, nacl-util.min.js}`
  (pinned tweetnacl@1.0.3 / tweetnacl-util@0.15.1), `ed25519.js` (ESM facade: `publicKeyB64(seedB64)`,
  `signNonceB64(seedB64, nonceB64)`), and `build_ed25519.py` (regenerates the facade). **Done:** a throwaway
  spike `.http` imported the facade under **ijhttp** and asserted `sign`+`verify` round-trips and correct
  key/sig lengths → **RUN SUCCESSFUL**. This de-risks the one unknown (does the ijhttp CLI execute imported JS);
  the backend-acceptance half is already guaranteed by the identical scheme in `LoginSliceTest`. Spike removed.
- [x] **2 — `http/account/authed-flow.http` (login · unlock · trust-device, signed).** One fresh-instance
  journey: seed owner via `/setup/owner` with `authPublicKey = publicKeyB64(SEED)` → `/login/challenge` (capture
  nonce) → pre-request `signNonceB64(SEED, nonce)` → `/login/verify` **200** (capture access + refresh) →
  `GET /account/key-material` **200** returning the seeded wraps + `wrappedIdentityPrivateKey` (the unlock read) →
  `POST /account/wraps` add a DEVICE wrap **201** → key-material now lists DEVICE → `DELETE` it **204** →
  `/token/refresh` rotation **200** → replay the old refresh → family revoke (reuse detection) → rotated token
  now **401** (blast radius) → `logout` **204**. **Done:** `ijhttp … account/authed-flow.http` **11/11** green
  against a wiped dev MariaDB. Confirmed the whole mechanism: pre-request `import` runs under the ijhttp CLI,
  cross-request `client.global` variable passing, and the signature is accepted by the real backend.
- [x] **3 — `http/admin/tenant-authed.http` (create-tenant, signed owner).** Seed owner → signed login → the
  Server-Owner CRUD `tenant.http` defers today: create **201** PENDING → list shows it (ACTIVE invite) → resend
  (still one ACTIVE) → reassign (email rebound to `second@example.com`) → revoke **204** → resend now **404**
  `tenant_not_found`. **Done:** `ijhttp … admin/tenant-authed.http` **9/9** green against a wiped dev instance
  (the seeded owner has `SERVER_OWNER`, so it clears the `@RolesAllowed` gate).
- [x] **4 — cleanup + docs.** Replaced the "cannot produce an Ed25519 signature / NOT covered here" caveats in
  all four boundary files (`login` / `unlock` / `trust-device` / `tenant.http`) with a pointer to the authed file
  that now covers the happy-path (the non-owner 403 in `tenant.http` stays slice-covered — no http file can seed
  a *member* yet). Added [http/_lib/README.md](../http/_lib/README.md) (vendored versions + source +
  `build_ed25519.py` + the non-secret test-seed note). Added a
  [developer-styleguide](../docs/crosscutting/developer-styleguide.md) §11 bullet ("`http/` covers signed
  happy-paths, not just boundaries"). Updated the four coverage files: added `http: e2e` to the now-covered
  criteria (signed login / refresh-rotate / reuse-revoke / ciphertext-read / device add+remove / tenant CRUD +
  unknown-404) and corrected each stale "not expressible on http" note. **Done:** `scripts/validate_coverage.py`
  green (5 use-cases, all criteria covered); no boundary file still claims the signed path is uncoverable.

## Definition of done

`http/` covers the authenticated happy-paths end-to-end with a **real** Ed25519 signature produced in-client:
signed login + session rotation + reuse-revoke + logout, the key-material unlock read, the trust-device wrap
round-trip, and the Server-Owner tenant CRUD — each green under ijhttp against a fresh instance. The boundary
files keep their zero-precondition checks and no longer claim the signed path is uncoverable. The vendored lib +
builder + fixed test seed are documented. Keep this file as a record after merge.
