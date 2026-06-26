# http/_lib — client-side crypto for the ijhttp suites

Vendored JavaScript the IntelliJ HTTP Client / `ijhttp` imports in request handlers to produce a **real
Ed25519 signature** in-client, so the `*-authed.http` suites can drive the signed login happy-path
(challenge → sign the nonce → verify → session) instead of deferring it to the backend slice tests (cr-0013).

## Files

| File | What | Source |
|---|---|---|
| `nacl-fast.min.js` | tweetnacl **1.0.3** (Ed25519 sign/verify; bundles its own SHA-512) | npm `tweetnacl@1.0.3` |
| `nacl-util.min.js` | tweetnacl-util **0.15.1** (base64 ↔ bytes) | npm `tweetnacl-util@0.15.1` |
| `ed25519.js` | **generated** ESM facade over the two UMD libs | `build_ed25519.py` |
| `build_ed25519.py` | regenerates `ed25519.js` (run after bumping a vendored lib) | — |

The two `nacl-*.min.js` are UMD (no ESM named exports), which the HTTP client's `import` can't consume
directly. `build_ed25519.py` wraps each in an IIFE with a local `module` (steering the UMD into its
CommonJS branch) and re-exports the helpers as a single ESM module.

## API (`ed25519.js`)

```js
import { publicKeyB64, signNonceB64 } from '../_lib/ed25519.js';

publicKeyB64(seedB64)            // raw 32-byte Ed25519 public key (base64) — what setup stores as authPublicKey
signNonceB64(seedB64, nonceB64)  // base64 Ed25519 signature over the raw bytes of a base64 nonce — for /login/verify
```

Matches the backend exactly ([ADR 0003](../../docs/decisions/0003-operator-blind-password-auth.md)): the
signed message is the **raw decoded nonce bytes** (`instanceSalt` is not part of it).

## Test seed

The `*-authed.http` files use a fixed seed of **32 zero bytes**
(`"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="`). It is a **non-secret test key** — it only ever signs a
throwaway owner self-seeded into a fresh instance, and never represents a real account. The public key
derived from it is sent to `/api/setup/owner` as `authPublicKey`; all other key-material fields in the seed
are operator-blind dummy ciphertext the server never interprets.

## Usage

`*-authed.http` files require a **fresh** instance (they self-seed the owner; setup is single-owner-locked):

```
docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d
# start the backend, then:
ijhttp --env-file http/account/http-client.env.json --env dev http/account/authed-flow.http
ijhttp --env-file http/admin/http-client.env.json   --env dev http/admin/tenant-authed.http
```

The boundary files (`login.http`, `unlock.http`, `trust-device.http`, `tenant.http`) need none of this —
they carry no precondition and run against any instance.
