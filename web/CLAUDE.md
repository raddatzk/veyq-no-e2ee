# web (Vue 3 + PrimeVue)

The web client. Loaded in addition to the root `CLAUDE.md` when working here. All content crypto runs
here; the server is blind.

## Prove your work
- `npm run check` (typecheck + test + build).
- API client is **generated**: `npm run generate:api` from `../contracts/api/*.openapi.yaml`. Never
  hand-write the client.

## Conventions
- **Reuse legacy UI, swap only the data layer — never invent new UI.** Port screens/components from
  `legacy/frontend` (read-only) and rewire them to the contracts client + crypto. If a ported view lacks
  a field the contract requires, add it **in-style** rather than hardcode a default.
- **Crypto:** `src/shared/crypto/v1.ts` is the **conformance-pinned** suite — keep it byte-identical to
  the vectors (`../contracts/crypto/vectors/v1.json`); the conformance test must stay green. The Model B
  auth derivation (`deriveAuthSalt` / `deriveAuthKeypair`, Ed25519 from Argon2id — ADR 0003) lives here.
- **Wire encoding:** opaque fields are **standard base64** (`b64std`), not base64url — the contract pins
  `[A-Za-z0-9+/]*={0,2}`, so base64url is rejected with 400 at the boundary.
- **Operator-blind:** seal/derive everything client-side; never send the password, plaintext or raw keys.
  See [security model](../docs/crosscutting/security-model.md) and
  [account & key model](../docs/crosscutting/account-key-model.md).

Stack: Vue 3, PrimeVue, vue-router. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
