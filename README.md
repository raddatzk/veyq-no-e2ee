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
- `LETSENCRYPT_EMAIL`, `VEYQ_TRAEFIK_RULE`: Traefik/Let's Encrypt routing values for the public HTTPS host.
- `QUARKUS_WEBAUTHN_ORIGINS`, `QUARKUS_WEBAUTHN_RELYING_PARTY_ID`, `QUARKUS_WEBAUTHN_RELYING_PARTY_NAME`: passkey origin and relying-party settings. Keep these aligned with the public HTTPS host.
- `DB_PASSWORD`, `DB_ROOT_PASSWORD`: MariaDB application and root passwords. Replace generated or example values before keeping data.
- `MAIL_FROM`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_LOGIN`, `MAIL_TLS`: SMTP settings for invitations, password resets and email confirmation.
- `VEYQ_SOURCE_URL`, `VEYQ_VERSION`, `VEYQ_REVISION`: source/license and build metadata shown in server settings.
- `MEDIA_STORAGE_MODE`, `MEDIA_STORAGE_MOUNT_TYPE`, `MEDIA_STORAGE_SOURCE`, `MEDIA_CIFS_DEVICE`, `MEDIA_CIFS_OPTIONS`: media storage backend settings. The supported modes are Docker named volume, host bind mount and Docker-managed CIFS volume. `MEDIA_STORAGE_DIR` is an optional advanced override for the internal container path and normally does not need to be set.
- `MEDIA_CHUNK_SIZE`, `MEDIA_MAX_UPLOAD_SIZE`, `MEDIA_UPLOAD_SESSION_TTL_HOURS`, `MEDIA_UPLOAD_SESSION_CLEANUP_CRON`, `FFMPEG_PATH`, `MEDIA_PREVIEW_TIMEOUT_SECONDS`: media upload and preview-generation settings.
- `COOKIE_SECURE`, `COOKIE_ACCESS_TOKEN_NAME`, `COOKIE_REFRESH_TOKEN_NAME`, `ACCESS_TOKEN_TTL_MINUTES`, `REFRESH_TOKEN_TTL_MINUTES`, `ACCESS_TOKEN_AUDIT_TTL_DAYS`, `REFRESH_TOKEN_AUDIT_TTL_DAYS`, `TOKEN_CLEANUP_CRON`: authentication cookie and token retention settings.
- `INVITATION_TTL_DAYS`, `EMAIL_CHANGE_TTL_HOURS`, `RESET_PASSWORD_TTL_HOURS`, `USER_CLEANUP_CRON`, `USER_CLEANUP_RETENTION_DAYS`: user workflow token lifetimes and cleanup.
- `BCRYPT_TARGET_MS`, `BCRYPT_MIN_COST`, `BCRYPT_MAX_COST`, `BCRYPT_BENCHMARK_SAMPLES`: password hashing benchmark and bounds.
- `COMPOSE_PROFILES=reset`, `RESET_TRAEFIK_RULE`, `RESET_USERNAME`, `RESET_PASSWORD`, `RESET_RESTART_CONTAINER`: optional reset sidecar for disposable test instances only.

See [`infra/.env.template`](infra/.env.template) for all defaults and comments.

### Media Storage

The Compose stack stores uploaded originals, previews and Memory covers below `/data/veyq-media` inside the app container by default. `MEDIA_STORAGE_MODE` controls where that path is mounted from:

- `docker-volume`: default, uses the Compose-managed `veyq-media` Docker volume.
- `host-path`: bind-mounts an absolute host directory from `MEDIA_STORAGE_SOURCE`, for example a CIFS share mounted by the host at `/mnt/veyq-media`.
- `cifs-volume`: lets Docker create the `veyq-media-cifs` volume from `MEDIA_CIFS_DEVICE` and `MEDIA_CIFS_OPTIONS`, for example `//nas.example.com/photos` plus CIFS mount options.

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

The reset sidecar can clear veyq tables, delete media storage contents and restart the app container so the instance returns to the initial setup flow. See [`docs/development.md`](docs/development.md) before enabling it.

## API-First Development

The backend API contracts live as OpenAPI 3.1 specifications in [`docs/api`](docs/api). Backend resources implement generated JAX-RS interfaces, and the frontend generates TypeScript clients from the same contracts.

Keep new public API behavior documented in the relevant OpenAPI spec before or alongside implementation.

## CI

GitHub Actions includes [`ci.yml`](.github/workflows/ci.yml) for backend/frontend checks and [`container.yml`](.github/workflows/container.yml) for building and publishing runtime images to GitHub Container Registry.

## Documentation Map

- [`README.md`](README.md): GitHub entry point and project map.
- [`AGENTS.md`](AGENTS.md): operating context and rules for AI agents and contributors.
- [`docs/development.md`](docs/development.md): local development, Docker Compose and reset sidecar.
- [`docs/operations.md`](docs/operations.md): operator runbook for expected self-hosting failure cases.
- [`docs/architecture.md`](docs/architecture.md): current system architecture and implemented domain model.
- [`docs/product/memory-product-context.md`](docs/product/memory-product-context.md): product vision and Memory context.
- [`docs/product/memory-ui-styleguide.md`](docs/product/memory-ui-styleguide.md): UI colors, shared CSS classes, component patterns and product-facing style rules.
- [`docs/product/journal-markdown.md`](docs/product/journal-markdown.md): Journal Markdown and veyq embed syntax.
- [`docs/product/playlists.md`](docs/product/playlists.md): Memory playlist links, permissions and API surface.
- [`docs/product/future-work.md`](docs/product/future-work.md): ADR, API, UX and spike candidates.
- [`docs/product/v1-scope.md`](docs/product/v1-scope.md): V1 release scope and deferred work.
- [`docs/releases/v1.md`](docs/releases/v1.md): Draft V1 release notes, known limits and release checklist.
- [`docs/legal/privacy.md`](docs/legal/privacy.md): Privacy notes for cookies, public shares and OpenStreetMap map tiles.
- [`docs/legal/trademark-policy.md`](docs/legal/trademark-policy.md): veyq brand and trademark usage rules.
- [`docs/legal/commercial-boundary.md`](docs/legal/commercial-boundary.md): Boundary between the AGPL community edition and optional commercial layers.
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
