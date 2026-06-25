---
id: cr-0002
title: Web setup flow (reuse legacy UI on the E2EE data layer)
status: in-progress      # planned → in-progress → done (then delete the file)
satisfies: [uc.admin.setup-server]
adrs: [0001, 0003]
invariants: [operator-blind]
---

## Goal

Bring up the first **web client** and make the first-run **setup flow** work end-to-end against the
operator-blind backend — **reusing the legacy UI/UX** (`legacy/frontend`) and changing only the data
layer (operator-blind, Model B auth, `contracts/`-generated client). Login UI is a **stub** here; real
login needs a backend login CR. Decisions taken: adopt the **legacy crypto suite v1**; add **`email`**
to setup (login identifier + Model B `authSalt` source).

## Context & constraints
- Use-case: [uc.admin.setup-server](../docs/features/admin/use-cases/uc.admin.setup-server.md)
- UI source (reuse, read-only): `legacy/frontend/src/views/SetupView.vue`, app shell/router/boot,
  `src/features/admin/setupServer`, `src/features/account/emergencyKit`.
- Crypto (adopt): `legacy/frontend/src/shared/crypto/v1.ts` + conformance vectors.
- Concepts: [Account & Key Model](../docs/crosscutting/account-key-model.md),
  [ADR 0003](../docs/decisions/0003-operator-blind-password-auth.md) (Model B), Developer Styleguide.
- Invariant at risk: **operator-blind** — content crypto stays client-side; the server only gains a
  server-visible `email` (low-sensitivity identity metadata) plus the existing ciphertext + `authPublicKey`.
- **Reuse UI, swap data layer; never invent new UI** (frontend rule). The content crypto comes from
  legacy `v1.ts` 1:1; only the **auth** part becomes Model B (Ed25519 auth keypair instead of Model A
  password-to-server).

## Phases

- [x] **1 — contracts + backend (email).** Add `email` (validated, server-visible) to
  `CreateServerOwnerRequest`; V2 Flyway migration adds a unique `email` column to `veyq_user`; handler
  stores it. Update slice/sanitization tests + `http/admin/setup.http`. **Done when:** `./gradlew check`
  green and the ijhttp run green.

- [x] **2 — crypto suite v1 adoption.** Port `legacy/frontend/src/shared/crypto/v1.ts` into the web
  client and bring its conformance vectors into `contracts/crypto/vectors/v1.json`; port the conformance
  test. **Done when:** the web conformance test passes against the vectors.

- [ ] **3 — web setup flow.** Scaffold `web/` (Vite + Vue 3 + PrimeVue), generate the TS client from
  `contracts/`, port `SetupView` + app shell + router + boot, and adapt the setup data layer to **Model
  B + our contract** (email, `authPublicKey`; reuse legacy content-crypto). Login route is a stub.
  **Done when:** `npm run check` green.

- [ ] **4 — verify.** The setup flow runs web → backend on a fresh instance (Playwright e2e or a
  preview-driven smoke). **Done when:** the setup happy path is observed creating the owner via the API.

## Definition of done

A fresh instance can be set up from the **web UI** (reused from legacy) on the operator-blind data layer
(Model B), with the crypto suite v1 adopted and conformance-pinned. Login UI deferred. After merge,
delete this file.
