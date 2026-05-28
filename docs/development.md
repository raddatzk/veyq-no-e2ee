# veyq Development Environment

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

The Leaflet media map renders without external tile requests by default. Users can enable OpenStreetMap tiles once per browser; veyq stores that choice in local storage under `veyq:osm-tiles-enabled`.

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
- `veyq`: veyq application container.
- `veyq-reset`: optional reset sidecar for disposable development/test instances.

The normal image-based Compose file keeps MariaDB available only inside the Compose network. The local source-build override below publishes MariaDB on host port `3306` so local database tools can connect during development.

Start the normal stack:

```bash
cd infra
cp .env.template .env
# adjust .env
docker compose up
```

For local source builds instead of a published image, include the build override:

```bash
cd infra
docker compose -f docker-compose.yml -f docker-compose.build.yml up --build
```

The app is served through the domain configured by `VEYQ_TRAEFIK_RULE`. The health endpoint is available at `/q/health`.

### Private Alpha Self-Hosting Runbook

Use this path for a first self-hosted veyq instance. It is suitable for a trusted private alpha, not yet for a public managed service.
For expected operational failure cases, diagnostics and recovery steps, see [`operations.md`](operations.md).

The recommended server install path is the helper script:

```bash
curl -fsSL https://raw.githubusercontent.com/raddatzk/veyq/main/scripts/veyq-server.sh | \
  sudo VEYQ_REPO=raddatzk/veyq VEYQ_REF=main bash -s -- install
```

Set `VEYQ_REPO` to the actual GitHub repository that publishes the veyq image and infra files when running a fork. The script downloads `docker-compose.yml`, `.env.template` and reset-sidecar files into `/opt/veyq`, optionally prompts for common settings, pulls the configured image and starts the stack.

Manual install:

1. Copy `infra/.env.template` to `infra/.env`.
2. Set `VEYQ_IMAGE` to the published image, for example `ghcr.io/raddatzk/veyq:latest`.
3. Set unique values for `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `MAIL_PASSWORD` and, if enabled, `RESET_PASSWORD`.
4. Set `VEYQ_TRAEFIK_RULE` to the public host, for example ``Host(`veyq.example.com`)``.
5. Set `QUARKUS_WEBAUTHN_ORIGINS` to the HTTPS origin and `QUARKUS_WEBAUTHN_RELYING_PARTY_ID` to the host without protocol.
6. Set `VEYQ_SOURCE_URL` when running a fork or modified public deployment so AGPL source links point to the matching source.
7. Configure SMTP through the `MAIL_*` variables before inviting users.
8. Start with `docker compose up -d` from `infra/`.
9. Open the configured host, create the first admin and create the first tenant.

### Media Storage Options

The default media storage mode is a Compose-managed Docker volume:

```env
MEDIA_STORAGE_MODE=docker-volume
MEDIA_STORAGE_MOUNT_TYPE=volume
MEDIA_STORAGE_SOURCE=veyq-media
```

To use a local path, including a CIFS share already mounted by the host OS, bind-mount an absolute host directory:

```env
MEDIA_STORAGE_MODE=host-path
MEDIA_STORAGE_MOUNT_TYPE=bind
MEDIA_STORAGE_SOURCE=/mnt/veyq-media
```

To let Docker mount CIFS directly, use the CIFS volume source:

```env
MEDIA_STORAGE_MODE=cifs-volume
MEDIA_STORAGE_MOUNT_TYPE=volume
MEDIA_STORAGE_SOURCE=veyq-media-cifs
MEDIA_CIFS_DEVICE=//nas.example.com/veyq-media
MEDIA_CIFS_OPTIONS=username=veyq,password=change-me,vers=3.0,uid=1000,gid=1000,file_mode=0660,dir_mode=0770
```

Alternatively, keep the CIFS username and password in a root-readable credentials file on the Docker host and reference it from `.env`:

```env
MEDIA_CIFS_OPTIONS=credentials=/root/veyq-cifs.creds,vers=3.0,uid=1000,gid=1000,file_mode=0660,dir_mode=0770
```

If the CIFS share already contains unrelated data, use `prefixpath` to make an existing subdirectory the veyq storage root:

```env
MEDIA_CIFS_OPTIONS=credentials=/root/veyq-cifs.creds,vers=3.0,prefixpath=veyq,uid=1000,gid=1000,file_mode=0660,dir_mode=0770
```

If the subdirectory should be created automatically by veyq, mount the CIFS share one level higher and put `MEDIA_STORAGE_DIR` inside that mount:

```env
MEDIA_STORAGE_MOUNT_TARGET=/data
MEDIA_STORAGE_DIR=/data/veyq-media
MEDIA_CIFS_OPTIONS=credentials=/root/veyq-cifs.creds,vers=3.0,uid=1000,gid=1000,file_mode=0660,dir_mode=0770
```

Uploaded originals, previews and Memory covers are mounted at `/data/veyq-media` inside the veyq containers by default. Override `MEDIA_STORAGE_DIR` only for unusual container layouts. Keep `.env` at `chmod 600` when it contains CIFS credentials. Run `./veyq-server.sh doctor` or `docker compose config` after changing storage settings.

Upgrade with the helper script:

```bash
cd /opt/veyq
sudo ./veyq-server.sh doctor
sudo ./veyq-server.sh backup
sudo ./veyq-server.sh upgrade
```

The helper script keeps `.env` compatible with additive releases. During `upgrade` and `auto-upgrade`, it adds safe default values for newly introduced optional environment variables and records `VEYQ_ENV_SCHEMA_VERSION`. If a new release needs a secret or operator decision that has no safe default, `upgrade` stops before pulling or restarting containers and offers interactive setup when a terminal is available. `auto-upgrade` never prompts; it stops before pull/restart and reports the failure to ServerOwner users when updater notifications are configured.

Within a major image channel, new environment variables must either be optional with a safe default or be handled by a migration that needs no operator decision. A new required variable without a safe default is a breaking operational change and must ship in a new major channel.

Manual upgrade:

1. Back up `.env`, the database and media storage.
2. Pull the configured image with `docker compose pull`.
3. Restart with `docker compose up -d`.
4. Check `docker compose ps` and `docker compose logs veyq`.

### Optional Auto-Updates

veyq images are tagged as `<major>.<buildNumber>`, where `major` comes from the repository `VERSION` file and `buildNumber` comes from the GitHub Actions run number. The major tag, for example `ghcr.io/raddatzk/veyq:1`, moves to the newest compatible build in that major line.

To enable automatic compatible updates, set `VEYQ_IMAGE` to the major tag, add `updater` to `COMPOSE_PROFILES`, set `VEYQ_AUTO_UPDATE_ENABLED=true` and provide `VEYQ_UPDATER_NOTIFY_TOKEN`. The setup script can generate the token. The updater sidecar is not exposed through Traefik and checks for updates every `VEYQ_AUTO_UPDATE_INTERVAL_HOURS` with random jitter.

The auto-updater only updates the `veyq` application container. It reconciles safe `.env` defaults, runs `doctor`, compares the remote and local image digests, creates a backup, pulls the new image, restarts veyq and waits for the health check. Successful updates are written to the server update log; failed updates create a ServerOwner notification. The sidecar installs its tools as root, then runs the helper as the owner of `.env` so additive `.env` updates, logs and backups do not become root-owned. If an older sidecar already left `.env` owned by root, the new sidecar repairs it to the install-directory owner on startup when the install directory itself is not root-owned.

Back up at least these state locations before upgrading or testing destructive flows:

- `infra_veyq-db`: MariaDB data.
- media storage: `infra_veyq-media` for `docker-volume`, `infra_veyq-media-cifs` for `cifs-volume`, or the configured `MEDIA_STORAGE_SOURCE` host path for `host-path`.
- `infra_traefik-letsencrypt`: ACME certificates and account state.

Example backup commands from the `infra/` directory:

```bash
cp .env "veyq-env-$(date -u +%Y%m%dT%H%M%SZ)"
docker compose exec veyq-db mariadb-dump -u root -p"${DB_ROOT_PASSWORD}" "${DB_NAME:-veyq}" > veyq-db.sql
docker run --rm -v infra_veyq-media:/data -v "$PWD":/backup alpine tar czf /backup/veyq-media.tgz -C /data .
```

For `cifs-volume`, replace `infra_veyq-media` with `infra_veyq-media-cifs`. For `host-path`, mount the configured host path instead, for example `-v /mnt/veyq-media:/data:ro`.

The helper script writes all three backup artifacts together:

```bash
sudo ./veyq-server.sh backup
```

Run `sudo ./veyq-server.sh doctor` after editing `.env`; it validates required values, catches default secrets and checks the Compose configuration without changing running services.

Before considering a private alpha instance ready, run the product smoke flow once:

- Initial setup creates the first admin and tenant.
- Login, logout and refresh cookies work through the configured HTTPS host.
- A Memory can be created with an optional cover.
- Photos and videos can be uploaded and viewed.
- Uploaded videos show poster previews generated by ffmpeg.
- Video playback is original-only in V1. veyq does not create lower-resolution variants, H264 fallback files or HLS/DASH manifests.
- The Journal can be saved and reloaded.
- A child Memory can be created and opened.
- Media maps show geotagged uploads without loading OpenStreetMap tiles before browser opt-in.
- GPX tracks can be uploaded, labeled with an activity type and downloaded again.
- Playlist links from supported providers render as compact cards and open externally.
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
- `RESET_TRAEFIK_RULE`: Traefik host rule for the reset UI, for example `Host('reset.example.com')`. In the actual `.env`, use Traefik's backtick syntax as shown in `infra/.env.template`.

Required passkey settings for Compose:

- `QUARKUS_WEBAUTHN_ORIGINS`: comma-separated HTTPS origins allowed to use passkeys, for example `https://veyq.example.com`.
- `QUARKUS_WEBAUTHN_RELYING_PARTY_ID`: relying-party domain, usually the host without protocol, for example `veyq.example.com`.
- `QUARKUS_WEBAUTHN_RELYING_PARTY_NAME`: display name shown by authenticators. Defaults to `veyq`.

Optional reset setting:

- `RESET_RESTART_CONTAINER`: Docker container restarted after reset. Defaults to `veyq`.

When enabled, the reset service:

- Starts as container `veyq-reset`.
- Serves a small HTTP UI on container port `8081`.
- Is routed by Traefik through `RESET_TRAEFIK_RULE`.
- Uses HTTP Basic Auth with `RESET_USERNAME` and `RESET_PASSWORD`.
- Connects to the same MariaDB database as veyq.
- Mounts the same media storage source as veyq.
- Mounts the Docker socket so it can restart the app container after reset.

Reset behavior:

- Truncates all veyq database tables except `flyway_schema_history`.
- Deletes all entries below `MEDIA_STORAGE_DIR`, default `/data/veyq-media`.
- Restarts `RESET_RESTART_CONTAINER`.
- Keeps database schema, Docker volumes and Flyway migration history.
- Returns the app to the initial setup flow.

Start the stack with the reset sidecar:

```bash
cd infra
docker compose up
```

Then open the host configured by `RESET_TRAEFIK_RULE`, authenticate with `RESET_USERNAME` and `RESET_PASSWORD`, and trigger the reset from the page.

Security notes:

- Use strong reset credentials even on test instances.
- Keep the reset host private where possible.
- Disable `COMPOSE_PROFILES=reset` for any environment that should preserve user data.
- Treat access to the reset UI as destructive admin access.
