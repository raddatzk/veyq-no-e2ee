# Documentation and Acceptance Coverage

This document captures veyq's feature-centered acceptance coverage workflow: make implemented features explicit, give each feature testable acceptance criteria, link those criteria to meaningful automated tests, and use deterministic validation to keep the mapping trustworthy.

## Problem

veyq has a growing Memory product surface across backend APIs, Vue UI, public shares, uploads, journals, operator scripts and release workflows. Tests exist at several layers, but the repository does not yet have one maintained place that explains which product behaviors are promised and which tests prove those behaviors still hold.

The goal is not blanket 100% code coverage. Tests should create justified trust in documented behavior, not exist as compliance artifacts.

## Goal

veyq should have a maintained feature catalog that describes implemented product and operator-facing behavior, including acceptance criteria and the tests that prove those criteria still hold.

The catalog should help humans and agents answer three questions quickly:

- Which features does veyq currently claim to support?
- What acceptance criteria define each feature as working?
- Which backend, frontend or future full-stack tests cover each criterion?

Deferred or exploratory work should remain in [Future Work](future-work.md), but it should still be clear that it is not part of the current acceptance baseline.

## Trust Chain

The trust chain is:

```text
Feature -> Acceptance criteria -> Positive/negative tests or explicit gaps -> CI
```

Feature documentation is the source of truth for acceptance coverage. Tests are linked from feature documentation instead of requiring every test to carry feature IDs. A test may cover several acceptance criteria as long as the mapping is explicit in the feature catalog. Existing tests should also be checked in reverse: a test that is not linked from a feature should either be mapped, documented as platform trust coverage, or marked as a deletion candidate with a reason.

Capabilities are useful inventory input and compatibility metadata. They should be linked from features when they describe feature availability, but capability IDs are not a replacement for feature documentation. A feature may link one capability, several capabilities or none.

## Feature Catalog Shape

Feature catalog conventions and the current YAML feature files live in [Feature Catalog](features/README.md). Each documented feature should have:

- Feature ID: stable lowercase dotted identifier.
- User or operator value: the behavior the feature provides in Memory/product language.
- Current status: implemented, partial, planned or deferred.
- Linked capabilities: optional capability IDs that advertise feature availability.
- Entry points: relevant UI routes, API contracts, commands or operator flows.
- Acceptance criteria: externally observable conditions that define done behavior.
- Test coverage: automated tests linked directly from each criterion, with test names and file references.
- Gaps: known missing tests, missing documentation, unclear behavior or follow-up ADR/API work.

Acceptance criteria should be concrete enough that a reviewer can decide whether a test proves the behavior. Criteria should avoid implementation-only wording unless the feature itself is operational or developer-facing.

## Coverage Principles

Acceptance coverage is risk-based and layer-appropriate. Each implemented criterion should be covered at the lowest useful layer, and high-risk user-facing, permission-sensitive or data-loss-sensitive behavior should also have API, integration or real full-stack coverage when unit tests alone do not prove the behavior.

Coverage can come from different layers depending on risk. The standard Feature Trust levels are:

- `backend.unit`: isolated Kotlin/JUnit logic tests for parsers, validators, services and helpers.
- `backend.integration`: Quarkus, RestAssured, API, database and OpenAPI contract tests.
- `frontend.unit`: Vitest tests for utilities, state, API mappers and Vue components or views with mocked dependencies.
- `frontend.integration`: Playwright browser flows against the real Vue app with a mocked backend via `page.route`.
- `ios.unit`: XCTest unit tests for native iOS models, stores, API adapters, upload state and routing helpers.
- `e2e.browser`: reserved for real browser full-stack tests against frontend, Quarkus and a test database.
- `e2e.ios`: reserved for later native iOS app flows.
- `e2e.android`: reserved for later native Android app flows.

Script and workflow references are not Feature Trust coverage levels. Operator and release features should keep explicit gaps until real automated tests prove their acceptance criteria.

Positive and negative scenarios should both be used when the behavior has meaningful success and failure modes. Positive tests prove the intended path works. Negative tests prove unauthorized, invalid, unavailable or boundary cases are rejected or shown safely.

If a criterion, layer or negative scenario cannot reasonably be covered yet, the feature documentation should mark the gap explicitly with the reason and intended follow-up. Current Playwright tests with a mocked backend are `frontend.integration`, not full-stack `e2e.browser`.

Code coverage percentages are not a target. Strong targeted tests that prove acceptance criteria are better than broad but shallow coverage that only executes code. Do not add tests only to satisfy a metric.

## Deterministic Validation

Keep deterministic checks in place before introducing any agentic review. The checks should:

- Validate the feature catalog structure.
- Ensure linked capability IDs exist when they are part of the current baseline.
- Ensure referenced test files exist.
- Ensure every coverage item has a concrete `testName`, `kind` and `ref`.
- Ensure `kind` is only `positive` or `negative`.
- Flag implemented criteria that have neither test coverage nor an explicit coverage gap.
- Extract current test names and flag tests that are not referenced by any feature YAML or explicitly listed as delete candidates in [Test To Feature Reverse Audit](test-feature-reverse-audit.md).
- Keep validation focused on source-controlled facts instead of subjective review.

These checks should run in CI and give contributors fast feedback when feature, acceptance or test references drift.

The current local validation entry point is:

```bash
node scripts/validate-feature-coverage.mjs
```

## Future Advisory Review

A ChatGPT-backed GitHub review can be added later as an advisory layer after the feature/acceptance/test chain is reliable. It should not replace deterministic validation or normal tests.

If introduced, the advisory review should:

- Detect the PR diff and reduce the review scope to affected features, API contracts, docs, tests and workflows.
- Load the feature catalog and acceptance criteria for only the affected scope where possible.
- Compare changed behavior against documented acceptance criteria.
- Check whether new or changed acceptance criteria have corresponding tests.
- Check whether changed code appears to weaken existing acceptance coverage.
- Comment on the PR with a concise assessment, including missing documentation, missing tests and ambiguous criteria.
- Avoid blocking on broad, unrelated feature areas that the PR did not touch.

An optional broader assessment mode may also check product-language consistency, privacy and authorization risk, self-hosting impact, API-first contract alignment and test or documentation gaps tied to the PR's actual scope.

Any advisory agent should comment with findings and open questions only. It should not become the foundation of the trust chain.

## Maintenance Rules

1. Keep [Feature Catalog](features/README.md) as the implemented behavior baseline.
2. Add or update acceptance criteria before or alongside product, API, UI and operator behavior changes.
3. Link tests directly to acceptance criteria with standardized coverage levels and concrete test names, including positive and negative tests where the behavior has meaningful success and failure paths.
4. Mark coverage gaps with the reason and intended follow-up when meaningful automated proof does not exist yet.
5. Keep reverse-audit coverage current for tests that are not linked from feature acceptance criteria.
6. Run `node scripts/validate-feature-coverage.mjs` after changing feature docs or tests.
7. Keep deferred or exploratory work in [Future Work](future-work.md), but remove or rewrite future-work entries once the behavior becomes part of the accepted implementation baseline.
8. Optionally reintroduce advisory AI review only after deterministic validation remains reliable.

## Remaining Decisions

- Whether platform trust areas such as auth sessions, HTTP client behavior, routing helpers and theme/i18n behavior should stay in feature YAMLs or move into a separate trust catalog if feature files become too broad.
- Whether and when to add advisory ChatGPT/GitHub review now that deterministic validation exists.
- When to add real full-stack `e2e.browser` coverage for the highest-risk setup, Memory and public-share flows.
