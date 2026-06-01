# Project Consistency Audit

Last reviewed: 2026-06-01

This audit captures repository-level consistency findings for veyq. It is meant to help human developers and AI agents quickly understand what is trustworthy, where project knowledge lives and which refactors would most improve maintainability.

## Current State

- The product direction is consistently Memory-first across `README.md`, `AGENTS.md`, ADRs, API docs and feature YAML. Folder language remains present only where organizational navigation is still part of the model.
- The feature-centered trust chain is explicit: feature YAML files define acceptance criteria, link concrete tests and document gaps where proof is missing.
- Backend, frontend, iOS, collaboration sidecar, infra and docs are separated clearly enough for scoped work. The strongest onboarding path is still `README.md` -> `AGENTS.md` -> `docs/development.md` -> `docs/architecture.md` -> relevant feature YAML.
- Public API behavior is centralized in `docs/api`, with generated backend and frontend artifacts. This is a strong consistency point, but generated output should be ignored during refactor sizing.
- The project has meaningful automated tests across backend integration/unit, frontend unit/integration, collaboration unit and iOS unit layers. Existing gaps are mostly honest product gaps rather than hidden missing tests.

## Fixed During Audit

- CI now runs a dedicated feature-coverage validation job and no longer relies only on documentation saying the trust chain is CI-backed.
- Feature YAML, capability metadata and reverse-audit changes now trigger CI despite broad docs path ignores.
- `scripts/validate-feature-coverage.mjs` now resolves paths from the repository root and gives an actionable message when frontend dependencies are missing.
- Development docs now include the local command sequence for feature coverage validation.
- Outdated feature gap wording now points to the missing full-stack browser harness instead of the already-existing validator.
- `MemoryDetail.vue` no longer owns optimistic media and bucket reconciliation directly; that state now lives in a focused composable with Feature-linked unit tests.
- Shared backend API formatting now lives in `ApiFormat`, so path segment encoding and UTC API timestamps are no longer redefined across media, memory, share, track, playlist, tenant, user and TOTP services.
- Media response DTO construction now lives in `MediaResponseMapper`, keeping `MediaService` closer to orchestration and persistence behavior.
- Frontend media URL selection now lives in shared media URL helpers, so gallery, journal, map, cover picker and lightbox components use the same preview/content/poster semantics.
- Frontend memory API normalization now lives in `memoryMappers.ts`, reducing the size of `memoryApi.ts` without changing the facade contract.
- Public-share memory DTO construction now lives in `PublicShareMemoryMapper`, keeping path, cover URL and media/child count semantics out of `MemoryShareService`.
- Managed and public share summary construction now lives in `MemoryShareMapper`, including share notices, role API mapping and authenticated redirect URL formatting.

## Refactor Candidates

- Continue splitting `frontend/src/features/memories/components/MemoryDetail.vue`. Media and bucket reconciliation has been extracted, but header actions, drag-and-drop uploads, journal editing, share dialogs, track actions and playlist actions still share one component.
- Continue splitting `backend/src/main/kotlin/com/veyq/api/library/media/MediaService.kt`. Response DTO mapping is extracted, but upload/session behavior, media persistence, previews, metadata and cleanup-adjacent responsibilities still share the service.
- Continue splitting `backend/src/main/kotlin/com/veyq/api/memory/MemoryShareService.kt`. Share DTO mapping is extracted, but authorization, token/session handling and share-scoped content mutations still share one service.
- Break up large test suites after behavior is stable, especially `UserManagementResourceTest`, `MemoryServiceTest`, `MemoryShareResourceTest`, `MemoryDetail.test.ts` and `VeyqTests.swift`. Keep acceptance mapping intact while grouping by feature behavior, not by helper setup.
- Consider moving `frontend/src/i18n/messages.ts` into per-domain message modules if translation churn keeps growing. Keep generated or static message aggregation deterministic.
- Review frontend code-splitting around the main app bundle. `npm run check` currently builds successfully, but Vite warns that the main minified chunk is larger than 500 kB.
- Add deterministic generated-artifact freshness validation for OpenAPI/frontend clients. The feature catalog already tracks this as a gap.

## Test And Documentation Gaps

- The largest explicit coverage gap is still real full-stack browser E2E (`e2e.browser`) against frontend, Quarkus and a test database. Current Playwright tests are useful `frontend.integration` tests with mocked backend routes.
- Operator features such as backup, upgrade and auto-update are documented but mostly gap-backed because scripts/workflows are not counted as Feature Trust coverage. A fixture-based script/operator test harness would improve confidence.
- Public-share and setup flows have strong backend/frontend proof, but still lack real full-stack browser coverage for the highest-risk paths.
- Some privacy-sensitive and permission-sensitive map/track/share edge cases are documented as gaps and should be converted into explicit acceptance criteria before implementation work expands there.

## Maintenance Rules

- Treat generated frontend clients and generated backend artifacts as API contract output, not refactor targets unless the generator config changes.
- Before changing behavior, update the relevant feature YAML acceptance criteria and test links or add an explicit gap.
- After changing feature docs or tests, run `node scripts/validate-feature-coverage.mjs` with frontend dependencies installed.
- After API contract changes, run frontend generation and backend checks, then review generated diffs separately from handwritten code.
- Keep future-work entries exploratory only; when behavior ships, move the truth into feature YAML, OpenAPI, architecture docs or ADRs.
