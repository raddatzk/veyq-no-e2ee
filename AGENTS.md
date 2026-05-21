# Veyq Agent Context

This file is the operating context for AI agents and contributors working on Veyq. The GitHub-facing project entry point is [README.md](README.md).

## Project Intent

Veyq is intended to become a self-hostable memory sharing product, not a classic cloud drive or folder-based gallery. The product should help people preserve and share trips, events and personal moments as curated memories with photos, videos, journal text, maps, tracks, music context and metadata.

The current codebase still contains library concepts such as folders, albums and media. Treat those as the implemented baseline, not necessarily as final product language. A first Memory API and backend domain layer exists, but the visible app is still mostly Explorer/Album based. The target product language is centered on `Memory` and nested `Memories`.

## Read First

- [README.md](README.md): compact project overview and documentation map.
- [docs/development.md](docs/development.md): local development, Docker Compose and reset sidecar.
- [docs/architecture.md](docs/architecture.md): current backend/frontend architecture.
- [docs/product/memory-product-context.md](docs/product/memory-product-context.md): product vision, domain vocabulary and decisions discussed so far.
- [docs/product/future-work.md](docs/product/future-work.md): topics that still need ADRs, API designs or implementation plans.
- [docs/adr/README.md](docs/adr/README.md): ADR process and template.

## Working Rules

- Preserve the distinction between current implementation and target product direction. Current code may still say folder, album or media; target product language is Memory.
- Prefer `Memory` wording for new product-facing concepts unless a task explicitly targets the current library API.
- Keep public APIs privacy-preserving: never expose cross-memory person graphs or unauthorized memories.
- Keep Veyq self-hostable by default. Managed hosting, push gateways, federation and commercial features must remain optional layers.
- Document meaningful product and architecture decisions in `docs/` before or alongside implementation.
- Use an ADR for stable decisions about terminology, privacy, sharing, storage, licensing, sync behavior or migrations.
- Use product docs for exploratory ideas, open questions and future-work inventories.
- API contracts live in `docs/api` and are generated into backend and frontend code.

## Current Product North Star

Users should be able to create a Memory for a small moment or a large journey, add media and context, nest related Memories, and share either the whole tree or selected subtrees with clear permissions.

Veyq should feel curated, modern and emotional without becoming a social network or a file manager.
