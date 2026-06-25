# veyq

<sub>Pronounced **/veɪk/**.</sub>

veyq is a self-hostable memory sharing application for preserving and sharing trips, events and personal moments as curated memories. It is meant to feel like a private archive with beautiful sharing, not like a generic gallery, cloud drive or folder browser.

## What veyq Is

veyq focuses on memories instead of files. A memory can be a small moment or a larger journey, and the product direction combines media, journal text, maps, tracks, people, weather and music context into one shareable experience.

The long-term goal is a self-hostable product that works on arbitrary domains, keeps central infrastructure optional and avoids public social-graph leakage.

This README is the human entry point for the project. Operational working rules for agents and contributors live in [`AGENTS.md`](../AGENTS.md), and the product documentation under [`docs/product`](docs/product) captures feature direction, acceptance expectations and future work.

## Current Status

The current implementation is a Quarkus/Vue application with global user accounts, cookie-based authentication and tenant-scoped content.

Memory UI and the Memory APIs are now the active product baseline. Folders remain as organizational containers around Memories, but they are not rich content objects. The former Album/Explorer product line has been removed from the public API, frontend and database schema.

veyq uses a feature-centered trust chain: documented features define acceptance criteria, acceptance criteria link to meaningful positive and negative tests or explicit gaps, and CI validates those links over time.

## Tech Stack

- Backend: Quarkus 3, Kotlin, Gradle Wrapper, Flyway, Hibernate ORM Panache
- Frontend: Vue 3, TypeScript, OpenAPI-generated API clients
- Database: MariaDB for the Docker Compose runtime
- API: OpenAPI-first contracts under [`docs/api`](docs/api)
- Infrastructure: Docker Compose, Traefik, configurable local filesystem media storage

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

## Server Installation

The recommended self-hosted server path is the setup script. It asks for the install directory, downloads the Compose files, creates `.env`, prompts for the common server values, can start the stack and later provides `doctor`, `backup` and `upgrade` commands.

Run this on the server:

```bash
curl -fsSL https://raw.githubusercontent.com/raddatzk/veyq/main/scripts/veyq-server.sh | \
  bash -s -- install
```

After installation, run `./veyq-server.sh doctor` from the install directory after `.env` changes and before upgrades. Use `sudo` only if your Docker setup requires it. For expected self-hosting failure cases and recovery steps, see [`docs/operations.md`](docs/operations.md).

### Environment Variables

The install script prompts for the common values and writes them to `.env`. These variables can also be set before running the script or edited later in `.env`:

- `VEYQ_DIR`: install directory for downloaded Compose files and `.env`; defaults to the current directory.
- `VEYQ_REPO`, `VEYQ_REF`: GitHub repository and branch/tag/SHA used by the script to download server files; defaults to `raddatzk/veyq` and `main`.
- `VEYQ_IMAGE`: runtime container image used by the production Compose stack, for example `ghcr.io/raddatzk/veyq:latest`.
- `COMPOSE_PROFILES=collaboration`, `VEYQ_COLLABORATION_IMAGE`, `JOURNAL_LIVE_TOKEN_SECRET`, `JOURNAL_LIVE_SIDECAR_SECRET`: optional Journal live-editing sidecar settings.
- `VEYQ_AUTO_UPDATE_ENABLED`, `VEYQ_AUTO_UPDATE_INTERVAL_HOURS`, `VEYQ_AUTO_UPDATE_JITTER_MINUTES`, `VEYQ_UPDATER_NOTIFY_TOKEN`: optional internal updater sidecar settings. For automatic compatible updates, use a major-channel image such as `ghcr.io/raddatzk/veyq:1` and enable the `updater` Compose profile.
- `LETSENCRYPT_EMAIL`, `VEYQ_TRAEFIK_RULE`: Traefik/Let's Encrypt routing values for the public HTTPS tenant hosts.
- `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_ROOT_PASSWORD`: MariaDB database name, application user and passwords (the app connects via a preset `DB_KIND=mariadb` + `jdbc:mariadb` `DB_URL`). Replace generated or example values before keeping data.
- `MAIL_FROM`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_LOGIN`, `MAIL_TLS`: SMTP settings for invitation and account emails.
- `VEYQ_APP_BASE_URL`: public web origin used to build invite links in outgoing email.
- `MEDIA_STORAGE_MODE`, `MEDIA_STORAGE_MOUNT_TYPE`, `MEDIA_STORAGE_SOURCE`, `MEDIA_STORAGE_MOUNT_TARGET`, `MEDIA_CIFS_DEVICE`, `MEDIA_CIFS_OPTIONS`: media storage backend settings. The supported modes are Docker named volume, host bind mount and Docker-managed CIFS volume. `MEDIA_STORAGE_DIR` is the in-container path the encrypted blobs are written to — it feeds the app's blob-store directory and must sit on the mounted media volume.
- `MEDIA_MAX_UPLOAD_SIZE`: maximum request body size, i.e. the largest single upload chunk (default `32M`).
- `PROXY_ADDRESS_FORWARDING`, `PROXY_ALLOW_X_FORWARDED`, `PROXY_ENABLE_FORWARDED_HOST`, `VEYQ_RATELIMIT_TRUSTED_PROXIES`: reverse-proxy header trust. `X-Forwarded-*` is honored only from the trusted-proxy ranges, which also let the per-IP rate limiter key on the real client IP behind Traefik instead of Traefik's own address.
- `VEYQ_RATELIMIT_ENABLED`, `LOG_LEVEL`: toggle the unauthenticated-endpoint rate limiter (default on) and the application log level (default `INFO`).
- Not in the current backend: cookie/JWT session tuning, server-side Argon2id benchmark bounds, Have-I-Been-Pwned checks, WebAuthn/passkeys, server-side media transcoding/preview generation and long-poll concurrency caps. The env vars that configured those in the earlier backend were removed rather than left as no-ops.
- `COMPOSE_PROFILES=reset`, `RESET_TRAEFIK_RULE`, `RESET_USERNAME`, `RESET_PASSWORD`, `RESET_RESTART_CONTAINER`: optional reset sidecar for disposable test instances only.

See [`infra/.env.template`](infra/.env.template) for all defaults and comments.

### Media Storage

The Compose stack stores uploaded originals, previews and Memory covers below `/data/veyq-media` inside the app container by default. `MEDIA_STORAGE_MODE` controls where that path is mounted from:

- `docker-volume`: default, uses the Compose-managed `veyq-media` Docker volume.
- `host-path`: bind-mounts an absolute host directory from `MEDIA_STORAGE_SOURCE`, for example a CIFS share mounted by the host at `/mnt/veyq-media`.
- `cifs-volume`: lets Docker create the `veyq-media-cifs` volume from `MEDIA_CIFS_DEVICE` and `MEDIA_CIFS_OPTIONS`, for example `//nas.example.com/photos` plus CIFS mount options. Use `prefixpath=veyq` in `MEDIA_CIFS_OPTIONS` for an existing share subdirectory, or mount the share at `MEDIA_STORAGE_MOUNT_TARGET=/data` and set `MEDIA_STORAGE_DIR=/data/veyq-media` so veyq creates that subdirectory automatically.

Keep CIFS credentials out of `docker-compose.yml`: put them in `.env` through `MEDIA_CIFS_OPTIONS` or reference a root-readable credentials file from that variable. If CIFS credentials are stored directly in `.env`, keep the file private with `chmod 600 .env`.

## Docker Compose Test Instance

The Docker Compose setup under [`infra`](infra) starts Traefik, MariaDB and the veyq application container. It can also run an optional `veyq-reset` sidecar for disposable development or test instances.

```bash
cd infra
cp .env.template .env
# adjust .env
docker compose up
```

Use `docker-compose.build.yml` when building the app image from local source instead of using `VEYQ_IMAGE`. The normal image-based Compose stack keeps MariaDB internal to the Compose network; the local build override publishes MariaDB on host port `3306` for development tools.

Published veyq images use `<major>.<buildNumber>` tags, for example `1.123`. The moving major tag, for example `:1`, points to the newest compatible build in that major line. Previous major release branches receive security fixes and critical bug fixes for one year after the first next-major build is published. `latest` points to the newest build overall and is not the recommended auto-update channel for persistent self-hosted instances.

The reset sidecar can clear veyq tables, delete media storage contents and restart the app container so the instance returns to the initial setup flow. See [`docs/development.md`](docs/development.md) before enabling it.

## API-First Development

The backend API contracts live as OpenAPI 3.1 specifications in [`docs/api`](docs/api). Backend resources implement generated JAX-RS interfaces, and the frontend generates TypeScript clients from the same contracts.

Keep new public API behavior documented in the relevant OpenAPI spec before or alongside implementation.

## CI

GitHub Actions includes [`ci.yml`](../.github/workflows/ci.yml) for backend, frontend and acceptance-coverage checks, and [`container.yml`](../.github/workflows/container.yml) for building and publishing runtime images to GitHub Container Registry. The coverage job validates the Greenfield trust chain from documentation artifacts to acceptance criteria, positive/negative tests and explicit gaps whenever API contracts, coverage YAML, reverse-audit mappings or validator code changes.

## Documentation Map

- [`README.md`](README.md): GitHub entry point and project map.
- [`AGENTS.md`](../AGENTS.md): operating context and rules for AI agents and contributors.
- [`docs/development.md`](docs/development.md): local development, Docker Compose and reset sidecar.
- [`docs/operations.md`](docs/operations.md): operator runbook for expected self-hosting failure cases.
- [`docs/architecture.md`](docs/architecture.md): current system architecture and implemented domain model.
- [`docs/product/memory-product-context.md`](docs/product/memory-product-context.md): product vision and Memory context.
- [`docs/product/memory-ui-styleguide.md`](docs/product/memory-ui-styleguide.md): UI colors, shared CSS classes, component patterns and product-facing style rules.
- [`docs/product/ios-ui-styleguide.md`](docs/product/ios-ui-styleguide.md): native SwiftUI style and interaction guidance for the iOS app.
- [`docs/product/journal-markdown.md`](docs/product/journal-markdown.md): Journal Markdown and veyq `::` embed token syntax.
- [`docs/product/ios-feature-parity.md`](docs/product/ios-feature-parity.md): browser-to-iOS parity map and first native implementation track.
- [`docs/product/playlists.md`](docs/product/playlists.md): Memory playlist links, permissions and API surface.
- [`docs/product/capabilities.md`](docs/product/capabilities.md): Server capability IDs, current baseline and maintenance rules.
- [`docs/wiki/coverage/README.md`](docs/wiki/coverage/README.md): Greenfield use-case coverage conventions and YAML files mirroring the feature/use-case tree with test links and gaps.
- [`docs/product/features/README.md`](docs/product/features/README.md): Legacy feature catalog reference while old product detail is migrated.
- [`docs/product/test-feature-reverse-audit.md`](docs/product/test-feature-reverse-audit.md): Reverse-audit status for current tests and explicit delete candidates.
- [`docs/product/documentation-and-acceptance-coverage.md`](docs/product/documentation-and-acceptance-coverage.md): Feature documentation, acceptance criteria, test coverage mapping and deterministic validation.
- [`docs/product/mobile-apps.md`](docs/product/mobile-apps.md): Native iOS/Android app direction, V1 scope and roadmap.
- [`docs/product/ios-testflight-submission.md`](docs/product/ios-testflight-submission.md): Source text for iOS TestFlight metadata, beta review, privacy answers and export compliance.
- [`docs/product/hosting-and-operations-strategy.md`](docs/product/hosting-and-operations-strategy.md): Exploratory strategy for Cloud, managed hosting, operations, billing and long-term privacy architecture.
- [`docs/product/project-consistency-audit.md`](docs/product/project-consistency-audit.md): Repository-level consistency findings, fixed gaps and refactor candidates.
- [`docs/product/future-work.md`](docs/product/future-work.md): Deferred ADR, API, UX and spike candidates; not the implementation baseline.
- [`docs/product/major-1-scope.md`](docs/product/major-1-scope.md): Major line 1 product scope and deferred work.
- [`docs/releases/major-1.md`](docs/releases/major-1.md): Major line 1 notes, known limits and readiness checklist.
- [`docs/legal/privacy.md`](docs/legal/privacy.md): Privacy notes for cookies, public shares and OpenStreetMap map tiles.
- [`docs/legal/ios-app-privacy-policy.md`](docs/legal/ios-app-privacy-policy.md): Public privacy policy source text for the official iOS app.
- [`docs/legal/trademark-policy.md`](docs/legal/trademark-policy.md): veyq brand and trademark usage rules.
- [`docs/legal/commercial-boundary.md`](docs/legal/commercial-boundary.md): Boundary between the AGPL community edition and optional commercial layers.
- [`docs/adr/README.md`](docs/adr/README.md): Architecture Decision Record process and template.
- [`docs/api`](docs/api): OpenAPI contracts.

## Contributor Notes

Read [`AGENTS.md`](../AGENTS.md) before making product or architecture changes. In particular:

- Keep current implementation details separate from the Memory product target.
- Keep product-facing work Memory-first; do not reintroduce Album or Explorer concepts.
- Preserve privacy and self-hosting assumptions.
- Record durable product and architecture decisions in ADRs.
- Keep the API-first workflow intact.
- Keep use-case coverage, acceptance criteria and positive/negative test links current for product, API, UI and operator behavior changes.
- Run `node scripts/validate-feature-coverage.mjs` after changing coverage docs or tests.

## License

veyq is licensed under AGPL-3.0-only. See [`LICENSE`](../LICENSE) for the full text and [`NOTICE`](../NOTICE) for project notices.

The veyq name, logos and brand assets are reserved separately and are not granted by the software license. See [`docs/legal/trademark-policy.md`](docs/legal/trademark-policy.md).
