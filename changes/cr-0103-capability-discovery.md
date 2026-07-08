---
id: cr-0103
title: Capability discovery & version gating — lean core (web vertical), sharing as first consumer
status: done
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
  (the flow + the 7 acceptance criteria), [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md)
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
- **Capability grain (ADR 0032 §6):** a capability is a **coarser projection** over one or more use-cases — the
  catalog is **cross-referenced** to the use-case index (`serves: [uc.…]`, catalog-only, **not** on the wire),
  **not** invented in parallel and **not** 1:1 per use-case. A validator enforces that every `serves` resolves to
  a **real, built** use-case (the same wiring guarantee `validate_feature_wiring` gives the feature graph).

## Phases (web vertical; native is a later CR)

- [x] 1 contracts — add **`contracts/platform/capabilities.json`** (append-only catalog; each entry carries a
  **`serves: [uc.…]`** back-ref — catalog-only, not on the wire). Seed four policy-toggle ids: `sharing.public-links`
  → `[uc.sharing.share-public-viewer, uc.sharing.share-contributor]`, `sharing.uploader-links` →
  `[uc.sharing.share-uploader]`, `spaces.groups` → `[uc.spaces.add-group-member, uc.spaces.manage-group-members,
  uc.spaces.rename-group, uc.spaces.delete-group, uc.spaces.consent-group-add]`, `account.device-trust` →
  `[uc.account.trust-device, uc.account.remember-this-device, uc.account.auto-unlock, uc.account.set-device-security]`.
  Extend `SetupStatus` in `contracts/api/setup.openapi.yaml` with **`version: { major, build }`** and
  **`capabilities: [{ id }]`**. Add a shared **`capability_disabled`** (`422`) error code. · Done when:
  `openApiGenerateSpecs` (backend) + web `generate:api` clean.
- [x] 2 backend — a **`CapabilityRegistry`** (loads the pinned catalog; subtracts a `veyq.capabilities.disabled`
  config list → the **active** set — only ids whose feature is built are advertised) + a **version** source
  (`major`/`build`, from build config). Fold both into `SetupHandler` → the `/api/setup` response. Enforce a
  disabled capability at each seeded capability's **create/enable gate** — the **worked example is sharing**
  (`createShare(role)` maps to its capability: `VIEWER`/`CONTRIBUTOR` → `sharing.public-links`, `UPLOADER` →
  `sharing.uploader-links`; if inactive → `422 capability_disabled` **before** the personal-scope check, so it
  reads as instance policy). The **same one-line gate** goes on the group-create path (`spaces.groups`) and the
  device-wrap enrol path (`account.device-trust`) where those endpoints already exist. Slice tests: setup
  advertises the active set + version; a disabled capability is absent from the manifest **and** its create path
  returns `422`; an enabled one still works; the manifest carries no identity. · Done when: `./gradlew check` green.
- [x] 3 web — a **capability store** (fetch `/api/setup` on boot; expose `hasCapability(id)` + `version`); a
  **blocking incompatibility screen** on a `major` mismatch (direction-specific copy), shown **before** login. Gate
  each seeded consumer on `hasCapability`: the **`ShareDialog`** role options + Share affordance
  (`sharing.public-links` / `sharing.uploader-links`) — the **worked, live-smoked** consumer — plus the group-space
  UI (`spaces.groups`) and the "remember this device" toggle (`account.device-trust`) via the same helper. i18n
  EN+DE. · Done when: `npm run check` green + a **live smoke** (with `sharing.uploader-links` disabled: the Uploader
  option is gone and a forced `createShare` UPLOADER is rejected; a `major` bump on the server shows the
  incompatibility screen).
- [x] 4 coverage/docs — fill [uc.platform.capability-discovery.yaml](../docs/coverage/features/platform/use-cases/uc.platform.capability-discovery.yaml):
  add test refs for all seven criteria (backend slice + web unit + the live smoke + the catalog↔use-case
  validator), keep **apple/android** as gaps,
  **drop `status: planned`**, mark the use-case **built (web)** in [platform/_index.md](../docs/features/platform/_index.md).
  Add a validator (extend `validate_feature_wiring.py` or a sibling `validate_capabilities.py`) that checks every
  catalog entry's **`serves`** resolves to a real, built use-case. · Done when: `validate_coverage.py` +
  `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py` (+ the capability↔use-case check)
  green.

## Definition of done

`GET /api/setup` advertises `version { major, build }` + an active `capabilities` manifest sourced from an
append-only catalog seeded with four policy-toggle ids (`sharing.public-links`, `sharing.uploader-links`,
`spaces.groups`, `account.device-trust`), each cross-referenced to the use-cases it `serves`. The web client
**hard-gates** on a `major` mismatch (a blocking, direction-specific screen before login) and **soft-gates** each
seeded consumer on `hasCapability`. **Sharing is the worked end-to-end proof** (manifest → client soft-gate →
server `422`, live-smoked); `spaces.groups` / `account.device-trust` ride the same machinery + one-line gate. An
operator can **disable** a capability instance-wide: it vanishes from the manifest **and** its create endpoint
rejects it (`422 capability_disabled`). The manifest stays **pre-auth, identity-free, identical for every
caller** (operator-blind-safe). Guards green (`./gradlew check`, `npm run check`, the four `docs/` validators, a
live smoke). **Out of scope (follow-ups):** the deprecation lifecycle (`state`/`sunsetAt`/`supersededBy`); per-user
"new feature" notices/acks; audience filtering; folding `suites`/`directTransport` into the unified manifest;
client device self-capabilities; **native parity**.

## Retrospective

Built web-vertical as scoped. Notes for the follow-ups:

- **Wire shapes.** `version { major, build }` and `capabilities [{ id }]` are **required** on `SetupStatus` (the
  client always needs `major` for the gate + an — possibly empty — active set). Kept `[{ id }]` object-shaped so
  the deferred `state`/`sunsetAt` fields graft on additively (a `build` bump, never `major`). `major`/`build` are
  plain ints; `directTransport` stays a separate advisory field (folding it in is deferred).
- **One registry, three gates.** `CapabilityRegistry.active = CapabilityIds.ADVERTISED − veyq.capabilities.disabled`
  feeds both the manifest and the endpoint gate. The capability check sits **first** in each create path (before
  the node/scope lookup) so a disabled capability reads as instance policy (`422 capability_disabled`), not a
  scope error — the VIEWER-passes-to-404 vs UPLOADER-422 contrast in `CapabilityDisabledSliceTest` pins that.
- **Drift-proofing on both surfaces.** Pinned ids (`CapabilityIds` / web `capabilities.ts`) + a conformance test
  each against `contracts/platform/capabilities.json`, plus `scripts/validate_capabilities.py` (in the CI
  trust-chain) asserting every `serves` resolves to a real, **built** use-case. All 12 seed use-cases are built,
  so "real + built" is a hard check today; **reserving an id ahead of its use-cases being built** (ADR 0032 §6)
  needs the built-check to gain a reserved-entry skip — the first thing to add when that lifecycle lands.
- **`account.device-trust` couples browser-support AND capability.** The `rememberSupported` flag now ANDs the
  capability in, so disabling it hides the toggle **and** stops `enrolConvenienceWrap` enrolling any DEVICE /
  DEVICE_KEY wrap — matching the server's `422` on `addWrap`. Group creation gates only the create affordance
  (existing groups stay listed/manageable), matching the server, which gates only `createGroup`.
- **Live smoke (with the dev backend restarted per scenario):** `sharing.uploader-links` disabled → `/api/setup`
  omits it (real backend) and the app still boots compatibly to login (device-trust toggle shown, since only
  uploader was off); `veyq.version.major=2` → the app shows the blocking incompatibility screen (update-app copy,
  DE) before login and clears on reset. The forced `createShare(UPLOADER)` → `422` is a real-HTTP slice test; the
  logged-in ShareDialog option-hiding is a component test (the dev `.run/.env` has no password to drive UI login).
- **Dev-mode gotcha (worth a note):** `quarkusDev` does **not** re-run `openApiGenerateSpecs` on a contract
  change, so a running dev instance breaks with "Unresolved reference 'Capability'/'Version'" until restarted —
  expected after a contract edit, not a code bug. A fresh `./gradlew quarkusDev` regenerates + recovers.
- **Deferred (unchanged from the plan):** deprecation lifecycle (`state`/`sunsetAt`/`supersededBy`), per-user
  new-feature notices, audience filtering, folding `suites`/`directTransport` into the manifest, **native
  parity** (apple/android read the same manifest + `major` gate — a later CR).
