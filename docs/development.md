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

OpenAPI client generation:

```bash
cd frontend
npm run generate:api
```

Run this after changing files under `docs/api/`. Backend checks also validate and regenerate backend OpenAPI artifacts through Gradle.

Memory stabilization checks currently expected before review:

- Backend check with Java 21 when needed.
- Frontend `npm run check`.
- Frontend `npm run generate:api` after API spec edits.
- Manual `git diff --stat` review for migration, OpenAPI and generated client changes.

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
