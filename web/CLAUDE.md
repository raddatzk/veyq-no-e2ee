# web (Vue 3 + PrimeVue)

The web client. Loaded in addition to the root `CLAUDE.md` when working here. All content crypto runs
here; the server is blind.

## Prove your work
- `npm run check` (typecheck + test + build).
- API client is **generated**: `npm run generate:api` from `../contracts/api/*.openapi.yaml`. Never
  hand-write the client.
- **Run the Memory shell locally (cr-0030):** `npm run dev` (Vite proxies `/api` → `127.0.0.1:8080` with
  `changeOrigin`, so the backend sees `Host: 127.0.0.1`, port stripped). The shell needs the signed-in user to
  belong to a tenant whose **`domain` is `127.0.0.1`** (the active tenant is resolved from the Host) — create
  one with that domain, or seed it in dev. Then login → unlock auto-births the personal scope and the browse
  renders. If no tenant claims the Host you get the *“no space”* state (expected).

## Conventions
- **Reuse legacy UI, swap only the data layer — never invent new UI.** Port screens/components from
  `legacy/frontend` (read-only) and rewire them to the contracts client + crypto. If a ported view lacks
  a field the contract requires, add it **in-style** rather than hardcode a default. Visual rules +
  tokens/classes: [web UI styleguide](../docs/crosscutting/ui-web-styleguide.md).
- **Crypto:** `src/shared/crypto/v1.ts` is the **conformance-pinned** suite — keep it byte-identical to
  the vectors (`../contracts/crypto/vectors/v1.json`); the conformance test must stay green. The Model B
  auth derivation (`deriveAuthSalt` / `deriveAuthKeypair`, Ed25519 from Argon2id — ADR 0003) lives here.
- **Wire encoding:** opaque fields are **standard base64** (`b64std`), not base64url — the contract pins
  `[A-Za-z0-9+/]*={0,2}`, so base64url is rejected with 400 at the boundary.
- **Operator-blind:** seal/derive everything client-side; never send the password, plaintext or raw keys.
  See [security model](../docs/crosscutting/security-model.md) and
  [account & key model](../docs/crosscutting/account-key-model.md).

Stack: Vue 3, PrimeVue, vue-router. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
