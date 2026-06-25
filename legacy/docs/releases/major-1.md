# veyq Major Line 1 Notes

Status: draft for the first self-hostable major line. Major versions are compatibility lines; increment the major only for breaking API, storage, deployment or operator-contract changes.

Maintenance: after the first major line 2 build is published, `release/1.x` receives security fixes and critical bug fixes for one year. Critical bugs are issues that can cause data loss, broken installs, broken upgrades or unusable self-hosted operation. Operators should move to a maintained major line before that window expires.

## Highlights

- Memory-first product baseline with tenant, group and personal spaces.
- Nested Memories for journeys, events and smaller moments.
- Photo and video uploads with resumable chunk sessions, progress tracking and cleanup support.
- Original media storage with generated image previews and video posters.
- Original-only browser video playback with HTTP range request support.
- Memory covers, favorites, date ranges, metadata display and timestamp correction.
- Markdown journals with veyq embeds for visible media, child Memories, maps, playlists and tracks.
- Media map for photos and videos with GPS metadata. OpenStreetMap tiles require explicit browser opt-in and can be disabled again from the map, public share pages or settings.
- GPX track upload, activity type labeling, listing, download and journal track embeds.
- Memory playlists with one Spotify, Apple Music, YouTube, SoundCloud or Deezer provider link, external opening, compact management cards and journal embeds.
- ZIP downloads for direct Memory media and visible descendant media.
- Public Memory shares with Viewer, Contributor, Manager and Uploader roles and optional 10-character share passwords.
- Uploader-only public links that accept contributions without exposing the gallery or other uploads.
- Docker Compose self-hosting with Traefik, MariaDB, local filesystem media storage and image-based upgrades.
- Server helper script for install, setup, status, backup and upgrade workflows.
- GitHub Actions container workflow for publishing veyq images to GitHub Container Registry.
- AGPL-3.0-only community edition licensing with separate veyq trademark policy.

## Upgrade Notes

- Self-hosted deployments should use the image-based Compose file and set `VEYQ_IMAGE` to either an exact build tag, for example `1.123`, or a compatible major-channel tag, for example `1`.
- Local source builds now use `infra/docker-compose.build.yml` as an override. The image-based deployment keeps MariaDB internal to the Compose network; the build override publishes MariaDB on host port `3306` for local development.
- Official runtime images include `ffmpeg`; major line 1 uses it only for poster/preview generation, not video transcoding.
- Before upgrading, create both a MariaDB dump and a media volume archive:

```bash
./veyq-server.sh doctor
./veyq-server.sh backup
./veyq-server.sh upgrade
```

Manual deployments can follow the same sequence with `docker compose pull` and `docker compose up -d` after backing up `.env`, the database and the media volume.

## Known Major Line 1 Limits

- Video delivery is original-only. There are no lower-resolution variants, H264 fallback files, HLS/DASH streams or adaptive network-quality options in major line 1.
- Maps and tracks are intentionally lightweight in major line 1: media maps use GPS metadata and optional OpenStreetMap tiles, while GPX tracks live in a separate Tracks tab. Combined map/track views, route styling and advanced location models are deferred.
- Weather, playlist metadata caching, automatic playlist matching and person models beyond tenant-user Memory participants are not first-class major line 1 features.
- Face recognition, semantic search, classification and cross-Memory person graphs are deferred.
- Native mobile apps may exist as experimental/pre-release clients, but major line 1 does not include app-store distribution, push notifications, federation, remote share following or offline sync as supported release scope.
- No comments, reactions, manual asset sorting or cross-Memory asset moves.
- No demo seed Memories. First-run guidance relies on clear empty states.

## Build Readiness Checklist

- Confirm `VEYQ_REPO=raddatzk/veyq`, `VEYQ_REF=main` and `VEYQ_IMAGE=ghcr.io/raddatzk/veyq:<major>.<buildNumber>` for a pinned public build.
- Confirm public build artifacts include `LICENSE`, `NOTICE`, `docs/legal/trademark-policy.md` and `docs/legal/commercial-boundary.md`.
- Run backend checks from `backend`: `./gradlew --no-daemon check`.
- Run frontend checks from `frontend`: `npm run check`.
- Run the major-line smoke tests from `frontend`: `npm run e2e -- major-1-smoke.spec.ts`.
- Confirm the smoke test covers first empty states, public viewer shares, uploader-only shares, media maps, GPX tracks and playlist links.
- Run `scripts/veyq-server.sh doctor` against a prepared server `.env`.
- Run at least one fresh server smoke test with `scripts/veyq-server.sh install`.
- Run one backup/upgrade cycle and confirm it writes `.env`, database and media archive artifacts.
- Verify CI checks through `.github/workflows/ci.yml`.
- Verify GHCR publishing through `.github/workflows/container.yml`.
- Publish a build only after the image, server install, upload flow, public share flow and upgrade path have been verified.
