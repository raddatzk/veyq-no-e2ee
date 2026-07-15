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

The audit asks to extract a shared `_common.openapi.yaml` and `$ref` it. The blocker is **per-toolchain
external-`$ref` support**, established empirically:

| Consumer | Resolves cross-file `$ref`? | Note |
|---|---|---|
| web (openapi-generator typescript-fetch) | **yes** | swagger-parser resolves relative refs in place |
| backend (openapi-generator 7.21, Quarkus) | **yes** | reads specs in place |
| android (openapi-generator kotlin) | **no, as wired** | `sanitizeSpecs` copies specs into a *flat isolated* build dir |
| apple (swift-openapi-generator 1.5) | **no, ever** | generator throws on any external ref — source-verified¹ |
| http (`.http` files) | n/a | no code generation |

¹ `swift-openapi-generator/.../TypeAssigner.swift`: *"External JSON references are not supported"*;
`OperationDescription.swift:63`: *"Swift OpenAPI Generator currently doesn't support external references."*

So a naive `$ref` extraction **breaks apple and android**. apple is unfixable at the generator level (and SPM's
build-tool plugin gives no hook to run a bundler before it), so apple would have to consume a **pre-bundled,
self-contained spec**; and since `swift test` must pass standalone (no manual pre-step), that bundled spec would
have to be **committed** — a generated artifact in the tree. A bundler is the only way to physically DRY the YAML,
and it costs a reversal of a documented invariant plus committed generated specs.

The duplication it would remove, however, is ~50 lines of **stable** boilerplate: the `ErrorResponse` schema is
already byte-identical across all 9 specs (only the per-domain `code.example` varies), `bearerAuth` differs only
in `description`, and the shared enums are duplicated-not-drifted. The actual risk is **future** drift, not present
disorder. That risk is fully addressed by a check — which needs none of the machinery.

## Decision

**Keep the specs self-contained; add a drift guard instead of a bundler.** Do not extract `_common.openapi.yaml`,
do not introduce external `$ref`, do not touch any generator toolchain.

1. **The invariant stays.** Each `contracts/api/*.openapi.yaml` remains a single self-contained document, exactly
   as the toolchains require and the docs promise. Zero risk to web/backend/apple/android generation.
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
- **Rejected — the bundler (this ADR's original proposal).** `_common.openapi.yaml` + external `$ref` + a Python
  bundler + committed apple bundles would give a true single source file, but at the cost of reversing a
  documented invariant and checking generated YAML into the tree, to DRY boilerplate that does not actually drift
  today. If the shared surface grows large or genuinely starts drifting under the guard, revisit this — the guard
  makes that signal visible.
