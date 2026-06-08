# Feature Catalog

This directory is the source of truth for feature-level acceptance coverage in veyq. Each YAML file describes one feature, the acceptance criteria that define it as working, and the automated tests or explicit gaps that support those criteria.

The catalog is for humans first and agents second: names should stay readable, while IDs and coverage references should stay stable enough for deterministic validation.

## Feature Files

- [`api.openapi-contracts.yaml`](api.openapi-contracts.yaml)
- [`auth.local-accounts-passkeys.yaml`](auth.local-accounts-passkeys.yaml)
- [`frontend.localization-theme.yaml`](frontend.localization-theme.yaml)
- [`frontend.shell-navigation.yaml`](frontend.shell-navigation.yaml)
- [`memory.core.yaml`](memory.core.yaml)
- [`memory.journal-markdown.yaml`](memory.journal-markdown.yaml)
- [`memory.maps.yaml`](memory.maps.yaml)
- [`memory.media.yaml`](memory.media.yaml)
- [`memory.media-resumable-upload.yaml`](memory.media-resumable-upload.yaml)
- [`memory.media-upload-eta.yaml`](memory.media-upload-eta.yaml)
- [`memory.media-zip-download.yaml`](memory.media-zip-download.yaml)
- [`memory.playlists.yaml`](memory.playlists.yaml)
- [`memory.public-sharing.yaml`](memory.public-sharing.yaml)
- [`memory.public-uploader-links.yaml`](memory.public-uploader-links.yaml)
- [`memory.tracks.yaml`](memory.tracks.yaml)
- [`mobile.app-links.yaml`](mobile.app-links.yaml)
- [`platform.api-runtime.yaml`](platform.api-runtime.yaml)
- [`platform.auth-session-security.yaml`](platform.auth-session-security.yaml)
- [`platform.security-pipeline.yaml`](platform.security-pipeline.yaml)
- [`server.auto-updates.yaml`](server.auto-updates.yaml)
- [`server.backup-upgrade.yaml`](server.backup-upgrade.yaml)
- [`server.capability-discovery.yaml`](server.capability-discovery.yaml)
- [`server.setup.yaml`](server.setup.yaml)
- [`settings.tenant-group-admin.yaml`](settings.tenant-group-admin.yaml)
- [`spaces.tenant-group-personal.yaml`](spaces.tenant-group-personal.yaml)
- [`user.account-lifecycle.yaml`](user.account-lifecycle.yaml)
- [`user.profile-account-settings.yaml`](user.profile-account-settings.yaml)

## Relationship To Capabilities

Features are the source of truth for acceptance coverage. Capabilities are optional metadata that describe server-advertised feature availability or app compatibility. Link capabilities from a feature when they apply, but do not create feature documentation by copying the capability list.

A feature may have no linked capability, one linked capability, or several linked capabilities when one user workflow spans multiple advertised abilities.

## YAML Shape

Every feature file uses this structure:

```yaml
id: memory.public-sharing
title: Public Memory Sharing
status: implemented
capabilities:
  - memory.public-sharing
productArea: Public Memory shares with Viewer, Contributor, Manager and Uploader roles.
entryPoints:
  - docs/api/shares.openapi.yaml
  - frontend/src/views/PublicShareView.vue

acceptanceCriteria:
  - id: memory.public-sharing.viewer-readonly
    statement: Viewer shares can read Memory view but cannot write or delete.
    coverage:
      backend:
        integration:
          - testName: viewer reads memory view but cannot write or delete
            kind: negative
            ref: backend/src/test/kotlin/com/veyq/api/memory/MemoryShareResourceTest.kt
      frontend:
        integration:
          - testName: major line 1 public viewer share stays gallery-only and shows share scope
            kind: negative
            ref: frontend/e2e/major-1-smoke.spec.ts
      ios:
        unit:
          - testName: testConnectPersistsClientCapabilitySnapshotWithNativeSession
            kind: positive
            ref: ios/VeyqTests/VeyqTests.swift

gaps:
  - reason: No real full-stack browser E2E exists for public share permissions yet.
    followUp: Add e2e.browser coverage after a full-stack browser harness exists.
```

Criteria without current proof should carry an AC-level `gaps` list instead of empty coverage:

```yaml
acceptanceCriteria:
  - id: server.auto-updates.failure-notification
    statement: Update failures should produce ServerOwner notification.
    gaps:
      - reason: No automated update simulation currently proves ServerOwner failure notification behavior.
        followUp: Add backend integration coverage for update failure event ingestion and notification exposure.
```

## Coverage Levels

Use only these coverage levels:

- `backend.unit`: isolated Kotlin/JUnit logic tests for parsers, validators, services and helpers.
- `backend.integration`: Quarkus, RestAssured, API, database and OpenAPI contract tests.
- `frontend.unit`: Vitest tests for utilities, state, API mappers and Vue components or views with mocked dependencies.
- `frontend.integration`: Playwright browser flows against the real Vue app with a mocked backend via `page.route`.
- `ios.unit`: XCTest unit tests for native iOS models, stores, API adapters, upload state and routing helpers.
- `e2e.browser`: reserved for real browser full-stack tests against frontend, Quarkus and a test database.
- `e2e.ios`: reserved for later native iOS app flows.
- `e2e.android`: reserved for later native Android app flows.

`script` and `workflow` are not Feature Trust coverage levels. They can remain entry points for operator or release features, but their behavior needs real automated tests or documented gaps before it counts as acceptance coverage.

## Coverage Entries

Each coverage item must include:

- `testName`: the readable test name from the referenced file.
- `kind`: `positive` or `negative`.
- `ref`: the test file path.

Use `positive` for intended success paths and `negative` for unauthorized, invalid, unavailable or boundary behavior. A single test may appear under several acceptance criteria when the feature file makes the mapping explicit.

## Coverage Standard

veyq does not require blanket 100% code coverage. Tests exist to create justified trust in documented behavior, not to satisfy coverage numbers.

Coverage should be tied to acceptance criteria, risk-based, layer-appropriate and honest about gaps. Positive and negative tests should both be used when behavior has meaningful success and failure paths.

Do not add tests only to satisfy a metric. Add tests when they prove an acceptance criterion, protect risky behavior, document an important boundary or prevent a known regression.

## Validation Direction

Feature YAML files reference tests. Tests do not need feature IDs in comments or names.

The deterministic validator should check:

- YAML parses successfully.
- Feature files do not use retired coverage seed fields.
- Every feature has `acceptanceCriteria`.
- Every implemented acceptance criterion has coverage or an explicit gap.
- Coverage keys use only the standard hierarchy.
- Every coverage item has `testName`, `kind` and an existing `ref`.
- `kind` is only `positive` or `negative`.
- Linked capability IDs exist in [`docs/product/capabilities.md`](../capabilities.md).
- Every current Kotlin, Vitest and Playwright test is referenced by a Feature YAML or explicitly listed as a delete candidate in the reverse audit.

Run:

```bash
node scripts/validate-feature-coverage.mjs
```

Keep Cucumber out for now. The Feature YAML is the product spec, and normal backend, frontend and future full-stack tests are the proof.
