# veyq Agent Context

This file is the operating context for AI agents and contributors working on veyq. The GitHub-facing project entry point is [README.md](README.md).

## Project Intent

veyq is intended to become a self-hostable memory sharing product, not a classic cloud drive or folder-based gallery. The product should help people preserve and share trips, events and personal moments as curated memories with photos, videos, journal text, maps, tracks, music context and metadata.

Memory UI and the Memory APIs are the active product baseline. Folders still exist as organizational containers, but they are not rich content objects. Album and Explorer are historical implementation concepts and must not be reintroduced for new product work.

## Read First

- [README.md](README.md): compact project overview and documentation map.
- [docs/development.md](docs/development.md): local development, Docker Compose and reset sidecar.
- [docs/architecture.md](docs/architecture.md): current backend/frontend architecture.
- [docs/product/memory-product-context.md](docs/product/memory-product-context.md): product vision, domain vocabulary and decisions discussed so far.
- [docs/product/memory-ui-styleguide.md](docs/product/memory-ui-styleguide.md): UI colors, shared CSS classes, component patterns and product-facing style rules.
- [docs/product/ios-ui-styleguide.md](docs/product/ios-ui-styleguide.md): native SwiftUI style, interaction and implementation guidance for iOS work.
- [docs/product/mobile-apps.md](docs/product/mobile-apps.md): native iOS/Android app direction, V1 scope and roadmap.
- [docs/product/documentation-and-acceptance-coverage.md](docs/product/documentation-and-acceptance-coverage.md): feature documentation, acceptance criteria, test coverage mapping and deterministic validation.
- [docs/product/features/README.md](docs/product/features/README.md): feature catalog conventions for acceptance criteria, linked tests and coverage gaps.
- [docs/product/test-feature-reverse-audit.md](docs/product/test-feature-reverse-audit.md): reverse-audit status for current tests and explicit delete candidates.
- [docs/product/future-work.md](docs/product/future-work.md): topics that still need ADRs, API designs or implementation plans.
- [docs/adr/README.md](docs/adr/README.md): ADR process and template.

## Working Rules

- Use `Memory` wording for product-facing concepts. Folder wording is acceptable only for organizational navigation.
- Do not add new Album or Explorer flows, models, API contracts or database tables.
- Follow the Memory UI styleguide for product-facing frontend work, especially shared `.veyq-*` classes, color roles, buttons, lists and dialogs. Reuse existing CSS classes before adding new ones; introduce new classes only when no existing shared or feature-local class fits the behavior.
- For iOS work, follow the iOS UI styleguide and inspect the current SwiftUI implementation under `ios/` before proposing style or navigation changes.
- Keep client parity documentation current when browser, iOS, Android or API behavior changes. Update the relevant parity map before or alongside implementation, and explicitly mark intentional platform differences instead of leaving them implicit.
- Keep public APIs privacy-preserving: never expose cross-memory person graphs or unauthorized memories.
- Keep veyq self-hostable by default. Managed hosting, push gateways, federation and commercial features must remain optional layers.
- Document meaningful product and architecture decisions in `docs/` before or alongside implementation.
- Preserve the feature-centered trust chain: every product, API, UI or operator behavior change must map to a documented feature, each new or changed acceptance criterion must link to meaningful automated tests or an explicit coverage gap, and CI should be able to validate that mapping.
- Treat feature documentation as the source of truth for acceptance coverage. Link capabilities from features when they describe feature availability, but do not use capability IDs as a replacement for feature documentation.
- Prefer meaningful, risk-based coverage over coverage percentages. Use positive and negative tests where behavior has relevant success and failure paths, and do not add tests only to satisfy a metric.
- When changing behavior, actively check the affected feature, acceptance criteria, positive and negative test coverage, and remaining gaps before considering the work complete.
- When touching tests, also check whether each changed or added test is linked from a feature acceptance criterion or documented in the reverse audit as pending mapping, platform trust coverage or a deletion candidate.
- Run `node scripts/validate-feature-coverage.mjs` after changing feature docs or tests.
- Keep [docs/product/future-work.md](docs/product/future-work.md) for deferred or exploratory work only. Do not duplicate implemented behavior there; move shipped behavior into feature YAML, OpenAPI, architecture docs, ADRs or capabilities and remove or rewrite the future-work item.
- Use an ADR for stable decisions about terminology, privacy, sharing, storage, licensing, sync behavior or migrations.
- Use product docs for exploratory ideas, open questions and future-work inventories.
- API contracts live in `docs/api` and are generated into backend and frontend code.
- For backend configuration, model all project-owned `veyq.*` properties with complete `@ConfigMapping` classes instead of injecting individual values with `@ConfigProperty`. Internal Quarkus properties such as `quarkus.*` may still use direct `@ConfigProperty` injection.
- Use domain-scoped Conventional Commit messages as documented in [docs/development.md](docs/development.md). Database and migration changes use the `backend` scope; commits affecting multiple client or server domains list each affected domain.
- Write all repository documentation in English (README, AGENTS, `docs/**`, ADRs, feature YAML and OpenAPI descriptions). Keep code identifiers, table names, annotations and config keys unchanged.

## Current Product North Star

Users should be able to create a Memory for a small moment or a large journey, add media and context, nest related Memories, and share either the whole tree or selected subtrees with clear permissions.

veyq should feel curated, modern and emotional without becoming a social network or a file manager.
