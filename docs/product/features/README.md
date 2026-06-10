# Feature Catalog

This directory is the source of truth for feature-level acceptance coverage in veyq. Each YAML file describes one feature, the acceptance criteria that define it as working, and the automated tests or explicit gaps that support those criteria.

The catalog is for humans first and agents second: names should stay readable, while IDs and coverage references should stay stable enough for deterministic validation.

Shared rules for feature files, pentest discovery, private findings and
publication are documented in
[`../feature-pentest-governance.md`](../feature-pentest-governance.md).

## Feature File Rules

Feature files are product and acceptance-coverage documents. They must keep the
trust-chain structure that maps behavior to proof:

- one focused feature ID and title
- product area and entry points
- acceptance criteria
- coverage grouped by execution domain, such as `backend`, `frontend`, `ios` or
  future `e2e`
- coverage grouped by test level below that domain, such as `unit` or
  `integration`
- positive/negative test kind, exact test name and test file reference
- explicit AC-level gaps where coverage does not exist yet

Feature files must stay free of concrete pentest attack paths and direct
technical attack hints. They must not contain exploit families, payload ideas,
framework-specific probes or persistence-specific hints. Those belong to the
per-run pentest discovery artifact, where they can be derived from the tested
commit.

## Catalog Shape

The rebuilt feature catalog is organized in [`catalog.yml`](catalog.yml). The
catalog groups feature files by product and security boundary, while each
feature file remains the source of truth for acceptance criteria, automated
coverage and gaps.

### Identity, Sessions And Access Boundaries

- [`auth.password-login.yaml`](auth.password-login.yaml)
- [`auth.password-management.yaml`](auth.password-management.yaml)
- [`auth.passkey-login.yaml`](auth.passkey-login.yaml)
- [`auth.passkey-management.yaml`](auth.passkey-management.yaml)
- [`auth.totp-login.yaml`](auth.totp-login.yaml)
- [`auth.totp-management.yaml`](auth.totp-management.yaml)
- [`auth.invitations.yaml`](auth.invitations.yaml)
- [`auth.session-security.yaml`](auth.session-security.yaml)
- [`user.profile-settings.yaml`](user.profile-settings.yaml)
- [`user.lifecycle.yaml`](user.lifecycle.yaml)
- [`spaces.memory-scopes.yaml`](spaces.memory-scopes.yaml)
- [`admin.tenant-group-settings.yaml`](admin.tenant-group-settings.yaml)

### Private Memory Authoring And Media

- [`memory.structure.yaml`](memory.structure.yaml)
- [`memory.media-library.yaml`](memory.media-library.yaml)
- [`memory.media-resumable-uploads.yaml`](memory.media-resumable-uploads.yaml)
- [`memory.media-upload-progress.yaml`](memory.media-upload-progress.yaml)
- [`memory.media-archives.yaml`](memory.media-archives.yaml)
- [`memory.journal-content.yaml`](memory.journal-content.yaml)
- [`memory.journal-collaboration.yaml`](memory.journal-collaboration.yaml)
- [`memory.location-map.yaml`](memory.location-map.yaml)
- [`memory.gpx-tracks.yaml`](memory.gpx-tracks.yaml)
- [`memory.playlist-links.yaml`](memory.playlist-links.yaml)

### Public Memory Access

- [`sharing.memory-shares.yaml`](sharing.memory-shares.yaml)
- [`sharing.uploader-links.yaml`](sharing.uploader-links.yaml)

### Web And Mobile Client Experience

- [`web.workspace-shell.yaml`](web.workspace-shell.yaml)
- [`web.localization-theme.yaml`](web.localization-theme.yaml)
- [`mobile.deep-links.yaml`](mobile.deep-links.yaml)

### Platform Runtime And API Trust

- [`platform.openapi-contracts.yaml`](platform.openapi-contracts.yaml)
- [`web.api-runtime.yaml`](web.api-runtime.yaml)
- [`platform.capability-discovery.yaml`](platform.capability-discovery.yaml)
- [`platform.request-security.yaml`](platform.request-security.yaml)

### Self-Hosted Operations

- [`operations.initial-setup.yaml`](operations.initial-setup.yaml)
- [`operations.backup-upgrade.yaml`](operations.backup-upgrade.yaml)
- [`operations.auto-updates.yaml`](operations.auto-updates.yaml)

## Relationship To Capabilities

Features are the source of truth for acceptance coverage. Capabilities are optional metadata that describe server-advertised feature availability or app compatibility. Link capabilities from a feature when they apply, but do not create feature documentation by copying the capability list.

A feature may have no linked capability, one linked capability, or several linked capabilities when one user workflow spans multiple advertised abilities.

## YAML Shape

Every feature file uses this structure:

```yaml
id: sharing.memory-shares
title: Public Memory Shares
status: implemented
capabilities:
  - memory.public-sharing
productArea: Public Memory shares with Viewer, Contributor, Manager and Uploader roles.
entryPoints:
  - docs/api/shares.openapi.yaml
  - frontend/src/views/PublicShareView.vue

acceptanceCriteria:
  - id: sharing.memory-shares.viewer-readonly
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
  - id: operations.auto-updates.failure-notification
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

## Pentest Discovery Boundary

Feature files must not define concrete pentest attack paths or direct technical
attack hints. They describe product and security behavior through acceptance
criteria, coverage and explicit gaps.

Before every pentest run, the runner derives attack paths by inspecting previous
runs, feature acceptance criteria, documentation, API contracts and the current
codebase. Technical attack choices belong in the run discovery artifact and
manifest, not in feature YAML.

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
- Feature YAML rejects concrete `attackPaths` and `dynamicAttackPathSignals`;
  pentest paths must be derived during run discovery instead.
- Linked capability IDs exist in [`docs/product/capabilities.md`](../capabilities.md).
- Every current Kotlin, Vitest and Playwright test is referenced by a Feature YAML or explicitly listed as a delete candidate in the reverse audit.

Run:

```bash
node scripts/validate-feature-coverage.mjs
```

Keep Cucumber out for now. The Feature YAML is the product spec, and normal backend, frontend and future full-stack tests are the proof.
