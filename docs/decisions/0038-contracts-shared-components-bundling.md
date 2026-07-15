# ADR 0038 — Shared contract components stay inline, kept honest by a drift guard (not a bundler)

- Status: Accepted
- Date: 2026-07-12

> Realises audit finding **#76 (P3f)**. **Keeps** the "specs are self-contained (no external `$ref`)" invariant
> (`backend/gradle/openapi.gradle.kts`, `contracts/api/account.openapi.yaml`) and adds a guard instead of a
> bundler — see *Decision*.

## Context

`ErrorResponse` is defined in all **9** `contracts/api/*.openapi.yaml` (structurally byte-identical — only the
`code.example` value varies); `bearerAuth` in **8**; the same logical enums repeat across specs
(`NodeKind` [FOLDER, MEMORY, CHAPTER] ×2, derivative-kind [THUMBNAIL, PREVIEW] ×3, credential-method ×3,
membership-role [OWNER, MEMBER] ×2, suite-version [v1] ×3); `ScopeId`/`TenantId` path-params are redeclared.
Today this duplication is a **documented, deliberate** invariant — the specs are independent single documents so
each generator target consumes exactly one self-contained file, and colliding component names across specs are
why they can't share a generator module.

The audit asks to extract a shared `_common.openapi.yaml` and `$ref` it. **Both consumers resolve cross-file
`$ref` natively**, established empirically:

| Consumer | Resolves cross-file `$ref`? | Note |
|---|---|---|
| web (openapi-generator typescript-fetch) | **yes** | swagger-parser resolves relative refs in place |
| backend (openapi-generator 7.21, Quarkus) | **yes** | reads specs in place |

**This decision originally rested on a blocker that no longer exists.** It was taken when a generator that
could not resolve external references at all was a consumer: extraction would have needed a bundler plus a
committed, generated spec in the tree, and *that* cost — not the merit of `$ref` — is what defeated it. With
only web and backend left, extraction needs no bundler, no committed artifact, and no reversal of a toolchain
requirement. The "self-contained spec" invariant is now a **convention we keep, not a constraint we are
under**, and this ADR's conclusion stands on its second argument alone:

The duplication `$ref` would remove is ~50 lines of **stable** boilerplate: the `ErrorResponse` schema is
already byte-identical across all 9 specs (only the per-domain `code.example` varies), `bearerAuth` differs only
in `description`, and the shared enums are duplicated-not-drifted. The actual risk is **future** drift, not present
disorder. That risk is fully addressed by a check — which needs none of the machinery. That argument is real,
but it is a *judgement about size*, not an impossibility — so the honest status is **weaker than it reads**, and
the revisit trigger below is now the live one.

## Decision

**Keep the specs self-contained; add a drift guard instead of a bundler.** Do not extract `_common.openapi.yaml`,
do not introduce external `$ref`, do not touch any generator toolchain.

1. **The invariant stays** — now as a convention, not a toolchain requirement (see Context). Each
   `contracts/api/*.openapi.yaml` remains a single self-contained document. Zero risk to generation.
2. **A trust-chain guard (`scripts/validate_contracts_common.py`) enforces consistency** where the components are
   copied: `ErrorResponse` identical across specs (ignoring the per-domain `code.example`), `bearerAuth`
   `type`+`scheme` identical (ignoring `description`), the `ScopeId`/`TenantId` path-params structurally identical,
   and every copy of a shared enum (node-kind / derivative-kind / membership-role / credential-method /
   suite-version) carrying the same values — a diverged copy fails unless it is a declared intentional variant
   (e.g. the device-only `[DEVICE, DEVICE_KEY]` subset). It runs in CI beside the other `validate_*` guards.
3. **Collection-wrapping is left alone** — the bare-array / `{results}` / `{items}` / domain-key mix encodes an
   intentional batch-vs-list distinction, so it is a design choice, not drift; the guard does not police it.

## Consequences

- **The single-source *guarantee* without the single-source *file*.** The shared components can no longer drift
  silently — the guard is the enforcement a bundler's `$ref` would have given — while every generator keeps
  consuming exactly what it does today. This is the "Start light / pain before tool" trade: a ~120-line check
  beats new build machinery for ~50 lines of stable boilerplate.
- **On-disk duplication remains.** A reviewer still sees `ErrorResponse` in nine files; editing it means editing
  nine (the guard then fails until they agree). Accepted: the components change ~never, and the guard makes a
  half-done edit loud instead of silent.
- **Rejected — extraction to `_common.openapi.yaml` + external `$ref`** (this ADR's original proposal). It would
  give a true single source file. It was rejected when it also required a bundler and committed generated specs;
  **it no longer does** — both consumers resolve refs in place, so the cost is now just the extraction itself.
  What remains against it is only proportion: ~50 lines of boilerplate that does not drift, already guarded.
  **Revisit when the shared surface grows, when the guard actually catches a drift, or when `contracts/` is next
  reworked** — the ground under this rejection moved, and it should not be re-affirmed by inertia.
