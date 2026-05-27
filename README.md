# veyq

veyq is a self-hostable memory sharing application for preserving and sharing trips, events and personal moments as curated memories. It is meant to feel like a private archive with beautiful sharing, not like a generic gallery, cloud drive or folder browser.

## What veyq Is

veyq focuses on memories instead of files. A memory can be a small moment or a larger journey, and the product direction combines media, journal text, maps, tracks, people, weather and music context into one shareable experience.

The long-term goal is a self-hostable product that works on arbitrary domains, keeps central infrastructure optional and avoids public social-graph leakage.

## Current Status

The current implementation is a Quarkus/Vue application with global user accounts, cookie-based authentication and tenant-scoped content.

Memory UI v1 and the Memory APIs are now the active product baseline. Folders remain as organizational containers around Memories, but they are not rich content objects. The former Album/Explorer product line has been removed from the public API, frontend and database schema.

## Tech Stack

- Backend: Quarkus 3, Kotlin, Gradle Wrapper, Flyway, Hibernate ORM Panache
- Frontend: Vue 3, TypeScript, OpenAPI-generated API clients
- Database: MariaDB for the Docker Compose runtime
- API: OpenAPI-first contracts under [`docs/api`](docs/api)
- Infrastructure: Docker Compose, Traefik, local filesystem media storage

## Quick Start

Backend development server:

```bash
cd backend
./gradlew quarkusDev
```

Frontend development server:

```bash
cd frontend
npm install
npm run dev
```

Checks:

```bash
cd backend
./gradlew check
```

```bash
cd frontend
npm run check
```

## Docker Compose Test Instance

The Docker Compose setup under [`infra`](infra) starts Traefik, MariaDB and the veyq application container. It can also run an optional `veyq-reset` sidecar for disposable development or test instances.

```bash
cd infra
cp .env.example .env
# adjust .env
docker compose up --build
```

The reset sidecar can clear Veyq tables, delete media volume contents and restart the app container so the instance returns to the initial setup flow. See [`docs/development.md`](docs/development.md) before enabling it.

## API-First Development

The backend API contracts live as OpenAPI 3.1 specifications in [`docs/api`](docs/api). Backend resources implement generated JAX-RS interfaces, and the frontend generates TypeScript clients from the same contracts.

Keep new public API behavior documented in the relevant OpenAPI spec before or alongside implementation.

## Documentation Map

- [`README.md`](README.md): GitHub entry point and project map.
- [`AGENTS.md`](AGENTS.md): operating context and rules for AI agents and contributors.
- [`docs/development.md`](docs/development.md): local development, Docker Compose and reset sidecar.
- [`docs/architecture.md`](docs/architecture.md): current system architecture and implemented domain model.
- [`docs/product/memory-product-context.md`](docs/product/memory-product-context.md): product vision and Memory context.
- [`docs/product/journal-markdown.md`](docs/product/journal-markdown.md): Journal Markdown and Veyq embed syntax.
- [`docs/product/future-work.md`](docs/product/future-work.md): ADR, API, UX and spike candidates.
- [`docs/adr/README.md`](docs/adr/README.md): Architecture Decision Record process and template.
- [`docs/api`](docs/api): OpenAPI contracts.

## Contributor Notes

Read [`AGENTS.md`](AGENTS.md) before making product or architecture changes. In particular:

- Keep current implementation details separate from the Memory product target.
- Preserve privacy and self-hosting assumptions.
- Record durable product and architecture decisions in ADRs.
- Keep the API-first workflow intact.

## License

veyq is licensed under AGPL-3.0-only. See [`LICENSE`](LICENSE) for the full text and [`NOTICE`](NOTICE) for project notices.

The veyq name, logos and brand assets are reserved separately and are not granted by the software license. See [`docs/legal/trademark-policy.md`](docs/legal/trademark-policy.md).
