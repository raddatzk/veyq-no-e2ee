# ADR 0038 — Shared contract components via `_common.openapi.yaml` + a bundling step

- Status: Proposed
- Date: 2026-07-12

> Realises audit finding **#76 (P3f)**. Reverses the "specs are self-contained (no external `$ref`)" note in
> `backend/gradle/openapi.gradle.kts` and `contracts/api/account.openapi.yaml`. The behaviour is carried by the
> CR that implements this ADR; until then each spec keeps its inline copies.

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
build-tool plugin gives no hook to run a bundler before it), so apple must consume a **pre-bundled, self-contained
spec**. Since `swift test` must pass standalone (no manual pre-step), that bundled spec must be **committed**.

## Decision

Introduce `contracts/api/_common.openapi.yaml` as the single source for the shared components, have the per-domain
specs reference it by external `$ref`, and add a **Python bundler + drift guard** so the two generators that can't
resolve external refs consume a self-contained artifact.

1. **`_common.openapi.yaml`** holds `ErrorResponse`, the `bearerAuth` scheme, common parameters (`ScopeId`,
   `TenantId`), and the shared enums. Domain-specific nuance that legitimately varies (e.g. each `code.example`,
   the per-domain `bearerAuth.description`) stays on the **referencing** side, not in `_common`.
2. **Source specs (`contracts/api/*.openapi.yaml`) `$ref` `_common`** for those components. They remain the
   hand-authored edit point. This is the invariant reversal.
3. **Consumption per toolchain:**
   - **web, backend** — unchanged; they resolve `./_common.openapi.yaml#/...` in place.
   - **android** — `sanitizeSpecs` also copies `_common.openapi.yaml` into the flat dir; refs are written
     `./_common.openapi.yaml` so they resolve as a sibling there.
   - **apple** — a Python bundler (`contracts/scripts/bundle_openapi.py`) inlines `_common` into each of the 6
     apple domains and writes **committed** self-contained specs the apple targets read (the current per-target
     symlinks are replaced by these generated files). apple/android only cover 6 domains
     (setup/auth/account/scopes/content/media), so only those bundle.
4. **Drift guard** (trust-chain, Python): re-run the bundler and fail if a committed apple bundle is stale;
   assert no source spec redefines a `_common` component inline. Runs in the same place the other guards do.
5. **Docs** asserting self-containment are updated to the new model.

## Consequences

- **Single source of truth for the shared components** — `ErrorResponse` et al. can no longer drift silently
  across specs; the guard enforces it. Cleans up the backend's "identical duplicates overwrite harmlessly" trick.
- **New build machinery** — a Python bundler + a guard, and the reversal of a documented invariant. This is the
  cost the "Start light / pain before tool" ethos weighs against ~50 lines of *stable, non-drifting* boilerplate.
- **apple bundles are committed generated artifacts** — an exception to "generated artifacts are gitignored",
  forced by SPM offering no pre-generate hook. The guard keeps them honest; a reviewer sees generated YAML in
  the tree. This is the least-pretty consequence and the main reason to sanity-check the decision.
- **Alternative not taken (guard-only):** keep specs self-contained and add *only* the drift guard (assert the
  inline copies stay byte-identical). Gets the drift-prevention value with **no** bundler, no invariant reversal,
  no committed generated specs — but keeps the on-disk duplication. Recorded here because the cost/benefit is
  close.
