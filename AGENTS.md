# veyq Agent Context

This file is the operating context for AI agents and contributors working on veyq. The GitHub-facing project entry point is [README.md](README.md).

## Project Intent

veyq is intended to become a self-hostable memory sharing product, not a classic cloud drive or folder-based gallery. The product should help people preserve and share trips, events and personal moments as curated memories with photos, videos, journal text, maps, tracks, music context and metadata.

Memory UI v1 and the Memory APIs are the active product baseline. Folders still exist as organizational containers, but they are not rich content objects. Album and Explorer are historical implementation concepts and must not be reintroduced for new product work.

## Read First

- [README.md](README.md): compact project overview and documentation map.
- [docs/development.md](docs/development.md): local development, Docker Compose and reset sidecar.
- [docs/architecture.md](docs/architecture.md): current backend/frontend architecture.
- [docs/product/memory-product-context.md](docs/product/memory-product-context.md): product vision, domain vocabulary and decisions discussed so far.
- [docs/product/memory-ui-styleguide.md](docs/product/memory-ui-styleguide.md): UI colors, shared CSS classes, component patterns and product-facing style rules.
- [docs/product/future-work.md](docs/product/future-work.md): topics that still need ADRs, API designs or implementation plans.
- [docs/adr/README.md](docs/adr/README.md): ADR process and template.

## Working Rules

- Use `Memory` wording for product-facing concepts. Folder wording is acceptable only for organizational navigation.
- Do not add new Album or Explorer flows, models, API contracts or database tables.
- Follow the Memory UI styleguide for product-facing frontend work, especially shared `.veyq-*` classes, color roles, buttons, lists and dialogs.
- Keep public APIs privacy-preserving: never expose cross-memory person graphs or unauthorized memories.
- Keep veyq self-hostable by default. Managed hosting, push gateways, federation and commercial features must remain optional layers.
- Document meaningful product and architecture decisions in `docs/` before or alongside implementation.
- Use an ADR for stable decisions about terminology, privacy, sharing, storage, licensing, sync behavior or migrations.
- Use product docs for exploratory ideas, open questions and future-work inventories.
- API contracts live in `docs/api` and are generated into backend and frontend code.

## Current Product North Star

Users should be able to create a Memory for a small moment or a large journey, add media and context, nest related Memories, and share either the whole tree or selected subtrees with clear permissions.

veyq should feel curated, modern and emotional without becoming a social network or a file manager.
