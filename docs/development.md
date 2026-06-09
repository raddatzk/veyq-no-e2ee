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

In local dev, backend discovery points the iOS mobile-login handoff to the Vite frontend at
`http://localhost:8081` by default. Override this local dev-only handoff URL with
`VEYQ_PUBLIC_WEB_BASE_URL` when testing from a physical device or another host, and make sure the
frontend dev server is reachable from that device. Non-dev deployments derive discovery URLs from
the current request host so tenant domains remain authoritative.

Tenant registration links keep their tenant-domain binding in normal deployments. In local dev,
requests from `localhost`, `127.0.0.1` or `::1` may validate a tenant registration link for testing,
and copied registration links use `VEYQ_PUBLIC_WEB_BASE_URL` when the admin request also comes from
localhost.

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

Backend configuration:

- Project-owned `veyq.*` properties should be modeled with complete `@ConfigMapping` classes. Avoid injecting individual `veyq.*` values with `@ConfigProperty`.
- Internal Quarkus properties such as `quarkus.*` do not need veyq-owned config mappings and may use direct `@ConfigProperty` injection where that keeps the integration simpler.

Frontend checks:

```bash
cd frontend
npm run check
```

Feature coverage validation:

```bash
cd frontend
npm ci
cd ..
node scripts/validate-feature-coverage.mjs
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
- Feature coverage validation with `node scripts/validate-feature-coverage.mjs` after frontend dependencies are installed.
- Frontend `npm run e2e` for setup and workspace shell regressions.
- Frontend `npm run generate:api` after API spec edits.
- Manual `git diff --stat` review for migration, OpenAPI and generated client changes.

## Commit Messages

Use domain-scoped Conventional Commit messages:

```text
<type>(<domain>[,<domain>...]): <imperative summary>
```

Supported types:

- `feat`
- `fix`
- `docs`
- `test`
- `refactor`
- `chore`
- `build`
- `ci`
- `perf`

Use these domain scopes when they identify the affected area:

- `backend`
- `collaboration`
- `web`
- `ios`
- `android`
- `docs`
- `infra`
- `ci`
- `repo`

Database and migration changes are part of `backend`; do not use a separate `db` scope. If a commit changes multiple domains, list every affected domain in the scope, separated by commas and no spaces. If a change touches almost every area, use `repo` only when the change is genuinely repository-wide.

Examples:

```text
feat(backend): add migration for memory share permissions
fix(web): prevent unauthorized memory preview
docs(ios): document offline sync behavior
test(backend,web): cover memory delete permissions
refactor(ios,web): align memory card naming
chore(repo): align commit message rules
```

Passkey development defaults:

- Local backend accepts WebAuthn origins `http://localhost:8081`, `http://localhost:8080` and `http://localhost:5173`.
- Override the accepted local WebAuthn origins with `WEBAUTHN_ORIGINS` when testing the Vite frontend from another host or port.
- Browser passkeys require HTTPS, except for localhost secure-context handling.

## Docker Compose Development/Test Instance

The Compose setup under `infra/` starts:

- `traefik`: reverse proxy, TLS and routing.
- `veyq-db`: MariaDB database.
- `veyq`: veyq application container.
- `veyq-collaboration`: optional Hocuspocus/Yjs sidecar for live Journal editing when the `collaboration` profile is enabled.
- `veyq-reset`: optional reset sidecar for disposable development/test instances.

The normal image-based Compose file keeps MariaDB available only inside the Compose network. The local source-build override below publishes MariaDB on host port `3306` so local database tools can connect during development.

## iOS TestFlight Deployment

The native iOS app is deployed through the `iOS TestFlight` GitHub Actions workflow. The workflow runs automatically on pushes to `main` that change `ios/**` or the workflow itself, and can also be started manually with `workflow_dispatch`.

Before the first run, configure these Apple Developer identifiers for the `veyq.app` domain:

- App ID: `app.veyq.ios`
- Share Extension App ID: `app.veyq.ios.share`
- App Group: `group.app.veyq.ios`, enabled for both the app and the share extension.
- App Store Connect app record for bundle ID `app.veyq.ios`.

Configure these GitHub environment or repository secrets:

- `APPLE_TEAM_ID`: Apple Developer Team ID.
- `APP_STORE_CONNECT_API_KEY_ID`: App Store Connect API key ID.
- `APP_STORE_CONNECT_ISSUER_ID`: App Store Connect issuer ID.
- `APP_STORE_CONNECT_API_PRIVATE_KEY_BASE64`: Base64-encoded contents of the `AuthKey_*.p8` API key.
- `APPLE_DISTRIBUTION_CERTIFICATE_BASE64`: Base64-encoded Apple Distribution `.p12` certificate.
- `APPLE_DISTRIBUTION_CERTIFICATE_PASSWORD`: Password for the `.p12` certificate.
- `APPLE_APP_STORE_PROFILE_BASE64`: Base64-encoded App Store provisioning profile for `app.veyq.ios`, named `veyq iOS App Store app.veyq.ios`.
- `APPLE_SHARE_APP_STORE_PROFILE_BASE64`: Base64-encoded App Store provisioning profile for `app.veyq.ios.share`, named `veyq Share App Store app.veyq.ios.share`.
- `IOS_BUILD_KEYCHAIN_PASSWORD`: Temporary CI keychain password.
- `APP_STORE_CONNECT_APP_ID`: Numeric App Store Connect app id for `app.veyq.ios`.
- `TESTFLIGHT_PUBLIC_GROUP_ID`: Optional App Store Connect beta group ID that should receive new builds. Prefer this when available because it avoids name matching.
- `TESTFLIGHT_PUBLIC_GROUP_NAME`: External TestFlight group name that should receive new builds when `TESTFLIGHT_PUBLIC_GROUP_ID` is not configured.

To create the base64 secret values locally:

```bash
base64 -i AuthKey_XXXXXXXXXX.p8 | pbcopy
base64 -i AppleDistribution.p12 | pbcopy
base64 -i "veyq iOS App Store app.veyq.ios.mobileprovision" | pbcopy
base64 -i "veyq Share App Store app.veyq.ios.share.mobileprovision" | pbcopy
```

The workflow runs unit tests, archives the app with the preinstalled signing certificate and provisioning profiles, exports an IPA and uploads it as a GitHub artifact. It intentionally does not allow Xcode to create or update Apple Developer signing assets during archive. Automatic runs upload the IPA to App Store Connect, wait for build processing, set the beta "What to Test" text, add the build to the configured external TestFlight group and submit it for Beta App Review. Manual runs can set the `upload` input to `false` when an archive should be built without publishing to App Store Connect.

iOS app versions are independent from backend `major.build` versions. The repository root `VERSION` file is only the backend and frontend major source. The iOS app major is stored separately in `ios/VERSION`. Automatic TestFlight runs set `CFBundleShortVersionString` to `ios/VERSION`, for example `1`, and set `CFBundleVersion` to the GitHub Actions run number, for example `42`. Manual runs can set `marketing_version` to a full `CFBundleShortVersionString` override. Increment the iOS app major in `ios/VERSION` when the app drops support for a server capability that has been removed from maintained server releases for at least one year.

New TestFlight builds are uploaded and associated with the configured external group even when App Store Connect rejects automatic Beta App Review submission because another build in the same train is already in review. App Store Connect no longer exposes an API operation for canceling Beta App Review submissions, so the workflow reports the active older review and leaves the new build available for a later or manual Beta App Review submission after the older review completes or is handled in App Store Connect.

The copyable App Store Connect source text is maintained in [iOS TestFlight Submission](product/ios-testflight-submission.md).

TestFlight metadata for the first beta:

- App name: `veyq`.
- Primary language: English, with German added once localized App Store metadata exists.
- Bundle ID: `app.veyq.ios`.
- SKU: `veyq-ios`.
- Category: Photo & Video, or Lifestyle if Apple review feedback considers Memory curation broader than media management.
- Beta app description: official native app for connecting to a veyq instance, browsing Memories and uploading photos or videos.
- Beta feedback email: project or operator-owned support address for `veyq.app`.
- Beta review contact: Apple Developer account contact who can answer review questions.
- Demo account: required only when Apple cannot use a normal self-hosted or managed test instance. The test instance should contain non-sensitive sample Memories and should not expose real private user content.
- What to test: instance connection, browser login handoff, tenant selection, Memory navigation, Memory detail media viewing, media upload queue and logout.

Compliance and privacy checklist before the first external TestFlight group:

- App privacy details must be completed in App Store Connect before external testing. Current expected data categories are user account/contact identifiers used for login, user-generated Memory content, photos/videos selected for upload, approximate or precise location only when present in uploaded media metadata, and diagnostics only if crash or analytics collection is added later.
- veyq should declare no third-party advertising, no cross-app tracking and no data broker use.
- The native app stores instance URLs, capability snapshots, tenant/user bootstrap state, upload queue state and native sessions locally. Native sessions are stored in iOS Keychain.
- Uploaded photos and videos are sent only to the user-selected veyq instance. Self-hosted operators, not the official app, control retention, backups and server logs for their instance.
- External services are contacted only through the selected instance APIs, user-opened external playlist links, and optional map tile behavior documented in [`docs/legal/privacy.md`](legal/privacy.md).
- Export compliance should be answered as standard app encryption using Apple platform HTTPS/TLS and Keychain APIs, with no custom cryptography beyond standard platform and server transport security.
- Privacy policy URL should point to the public `veyq.app` privacy page once published. Until then, keep [`docs/legal/privacy.md`](legal/privacy.md) and [`docs/legal/ios-app-privacy-policy.md`](legal/ios-app-privacy-policy.md) as the source text for the policy.

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

Enable collaborative Journal editing by adding `collaboration` to `COMPOSE_PROFILES` and setting the sidecar image plus two random values:

- `VEYQ_COLLABORATION_IMAGE`: collaboration sidecar image, for example `ghcr.io/raddatzk/veyq-collaboration:latest`.
- `JOURNAL_LIVE_TOKEN_SECRET`: signs short-lived browser editor tokens.
- `JOURNAL_LIVE_SIDECAR_SECRET`: authenticates sidecar-to-backend validation and snapshot persistence calls.

The sidecar is routed through `/api/collaboration/journal`; if it is not running, the web Journal editor falls back to REST Markdown saving. Live documents are kept in memory only while active and are evicted after a short idle period.

The app is served through the domain configured by `VEYQ_TRAEFIK_RULE`. The health endpoint is available at `/q/health`.

### Private Alpha Self-Hosting Runbook

Use this path for a first self-hosted veyq instance. It is suitable for a trusted private alpha, not yet for a public managed service.
For expected operational failure cases, diagnostics and recovery steps, see [`operations.md`](operations.md).

The recommended server install path is the helper script:

```bash
curl -fsSL https://raw.githubusercontent.com/raddatzk/veyq/main/scripts/veyq-server.sh | \
  sudo VEYQ_REPO=raddatzk/veyq VEYQ_REF=main bash -s -- install
```

Set `VEYQ_REPO` to the actual GitHub repository that publishes the veyq and veyq-collaboration images and infra files when running a fork. The script downloads `docker-compose.yml`, `.env.template` and reset-sidecar files into `/opt/veyq`, optionally prompts for common settings, pulls the configured images for enabled profiles and starts the stack.

Manual install:

1. Copy `infra/.env.template` to `infra/.env`.
2. Set `VEYQ_IMAGE` to the published image, for example `ghcr.io/raddatzk/veyq:latest`.
3. Set unique values for `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `MAIL_PASSWORD` and, if enabled, `JOURNAL_LIVE_TOKEN_SECRET`, `JOURNAL_LIVE_SIDECAR_SECRET` or `RESET_PASSWORD`.
4. Set `VEYQ_TRAEFIK_RULE` to the public host, for example ``Host(`veyq.example.com`)``.
5. Set `VEYQ_SOURCE_URL` when running a fork or modified public deployment so AGPL source links point to the matching source.
6. Configure SMTP through the `MAIL_*` variables before inviting users.
7. Start with `docker compose up -d` from `infra/`.
8. Open the configured host, create the first admin and create the first tenant.

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

The helper script keeps `.env` compatible across additive builds in the same major line. During `upgrade` and `auto-upgrade`, it regenerates `.env` from the current `.env.template`, restores previously configured values, preserves custom keys at the end of the file, adds safe defaults where available and records `VEYQ_ENV_SCHEMA_VERSION`. If a new build needs a secret or operator decision that has no safe default, `upgrade` stops before pulling or restarting containers and offers interactive setup when a terminal is available. `auto-upgrade` never prompts; it stops before pull/restart and reports the failure to ServerOwner users when updater notifications are configured.

Within a major image channel, new environment variables must either be optional with a safe default or be handled by a migration that needs no operator decision. A new required variable without a safe default is a breaking operational change and must ship in a new major channel.

Manual upgrade:

1. Back up `.env`, the database and media storage.
2. Pull the configured image with `docker compose pull`.
3. Restart with `docker compose up -d`.
4. Check `docker compose ps` and `docker compose logs veyq`.

### Optional Auto-Updates

veyq images are tagged as `<major>.<buildNumber>`, where `major` comes from the repository `VERSION` file and `buildNumber` is computed from the highest already published GHCR tag in that major line. The major tag, for example `ghcr.io/raddatzk/veyq:1`, moves to the newest compatible build in that major line.

When a new major is published, the previous `release/<major>.x` branch receives security fixes and critical bug fixes for one year after the first next-major build is published. Critical bugs are issues that can cause data loss, broken installs, broken upgrades or unusable self-hosted operation. After that maintenance window, operators should upgrade to a maintained major line.

To enable automatic compatible updates, set `VEYQ_IMAGE` to the major tag, add `updater` to `COMPOSE_PROFILES`, set `VEYQ_AUTO_UPDATE_ENABLED=true` and provide `VEYQ_UPDATER_NOTIFY_TOKEN`. The setup script can generate the token. The updater sidecar is not exposed through Traefik and checks for updates every `VEYQ_AUTO_UPDATE_INTERVAL_HOURS` with random jitter.

The auto-updater uses the `VEYQ_IMAGE` digest as the update trigger. It reconciles safe `.env` defaults, runs `doctor`, compares the remote and local image digests, creates a backup, pulls the new app image, also pulls `veyq-collaboration` when the `collaboration` profile is enabled, restarts the affected containers and waits for the veyq health check. Successful updates are written to the server update log; failed updates create a ServerOwner notification. The sidecar installs its tools as root, then runs the helper as the owner of `.env` so additive `.env` updates, logs and backups do not become root-owned. If an older sidecar already left `.env` owned by root, the new sidecar repairs it to the install-directory owner on startup when the install directory itself is not root-owned.

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
- Video playback is original-only in major line 1. veyq does not create lower-resolution variants, H264 fallback files or HLS/DASH manifests.
- The Journal can be saved and reloaded.
- A child Memory can be created and opened.
- Media maps show geotagged uploads without loading OpenStreetMap tiles before browser opt-in.
- GPX tracks can be uploaded, labeled with an activity type and downloaded again.
- Playlist links from supported providers render as compact cards and open externally.
- Viewer, Contributor, Manager and Uploader shares can be opened from a fresh browser session.
- Public upload links accept media without exposing the gallery.
- ZIP downloads work for direct Memory media and for media including child Memories.

### Controlled k6 Load Tests

The repository includes a small k6 suite for controlled capacity checks against a non-production or disposable veyq instance. It is meant for application-level measurement, not for network saturation or DDoS-style stress testing.

Prepare confirmed test users first. The suite does not create users because veyq invitations are intentionally email-token based. Copy the example user pool and fill in users that already accepted their invitation and have passwords:

```bash
cp load/k6/users.local.example.json load/k6/users.local.json
chmod 600 load/k6/users.local.json
```

Run the smoke profile:

```bash
VEYQ_BASE_URL=https://veyq.example.com \
VEYQ_TENANT=friends \
VEYQ_USERS_FILE=load/k6/users.local.json \
k6 run load/k6/veyq-core.js
```

Available profiles:

- `smoke`: short functional check with private browse, journal save, media upload, public share read, public uploader upload and cleanup.
- `capacity-step`: gradual ramp through 1, 3, 5, 10, 20 and up to `VEYQ_VUS_MAX` virtual users.
- `upload-focused`: small number of users with heavier resumable media upload pressure.

Set the profile with `VEYQ_PROFILE`, for example:

```bash
VEYQ_PROFILE=capacity-step VEYQ_VUS_MAX=30 \
VEYQ_BASE_URL=https://veyq.example.com \
VEYQ_TENANT=friends \
VEYQ_USERS_FILE=load/k6/users.local.json \
k6 run load/k6/veyq-core.js
```

Each run creates a root Memory named `k6-run-<runId>` with child Memories, shares, journals and small upload fixtures. By default, the suite deletes that root Memory in `teardown()`. Use `VEYQ_KEEP_TEST_DATA=true` only when you want to inspect generated data manually after a failed or exploratory run. If cleanup fails, the run id and root Memory id are printed so the test data can be removed from the UI.

While testing small VPS instances, keep host monitoring open (`docker stats`, RAM/swap, disk usage and I/O). Stop the run when p95 API latency stays above a few seconds, 5xx responses appear, containers restart, swap grows continuously or disk I/O stays saturated.

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

Optional passkey setting for Compose:

- `WEBAUTHN_RELYING_PARTY_NAME`: display name shown by authenticators. Defaults to `veyq`. Passkeys are bound to the current tenant domain.
- `PROXY_ADDRESS_FORWARDING`, `PROXY_ALLOW_X_FORWARDED`, `PROXY_ENABLE_FORWARDED_HOST`: enabled by default in Compose so WebAuthn sees the public HTTPS host behind Traefik.

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
