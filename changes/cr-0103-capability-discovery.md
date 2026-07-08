---
id: cr-0103
title: Capability discovery & version gating — lean core (web vertical), sharing as first consumer
status: planned
satisfies: [uc.platform.capability-discovery]
adrs: ["0032", "0031", "0030", "0029", "0021"]
invariants: [operator-blind-e2ee]
---

## Goal

Build the **lean core** of capability discovery + version gating
([uc.platform.capability-discovery](../docs/features/platform/use-cases/uc.platform.capability-discovery.md),
[ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md)): make the pre-auth **`GET /api/setup`**
advertise a **`version { major, build }`** and an active **`capabilities: [{ id }]`** manifest; have the web client
**hard-gate** on a `major` mismatch and **soft-gate** optional features on capability presence; and let an operator
**turn a capability off instance-wide** — omitted from the manifest **and** rejected at its endpoint. The first
gated consumer is the **sharing** roles (`sharing.public-links` / `sharing.uploader-links`), which proves the whole
loop end-to-end. **Web vertical only; native is a later CR.** **Lean core: no deprecation lifecycle / notices /
audience filter.**

## Context & constraints

- Durable truth: [uc.platform.capability-discovery](../docs/features/platform/use-cases/uc.platform.capability-discovery.md)
  (the flow + the 6 acceptance criteria), [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md)
  (the `major` hard gate; the append-only capability catalog; capability ≠ permission; disable = omit-from-manifest
  **and** enforce-at-endpoint). Reuses the pre-auth `/api/setup` surface (the `suites` / `directTransport` precedent)
  and the sharing create-gate ([ADR 0029](../docs/decisions/0029-public-viewer-share.md)/[0030](../docs/decisions/0030-contributor-public-share.md)/[0031](../docs/decisions/0031-uploader-public-share.md)).
- Reused substrate (not rebuilt): the `SetupHandler` + `/api/setup` contract; the sharing `createShare` gate; the
  codegen pipeline (a new `contracts/platform/capabilities.json` → backend registry + web constants, same idea as
  the crypto vectors / task tags being pinned from `contracts/`).
- Invariant at risk: **operator-blind-e2ee.** The manifest is **pre-auth + identity-free + identical for every
  caller** — it must never carry a user id, a per-user set, or any content. It advertises only instance-wide
  abilities (same for everyone), so it adds no operator-visible signal beyond "what this build/instance supports".
- **Lean-core gates:** active capabilities only (`[{ id }]`, no `state`); `major` bumps **only** on a capability
  removal; a disabled capability is enforced **server-side** (not just hidden); capability stays distinct from the
  per-scope permission it composes with.

## Phases (web vertical; native is a later CR)

- [ ] 1 contracts — add **`contracts/platform/capabilities.json`** (append-only catalog; seed: `sharing.public-links`,
  `sharing.uploader-links`). Extend `SetupStatus` in `contracts/api/setup.openapi.yaml` with **`version: { major,
  build }`** and **`capabilities: [{ id }]`**. Add a **`capability_disabled`** error code on the sharing create path
  (`POST /api/shares` → `422`). · Done when: `openApiGenerateSpecs` (backend) + web `generate:api` clean.
- [ ] 2 backend — a **`CapabilityRegistry`** (loads the pinned catalog; subtracts a `veyq.capabilities.disabled`
  config list → the **active** set) + a **version** source (`major`/`build`, from build config). Fold both into
  `SetupHandler` → the `/api/setup` response. Enforce a disabled capability at the **sharing create-gate**:
  `createShare(role)` maps to its capability (`VIEWER`/`CONTRIBUTOR` → `sharing.public-links`, `UPLOADER` →
  `sharing.uploader-links`); if inactive → `422 capability_disabled` (before the personal-scope check, so it reads
  as instance policy, not a per-node error). Slice tests: setup advertises the active set + version; a disabled
  capability is absent from the manifest **and** `createShare` for it returns `422`; an enabled one still works;
  the manifest carries no identity. · Done when: `./gradlew check` green.
- [ ] 3 web — a **capability store** (fetch `/api/setup` on boot; expose `hasCapability(id)` + `version`); a
  **blocking incompatibility screen** on a `major` mismatch (direction-specific copy), shown **before** login; the
  **`ShareDialog`** gates its role options on `sharing.public-links` / `sharing.uploader-links` (absent → hidden),
  and the Share affordance itself on `sharing.public-links`. i18n EN+DE. · Done when: `npm run check` green + a
  **live smoke** (with `sharing.uploader-links` disabled: the Uploader option is gone and a forced `createShare`
  UPLOADER is rejected; a `major` bump on the server shows the incompatibility screen).
- [ ] 4 coverage/docs — fill [uc.platform.capability-discovery.yaml](../docs/coverage/features/platform/use-cases/uc.platform.capability-discovery.yaml):
  add test refs for all six criteria (backend slice + web unit + the live smoke), keep **apple/android** as gaps,
  **drop `status: planned`**, mark the use-case **built (web)** in [platform/_index.md](../docs/features/platform/_index.md).
  · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py`
  green.

## Definition of done

`GET /api/setup` advertises `version { major, build }` + an active `capabilities` manifest sourced from an
append-only catalog. The web client **hard-gates** on a `major` mismatch (a blocking, direction-specific screen
before login) and **soft-gates** the sharing role options on `sharing.public-links` / `sharing.uploader-links`. An
operator can **disable** a sharing capability instance-wide: it vanishes from the manifest **and** the create
endpoint rejects it (`422 capability_disabled`). The manifest stays **pre-auth, identity-free, identical for every
caller** (operator-blind-safe). Guards green (`./gradlew check`, `npm run check`, the four `docs/` validators, a
live smoke). **Out of scope (follow-ups):** the deprecation lifecycle (`state`/`sunsetAt`/`supersededBy`); per-user
"new feature" notices/acks; audience filtering; folding `suites`/`directTransport` into the unified manifest;
client device self-capabilities; **native parity**.

## Retrospective

_(to be filled at close)_
