---
id: cr-0001
title: First-run server setup (API-first)
status: planned          # planned → in-progress → done (then delete the file)
satisfies: [uc.admin.setup-server]
adrs: [0001, 0002, 0003]
invariants: [operator-blind]
---

## Goal

Make a fresh instance bootstrappable: a Server Owner can be created as an end-to-end-encrypted account
and the instance marked set up — proven **operator-blind at the API level**. This CR is **API-first**:
`contracts → backend → http`. The web/apple/android setup UIs and the real client-side crypto (the
concrete contents of suite `v1` + conformance vectors) are **deferred to follow-up CRs** — they are the
first real client/crypto slice. The backend does **no content crypto** here: it stores ciphertext and
the auth public key, nothing more.

## Context & constraints

Honour these (links, not copies):
- Use-case: [uc.admin.setup-server](../docs/features/admin/use-cases/uc.admin.setup-server.md)
- Concepts: [Security Model](../docs/crosscutting/security-model.md) (operator-blind, what's
  server-visible), [Account & Key Model](../docs/crosscutting/account-key-model.md) (cold key birth,
  Master Key wraps, auth keypair — [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md)),
  [Scopes & Tenancy](../docs/crosscutting/scopes-and-tenancy.md)
- Conventions: [Developer Styleguide](../docs/crosscutting/developer-styleguide.md) — resource-shaped
  adapter + use-case handler ([ADR 0001](../docs/decisions/0001-backend-http-adapter-vs-application-slicing.md)),
  API-first from `contracts/`, portable Flyway (H2∩MariaDB), validation at the trust boundary.
- Invariant at risk: **operator-blind** — the server must store only ciphertext + server-visible
  operational metadata (displayName, instanceName, authPublicKey, suiteId, sizes/IDs). No content crypto,
  no key material in cleartext, no secrets in errors.
- No personal scope is created here ([ADR 0002](../docs/decisions/0002-personal-scope-per-tenant.md)).

## Phases

- [ ] **1 — contracts.** `contracts/api/setup.openapi.yaml`:
  - `GET /api/setup` → `SetupStatus { setupRequired: bool, suites: [string] }`.
  - `POST /api/setup/owner` → `CreateServerOwnerRequest { displayName, instanceName, suiteId,
    identityPublicKey, wrappedIdentityPrivateKey, masterKeyWraps: [{method, wrappedKey}], authPublicKey,
    recoveryMasterKeyTag? }`. All key fields are **opaque base64 strings**; `authPublicKey` is the Ed25519
    auth public key (server-visible, **not** a credential); `authSalt` is derived deterministically from
    the email (ADR 0003), so it is not in the payload. Declare field constraints in the schema (`maxLength`, `pattern`,
    `required`); `suiteId` is an enum incl. `"v1"` (its byte contents are out of scope here).
  - Responses: `200/201` created; `409` when already set up; `400` on invalid input.
  - **Done when:** the spec validates as OpenAPI 3.1 (standalone check). Codegen wiring and the actually
    generated interfaces/models are confirmed in phase 2, where the build exists.

- [ ] **2 — backend.** Stand up the Quarkus/Kotlin module (Gradle wrapper, Flyway, OpenAPI codegen wired
  per the styleguide); Flyway migration for the owner + setup-lock tables (H2∩MariaDB common subset);
  `SetupResource` (implements the generated interface, thin) + `SetupHandler` (store ciphertext +
  `authPublicKey`, enforce the **single-owner lock** — **no content crypto, no signature check**; the
  login challenge-response is a later CR). Slice test on a fresh in-memory DB: positive (setup flips `setupRequired`) +
  negatives (second setup → `409`; blank `displayName` / malformed → `400`).
  - **Done when:** `cd backend && ./gradlew check` is green.

- [ ] **3 — http.** `http/admin/setup.http` — runnable with response-handler **assertions**, using
  **fixture ciphertext** (the blind server only stores blobs): `GET /api/setup` (`setupRequired==true`)
  → `POST /api/setup/owner` (`200/201`) → `GET /api/setup` (`setupRequired==false`) → second `POST`
  (`409`); a blank `displayName` request (`400`).
  - **Done when:** the `.http` run passes against a fresh instance.

## Definition of done

`uc.admin.setup-server` is provable operator-blind at the API level across the **backend** and **http**
surfaces; all three phases green. Deferred to follow-up CRs: web/apple/android setup UIs and the real
client crypto (suite `v1` contents + conformance vectors). After merge, delete this file — the durable
trace is git history + the use-case (+ its coverage once the validator lands).
