---
id: cr-0105
title: Make the S3 object store a hard requirement; remove the directTransport flag
status: done
satisfies: [en.memory.media]
adrs: ["0013", "0032"]
invariants: [operator-blind-e2ee]
---

## Goal

Make an external S3-compatible object store a **hard requirement** of any real instance and **remove the
`directTransport` flag** ([en.memory.media](../docs/features/memory/enablers/en.memory.media.md),
[ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §1/§4): in `%dev` and `%prod` a
missing/blank `veyq.blob-store.endpoint` is a **fail-loud startup error** (media is not optional — an instance
without a byte path is misconfigured, not gracefully degraded); the in-memory store survives **only in `%test`**.
This drops the client-facing store-availability signal (`directTransport` on `/api/setup`) and its degradation
logic — trading a slight on-prem infra bump (an operator must run the bundled Garage) for less client + contract
complexity.

## Context & constraints

- Durable truth: [ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md) §1 (S3 is a hard
  requirement; in-memory is `%test`-only) + §4 (no client-facing store-availability flag; startup fails loud);
  [ADR 0032](../docs/decisions/0032-capability-discovery-version-gating.md) (the manifest does **not** carry an
  infra-preflight `directTransport`/`media.*` entry).
- Reused substrate: the `SetupHandler`/`SetupResource` + `/api/setup` contract (cr-0103); `BlobStorePreflight` +
  the `BlobStore`/`InMemoryBlobStore`/`S3BlobStore` split; the profile config (`application-{dev,test,prod}.properties`).
- Invariant unaffected: **operator-blind-e2ee** — this changes only *whether a store is required*, never how bytes
  are stored (still AEAD ciphertext, presigned direct-to-store).
- Note (cr-0103 relation): `directTransport` was the one **non-required** field on the cr-0103 `SetupStatus`;
  `version`/`capabilities` and the rest stay. Its removal is additive-safe on the version gate (a field removal a
  client no longer reads — bump `build`, not `major`, since no client relies on it).

## Phases (web vertical; native reads the same setup shape — a later CR)

- [x] 1 contracts — remove **`directTransport`** from `SetupStatus` in `contracts/api/setup.openapi.yaml` (+ its
  example). · Done when: `openApiGenerateSpecs` + web `generate:api` clean.
- [x] 2 backend — a **fail-loud startup check**: in `%dev`/`%prod`, a blank/absent `veyq.blob-store.endpoint`
  aborts startup with a clear message (media has no byte path); `%test` keeps the `InMemoryBlobStore` bean. Remove
  `directTransport` from `SetupHandler`/`SetupResource` (and retire/trim `BlobStorePreflight` if it now has no
  other consumer). Set `veyq.blob-store.endpoint` (+ creds) in `application-dev.properties`/dev config so `%dev`
  points at the bundled Garage by default. Slice tests: `%test` still starts + slices pass; a `%dev`/`%prod`-shaped
  config without an endpoint fails fast; `/api/setup` no longer carries `directTransport`. · Done when: `./gradlew
  check` green.
- [x] 3 web — regenerate the setup types (drop `directTransport`) and remove any client references to it
  (per cr-0103 discovery the client TS did **not** consume it — mostly test/reference cleanup). · Done when:
  `npm run check` green.
- [x] 4 coverage/docs — reflect the change where media coverage references the store precondition; confirm
  [en.memory.media](../docs/features/memory/enablers/en.memory.media.md) (already edited: E1 = fail-loud, no
  `directTransport`) and its coverage stay consistent. · Done when: `validate_coverage.py` + `validate_changes.py`
  + `validate_doc_links.py` + `validate_feature_wiring.py` green.

## Definition of done

`GET /api/setup` no longer carries `directTransport`; a `%dev`/`%prod` instance **fails loud at startup** without a
configured store (media is a hard requirement), while `%test` keeps the in-memory stub for the hermetic slices; the
web client carries no store-availability degradation. `%dev` runs against the bundled Garage like prod. Guards
green (`./gradlew check`, `npm run check`, the four `docs/` validators). **Out of scope (follow-ups):** the wider
capability-manifest folding of any remaining infra signals (deferred, ADR 0032); **native parity** (reads the same
`/api/setup`).

## Retrospective

Small, clean cut — landed as scoped. Notes:

- **Fail-loud via a `StartupEvent` observer** (`BlobStoreStartupCheck`) with a **testable pure predicate**
  `hasStore(endpoint, mode)`: mandatory outside `LaunchMode.TEST`. The `@Produces blobStore()` blank→in-memory
  branch is now **`%test`-only** (dev/prod never reach it — they abort at boot). The abort itself isn't drivable
  under `@QuarkusTest` (always TEST mode), so it's covered by the unit test for the predicate + the whole slice
  suite booting on the `%test` in-memory stub (proves that branch still works).
- **`BlobStorePreflight` deleted** — its only real consumers were the setup flow; `MediaHandler` used
  `blobStore.canPresign` directly (the "directTransport flag" was only a stale comment there).
- **`directTransport` gone from contract/backend/web.** The web client never consumed it (only a test comment),
  so the removal was additive-safe on the version gate (a `build` bump, not `major` — no client relied on it).
- **`%dev` now points at the bundled Garage by default** (`application-dev.properties`, documented throwaway
  creds); `public-endpoint` stays per-dev via env (the browse-origin SigV4 subtlety). This makes "always test with
  S3" the out-of-the-box dev default — no more silent in-memory fallback.
- **Consistency sweep:** ADR 0013/0032, `en.memory.media`, `en.memory.derivatives`, the platform `_index`, and the
  capability-discovery use-case all de-referenced `directTransport` (the inbox log + ADR 0032's historical Context
  line kept as-is). Guards green: `./gradlew check`, `npm run check`, the four `docs/` validators.
- **Deferred (unchanged):** any wider capability-manifest folding of remaining infra signals (ADR 0032);
  **native parity** (reads the same `/api/setup`).
