---
id: cr-0144
title: Contracts common-component drift guard (audit P3f / #76 — ADR 0038 guard path)
status: done
satisfies: []
adrs: ["0038"]
invariants: [operator-blind]
---

## Goal

Close audit finding #76 (**P3f contracts common components**) on the path ADR 0038 settles: **keep the specs
self-contained, guarantee the shared components don't drift with a check instead of a bundler.**

The 9 `contracts/api/*.openapi.yaml` copy a handful of components — `ErrorResponse` (×9), the `bearerAuth` scheme
(×8), the `ScopeId`/`TenantId` path-params, and several shared enums. Extracting them to a `_common.openapi.yaml`
+ cross-file `$ref` is **blocked by the toolchains** (apple's swift-openapi rejects external refs and SPM has no
pre-generate hook; android's `sanitizeSpecs` flat-copies specs) — a bundler would reverse a *documented*
self-contained invariant and check generated YAML into the tree, to DRY ~50 lines of **stable, non-drifting**
boilerplate (ADR 0038 records the full analysis + the rejected bundler design). The real risk is *future* drift,
which a guard addresses with none of that machinery.

## Context & constraints

- **New guard:** `scripts/validate_contracts_common.py` — asserts, across all specs: `ErrorResponse` identical
  (ignoring the per-domain `code.example`), `bearerAuth` `type`+`scheme` identical (ignoring `description`),
  `ScopeId`/`TenantId` path-params structurally identical, and every copy of a shared enum family (node-kind /
  derivative-kind / membership-role / credential-method / suite-version) carrying the same values. A copy that
  overlaps a canonical family (≥2 values) but isn't exactly it fails **unless declared an intentional variant**
  (only `[DEVICE, DEVICE_KEY]`, the device-only wrap-rotation subset). Same style/deps as the sibling
  `validate_*` guards (PyYAML, `sys.exit(1)`).
- **Not policed on purpose:** collection-wrapping (bare array / `{results}` / `{items}` / domain-key) — an
  intentional batch-vs-list distinction, a design choice not drift.
- **Wired into CI** in `.github/workflows/ci.yml` beside the other trust-chain guards.
- **Verified it actually catches drift** (a guard that never fails is worthless): temporarily changed
  `ErrorResponse.code.maxLength` in one spec → failed; added `POSTER` to media's derivative enum → failed
  (`MediaDerivativeKind … diverges from the derivative-kind family`); reverted → green. Passes clean on the
  current specs (they are consistent today).
- **ADR 0038** rewritten in place from Proposed(bundler) → **Accepted(guard)**, keeping the rejected bundler as
  the recorded alternative.
- **Verify:** `python3 scripts/validate_contracts_common.py` (green) — and it now runs in CI.

## Phases

- [x] 1 contracts — add `validate_contracts_common.py`; wire into `ci.yml`; flip ADR 0038 to Accepted(guard).
  · **Done:** guard green on current specs; drift-injection test confirmed it fails on a changed `ErrorResponse`
  and a diverged enum; reverted clean.

## Definition of done

`scripts/validate_contracts_common.py` green on the current specs, red on injected drift; wired into CI; ADR 0038
Accepted with the guard decision. Specs unchanged — no toolchain touched, operator-blind unaffected.

## Retrospective

- **Verified the toolchains before designing.** The literal finding ("extract `_common` + `$ref`") was
  un-buildable as-is — apple can never resolve external refs — so the honest deliverable was the *guarantee*
  (no drift), not the *mechanism* (a shared file). The guard gives the former without the latter's cost.
- **Guard proven by failing it.** Injected two real drifts and confirmed red, so the green is meaningful — not a
  vacuous always-pass check.
- **Cheaper than the pivot it replaced.** ~120 lines of Python vs. a bundler + committed generated specs + a
  reversed invariant, for boilerplate that does not actually drift today.
