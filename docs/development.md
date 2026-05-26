# Veyq Development Environment

This document describes the local and test-instance development environment. Product and architecture decisions live in the broader `docs/` tree; this file is about day-to-day development operations.

## Local Application Development

Backend:

```bash
cd backend
./gradlew quarkusDev
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Backend checks:

```bash
cd backend
./gradlew check
```

If the local default JDK is newer than the Kotlin/Gradle toolchain supports, run checks with the Java 21 installation explicitly:

```bash
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/kevin.raddatz/Library/Java/JavaVirtualMachines/temurin-21.0.11/Contents/Home check
```

Frontend checks:

```bash
cd frontend
npm run check
```

Frontend E2E checks:

```bash
cd frontend
npm run e2e
```

The E2E suite uses Playwright and starts the local Vite dev server automatically. On a fresh machine, install the browser runtime once:

```bash
cd frontend
npx playwright install chromium
```

OpenAPI client generation:

```bash
cd frontend
npm run generate:api
```

Run this after changing files under `docs/api/`. Backend checks also validate and regenerate backend OpenAPI artifacts through Gradle.

Memory stabilization checks currently expected before review:

- Backend check with Java 21 when needed.
- Frontend `npm run check`.
- Frontend `npm run e2e` for setup and workspace shell regressions.
- Frontend `npm run generate:api` after API spec edits.
- Manual `git diff --stat` review for migration, OpenAPI and generated client changes.

Passkey development defaults:

- Local backend accepts WebAuthn origins `http://localhost:5173` and `http://localhost:8080`.
- Browser passkeys require HTTPS, except for localhost secure-context handling.

## Docker Compose Development/Test Instance

The Compose setup under `infra/` starts:

- `traefik`: reverse proxy, TLS and routing.
- `veyq-db`: MariaDB database.
- `veyq`: Veyq application container.
- `veyq-reset`: optional reset sidecar for disposable development/test instances.

Start the normal stack:

```bash
cd infra
cp .env.example .env
# adjust .env
docker compose up --build
```

The app is served through the domain configured by `VEYQ_TRAEFIK_RULE`. The health endpoint is available at `/q/health`.

### Private Alpha Self-Hosting Runbook

Use this path for a first self-hosted Veyq instance. It is suitable for a trusted private alpha, not yet for a public managed service.

1. Copy `infra/.env.example` to `infra/.env`.
2. Set unique values for `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `MAIL_PASSWORD` and, if enabled, `RESET_PASSWORD`.
3. Set `VEYQ_TRAEFIK_RULE` to the public host, for example ``Host(`veyq.example.com`)``.
4. Set `WEBAUTHN_ORIGINS` to the HTTPS origin and `WEBAUTHN_RP_ID` to the host without protocol.
5. Configure SMTP through the `MAIL_*` variables before inviting users.
6. Start with `docker compose up --build` from `infra/`.
7. Open the configured host, create the first admin and create the first tenant.

Back up at least these Docker volumes before upgrading or testing destructive flows:

- `infra_veyq-db`: MariaDB data.
- `infra_veyq-media`: original uploads, previews and explicit Memory covers.
- `infra_traefik-letsencrypt`: ACME certificates and account state.

Example backup commands from the `infra/` directory:

```bash
docker compose exec veyq-db mariadb-dump -u root -p"${DB_ROOT_PASSWORD}" "${DB_NAME:-veyq}" > veyq-db.sql
docker run --rm -v infra_veyq-media:/data -v "$PWD":/backup alpine tar czf /backup/veyq-media.tgz -C /data .
```

Before considering a private alpha instance ready, run the product smoke flow once:

- Initial setup creates the first admin and tenant.
- Login, logout and refresh cookies work through the configured HTTPS host.
- A Memory can be created with an optional cover.
- Photos and videos can be uploaded and viewed.
- The Journal can be saved and reloaded.
- A child Memory can be created and opened.
- Viewer, Contributor, Manager and Uploader shares can be opened from a fresh browser session.
- Public upload links accept media without exposing the gallery.
- ZIP downloads work for direct Memory media and for media including child Memories.

## Reset Sidecar

The reset sidecar is intended for development and disposable test instances where a quick return to the initial setup flow is useful. It must not be exposed for real production data.

Enable it with the Compose profile:

```env
COMPOSE_PROFILES=reset
```

Required reset settings in `infra/.env`:

- `RESET_USERNAME`: username for the reset page.
- `RESET_PASSWORD`: password for the reset page.
- `RESET_TRAEFIK_RULE`: Traefik host rule for the reset UI, for example `Host('reset.example.com')`. In the actual `.env`, use Traefik's backtick syntax as shown in `infra/.env.example`.

Required passkey settings for Compose:

- `WEBAUTHN_ORIGINS`: comma-separated HTTPS origins allowed to use passkeys, for example `https://veyq.example.com`.
- `WEBAUTHN_RP_ID`: relying-party domain, usually the host without protocol, for example `veyq.example.com`.
- `WEBAUTHN_RP_NAME`: display name shown by authenticators. Defaults to `Veyq`.

Optional reset setting:

- `RESET_RESTART_CONTAINER`: Docker container restarted after reset. Defaults to `veyq`.

When enabled, the reset service:

- Starts as container `veyq-reset`.
- Serves a small HTTP UI on container port `8081`.
- Is routed by Traefik through `RESET_TRAEFIK_RULE`.
- Uses HTTP Basic Auth with `RESET_USERNAME` and `RESET_PASSWORD`.
- Connects to the same MariaDB database as Veyq.
- Mounts the same media volume as Veyq.
- Mounts the Docker socket so it can restart the app container after reset.

Reset behavior:

- Truncates all Veyq database tables except `flyway_schema_history`.
- Deletes all entries below `MEDIA_STORAGE_DIR`, default `/data/veyq-media`.
- Restarts `RESET_RESTART_CONTAINER`.
- Keeps database schema, Docker volumes and Flyway migration history.
- Returns the app to the initial setup flow.

Start the stack with the reset sidecar:

```bash
cd infra
docker compose up --build
```

Then open the host configured by `RESET_TRAEFIK_RULE`, authenticate with `RESET_USERNAME` and `RESET_PASSWORD`, and trigger the reset from the page.

Security notes:

- Use strong reset credentials even on test instances.
- Keep the reset host private where possible.
- Disable `COMPOSE_PROFILES=reset` for any environment that should preserve user data.
- Treat access to the reset UI as destructive admin access.
