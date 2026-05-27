# veyq Operations Runbook

This document is for operators of self-hosted veyq instances. It covers expected failure cases for the V1 private-alpha deployment path: Docker Compose, Traefik, MariaDB and local filesystem media storage.

Use [`docs/development.md`](development.md) for local development setup. Use this runbook when an installed server does not start, route, upgrade, back up, send mail, accept uploads or generate media previews as expected.

## Baseline Checks

Run these checks before debugging a specific symptom:

```bash
cd /opt/veyq
sudo ./veyq-server.sh doctor
sudo ./veyq-server.sh status
sudo docker compose ps
sudo docker compose logs --tail=200 veyq
sudo docker compose logs --tail=200 traefik
```

The application health endpoint is `/q/health`; the container health check uses `/q/health/ready`.

`doctor` validates required `.env` values, default secrets and the Compose configuration. It does not contact DNS, Let's Encrypt, SMTP, the database or the public application URL.

## Install And Setup

### Missing `.env` or Compose files

Symptom: `./veyq-server.sh doctor`, `backup` or `upgrade` prints `Missing /opt/veyq/.env. Run install first.` or `Missing /opt/veyq/docker-compose.yml. Run install first.`

Likely cause: the install step did not finish, the command is running from the wrong `VEYQ_DIR`, or files were removed from `/opt/veyq`.

Inspect:

```bash
cd /opt/veyq
ls -la
sudo ./veyq-server.sh status
```

Safe recovery: rerun the install command with the same `VEYQ_REPO`, `VEYQ_REF` and `VEYQ_DIR`. If an existing `.env` is present, the script keeps it. If `.env` is missing but the instance has real data, restore `.env` from backup before starting services.

Prevention: keep `.env` in every backup set and use the helper script from `/opt/veyq` for install, backup and upgrade operations.

### Default or missing secrets

Symptom: `doctor` reports `Missing required .env value` or `Replace default secret in .env`.

Likely cause: `.env.example` values such as `change-me`, `change-root-me`, `change-mail-me` or `change-reset-me` were not replaced.

Inspect:

```bash
cd /opt/veyq
sudo ./veyq-server.sh doctor
sudo grep -E '^(DB_PASSWORD|DB_ROOT_PASSWORD|MAIL_PASSWORD|RESET_PASSWORD)=' .env | sed 's/=.*/=<redacted>/'
```

Safe recovery: run `sudo ./veyq-server.sh setup` or edit `.env` manually. For an already initialized MariaDB volume, changing `DB_PASSWORD` or `DB_ROOT_PASSWORD` in `.env` does not automatically change database users inside MariaDB; restore the original `.env` value or rotate the database password inside MariaDB before updating `.env`.

Prevention: run `doctor` after every `.env` edit and before every upgrade.

### Invalid Traefik rule or image name

Symptom: `docker compose config` fails, Traefik does not create the route, or `docker compose pull` cannot pull the veyq image.

Likely cause: `VEYQ_TRAEFIK_RULE` is not valid Traefik syntax, shell quotes were copied incorrectly, or `VEYQ_IMAGE` points to a non-existent or private image.

Inspect:

```bash
cd /opt/veyq
sudo docker compose config
sudo docker compose pull veyq
sudo docker compose logs --tail=200 traefik
```

Safe recovery: set `VEYQ_TRAEFIK_RULE` to a valid host rule such as ``Host(`veyq.example.com`)`` and set `VEYQ_IMAGE` to a published image, for example `ghcr.io/raddatzk/veyq:<tag>`. Then run:

```bash
sudo ./veyq-server.sh doctor
sudo docker compose pull veyq
sudo docker compose up -d
```

Prevention: prefer `sudo ./veyq-server.sh setup` for common values because it writes the Traefik and passkey host settings together.

## TLS And Routing

### Public host does not reach veyq

Symptom: the browser shows a 404, a Traefik page, a connection timeout, or another service.

Likely cause: DNS does not point to the server, ports `80` or `443` are blocked, `VEYQ_TRAEFIK_RULE` does not match the requested host, or another process owns the ports.

Inspect:

```bash
cd /opt/veyq
sudo docker compose ps
sudo docker compose logs --tail=200 traefik
sudo docker compose logs --tail=200 veyq
curl -I http://veyq.example.com
curl -Ik https://veyq.example.com/q/health
```

Safe recovery: fix DNS, firewall rules, host routing and `VEYQ_TRAEFIK_RULE`, then restart Traefik and veyq with `sudo docker compose up -d`.

Prevention: configure the final public host before first setup. Keep `VEYQ_TRAEFIK_RULE`, `WEBAUTHN_ORIGINS` and `WEBAUTHN_RP_ID` aligned to the same host unless there is an intentional multi-host setup.

### Certificate issuance fails

Symptom: HTTPS shows an invalid certificate, Traefik serves a default certificate, or Traefik logs ACME errors.

Likely cause: Let's Encrypt cannot reach port `80`, `LETSENCRYPT_EMAIL` is missing or invalid, DNS is wrong, or the ACME volume contains stale state for a changed host.

Inspect:

```bash
cd /opt/veyq
sudo docker compose logs --tail=300 traefik
sudo docker volume ls | grep traefik-letsencrypt
curl -I http://veyq.example.com/.well-known/acme-challenge/test
```

Safe recovery: fix DNS and inbound port `80`, confirm `LETSENCRYPT_EMAIL`, then restart Traefik. Do not delete the `traefik-letsencrypt` volume unless you understand the Let's Encrypt rate-limit impact and have backed it up.

Prevention: back up the `traefik-letsencrypt` volume with the database and media volumes before upgrades or host changes.

## Passkeys

### Passkey registration or login fails

Symptom: the browser refuses passkey registration/login, shows a WebAuthn origin error, or a previously working passkey stops working after a domain change.

Likely cause: `WEBAUTHN_ORIGINS` does not exactly include the HTTPS origin used in the browser, `WEBAUTHN_RP_ID` does not match the effective domain, or the deployment is served over plain HTTP outside localhost.

Inspect:

```bash
cd /opt/veyq
sudo grep -E '^(WEBAUTHN_ORIGINS|WEBAUTHN_RP_ID|VEYQ_TRAEFIK_RULE)=' .env
sudo docker compose logs --tail=200 veyq
```

Safe recovery: set `WEBAUTHN_ORIGINS` to the browser origin, for example `https://veyq.example.com`, and set `WEBAUTHN_RP_ID` to the host without protocol, for example `veyq.example.com`. Restart veyq with `sudo docker compose up -d veyq`.

Prevention: do not change the public host after users create passkeys unless the passkey migration impact is accepted. Localhost development is the only expected HTTP exception.

## Database

### veyq is unhealthy or cannot connect to MariaDB

Symptom: `veyq` is restarting, the health check stays unhealthy, or logs show database connection failures.

Likely cause: `veyq-db` is not running, database credentials differ from the initialized MariaDB volume, the database is still starting, or the DB volume is damaged.

Inspect:

```bash
cd /opt/veyq
sudo docker compose ps
sudo docker compose logs --tail=200 veyq-db
sudo docker compose logs --tail=200 veyq
sudo docker compose exec veyq-db sh -lc 'mariadb-admin ping -u root -p"$MARIADB_ROOT_PASSWORD"'
```

Safe recovery: if the credentials were changed only in `.env`, restore the values that match the existing MariaDB volume. If MariaDB is damaged, stop the stack and restore the `veyq-db` volume and `.env` from the same backup set.

Prevention: back up `.env` and the database volume together. Run `sudo ./veyq-server.sh backup` before upgrades or destructive tests.

### Migration or startup fails after upgrade

Symptom: veyq starts, fails during Flyway or Hibernate initialization, then exits or stays unhealthy.

Likely cause: the new image expects a schema migration that failed, the wrong image tag was pulled, or the database is not the expected veyq database.

Inspect:

```bash
cd /opt/veyq
sudo docker compose logs --tail=300 veyq
sudo docker compose exec veyq-db sh -lc 'mariadb -u root -p"$MARIADB_ROOT_PASSWORD" "${MARIADB_DATABASE:-veyq}" -e "select installed_rank, version, description, success from flyway_schema_history order by installed_rank desc limit 10;"'
```

Safe recovery: do not reset the database. Preserve logs, restore the previous image tag and the pre-upgrade database/media backup if needed, then retry the upgrade after identifying the failing migration.

Prevention: always run backup before upgrade, pin release tags for real instances, and verify the upgrade path on a test instance when possible.

## Media Storage

### Uploads fail or media disappears after restart

Symptom: uploads return errors, previews are missing, or previously uploaded media is gone after a container restart.

Likely cause: the `veyq-media` volume is missing, `MEDIA_STORAGE_DIR` changed, the mounted directory has permission problems, or the reset sidecar cleared a disposable instance.

Inspect:

```bash
cd /opt/veyq
sudo docker compose ps
sudo docker volume ls | grep veyq-media
sudo docker compose exec veyq sh -lc 'echo "$MEDIA_STORAGE_DIR"; ls -la "$MEDIA_STORAGE_DIR" | head'
sudo docker compose logs --tail=200 veyq
```

Safe recovery: restore the media volume from backup that matches the database backup. If only `MEDIA_STORAGE_DIR` changed, return it to the previous value and restart before writing new media.

Prevention: include `veyq-media` in backups. Do not enable the reset sidecar for any environment that should preserve data.

### Disk full or permission errors

Symptom: uploads fail, backups fail, or logs mention no space left on device or permission denied.

Likely cause: the Docker host disk is full, the backup directory is full, or a host-mounted media path has incorrect ownership.

Inspect:

```bash
df -h
docker system df
cd /opt/veyq
sudo docker compose logs --tail=200 veyq
sudo du -sh backups 2>/dev/null || true
```

Safe recovery: free host disk space, move old backups off the server, then retry the failed operation. For host-mounted storage, fix host path ownership and restart veyq.

Prevention: monitor host disk usage and backup growth. Keep enough free space for at least one database dump and one compressed media archive.

## Uploads And Playback

### Upload rejected as too large

Symptom: the frontend reports that the upload is too large, or the API returns `UPLOAD_TOO_LARGE`.

Likely cause: the request body exceeds `MEDIA_MAX_UPLOAD_SIZE`, or `MEDIA_MAX_UPLOAD_SIZE` was configured lower than `MEDIA_CHUNK_SIZE`.

Inspect:

```bash
cd /opt/veyq
sudo grep -E '^(MEDIA_CHUNK_SIZE|MEDIA_MAX_UPLOAD_SIZE)=' .env
sudo docker compose logs --tail=200 veyq
```

Safe recovery: keep `MEDIA_MAX_UPLOAD_SIZE` higher than `MEDIA_CHUNK_SIZE`, then restart veyq. The default pairing is `MEDIA_CHUNK_SIZE=16M` and `MEDIA_MAX_UPLOAD_SIZE=32M`.

Prevention: change upload sizing only intentionally and test a large upload after each change.

### Interrupted resumable uploads do not complete

Symptom: an upload remains unfinished, retrying does not complete it, or the frontend shows a failed upload after a network interruption.

Likely cause: upload chunks were interrupted, the upload session expired, or the session cleanup job removed old unfinished state.

Inspect:

```bash
cd /opt/veyq
sudo grep -E '^(MEDIA_UPLOAD_SESSION_TTL_HOURS|MEDIA_UPLOAD_SESSION_CLEANUP_CRON)=' .env
sudo docker compose logs --tail=200 veyq
```

Safe recovery: retry the upload from the frontend. If the session expired, start a new upload. Do not manually edit upload-session database rows on a real instance.

Prevention: keep the default session TTL unless there is a concrete operational need to shorten it.

### Browser video playback fails or seeking breaks

Symptom: original videos do not play, seeking fails, or the frontend reports `RANGE_NOT_SATISFIABLE`.

Likely cause: the requested media file is missing or truncated, the browser requested an invalid byte range, or a proxy in front of Traefik interferes with range requests.

Inspect:

```bash
cd /opt/veyq
sudo docker compose logs --tail=200 veyq
curl -Ik -H 'Range: bytes=0-1023' 'https://veyq.example.com/<affected-media-url>'
```

Safe recovery: confirm the same media exists in the media volume and re-upload the asset if the original is missing or truncated. If an additional upstream proxy is used, verify that it passes `Range` headers and partial-content responses.

Prevention: keep the supported deployment path simple: browser to Traefik to veyq. Back up database and media together so media records and files stay consistent.

## Media Previews

### Video posters are not generated

Symptom: uploaded videos work as originals, but poster previews are missing or stay as placeholders.

Likely cause: `ffmpeg` is missing from the image, `FFMPEG_PATH` is wrong, the video format cannot be probed, or preview generation times out.

Inspect:

```bash
cd /opt/veyq
sudo docker compose exec veyq sh -lc 'command -v "${FFMPEG_PATH:-ffmpeg}" && "${FFMPEG_PATH:-ffmpeg}" -version | head -n 1'
sudo grep -E '^(FFMPEG_PATH|MEDIA_PREVIEW_TIMEOUT_SECONDS)=' .env
sudo docker compose logs --tail=300 veyq
```

Safe recovery: use the official runtime image or set `FFMPEG_PATH` to a valid executable inside the container. If a specific large file times out, increase `MEDIA_PREVIEW_TIMEOUT_SECONDS` and restart veyq.

Prevention: verify the private-alpha smoke flow after install and upgrade: upload a video and confirm its poster preview appears.

## Mail

### Invitations or password resets do not arrive

Symptom: tenant invites, email confirmations or password resets are created but no mail arrives.

Likely cause: `MAIL_*` settings are placeholders, SMTP credentials are wrong, TLS/login mode does not match the provider, or the provider rejects the sender.

Inspect:

```bash
cd /opt/veyq
sudo grep -E '^(MAIL_FROM|MAIL_HOST|MAIL_PORT|MAIL_USERNAME|MAIL_LOGIN|MAIL_TLS)=' .env
sudo ./veyq-server.sh doctor
sudo docker compose logs --tail=300 veyq
```

Safe recovery: correct SMTP settings and restart veyq. Existing invitation and reset tokens remain time-limited; resend invitations or request a fresh password reset when needed.

Prevention: configure SMTP before inviting users and include mail delivery in the private-alpha smoke flow.

## Backups And Upgrades

### Backup fails

Symptom: `./veyq-server.sh backup` exits with an error, no `.sql` file is written, or the media archive fails.

Likely cause: `DB_ROOT_PASSWORD` is missing or wrong, MariaDB is unavailable, Docker cannot mount the media volume, or the host disk is full.

Inspect:

```bash
cd /opt/veyq
sudo ./veyq-server.sh doctor
sudo docker compose ps
sudo docker compose logs --tail=200 veyq-db
df -h
ls -lah backups 2>/dev/null || true
```

Safe recovery: fix the specific cause and rerun backup. A valid backup set should include `.env`, a database `.sql` dump and a `-media.tgz` archive from the same point in time.

Prevention: run backups before upgrades and periodically test that backup artifacts can be copied off the host.

### Upgrade fails or new image is unhealthy

Symptom: `upgrade` fails during pull/start, veyq remains unhealthy, or the public site breaks after upgrade.

Likely cause: image pull failure, incompatible `.env`, migration failure, or a runtime regression in the target image.

Inspect:

```bash
cd /opt/veyq
sudo docker compose ps
sudo docker compose logs --tail=300 veyq
sudo docker compose images
ls -lah backups | tail
```

Safe recovery: keep the failed instance stopped if it may be writing bad state. Restore the pre-upgrade `.env`, database and media backup together, and set `VEYQ_IMAGE` back to the previous working tag before `sudo docker compose up -d`.

Prevention: run `doctor`, then `backup`, then `upgrade`. Prefer release tags over moving tags for real instances.

## Reset Sidecar

### Reset sidecar is accidentally enabled

Symptom: a reset host is reachable, or `veyq-reset` appears in `docker compose ps` on an instance that should preserve data.

Likely cause: `.env` contains `COMPOSE_PROFILES=reset`.

Inspect:

```bash
cd /opt/veyq
sudo grep -E '^(COMPOSE_PROFILES|RESET_TRAEFIK_RULE|RESET_USERNAME)=' .env
sudo docker compose ps
```

Safe recovery: remove `COMPOSE_PROFILES=reset` from `.env` and run:

```bash
sudo docker compose up -d
sudo docker stop veyq-reset
```

If the reset action was already triggered, restore the database and media volume from backup.

Prevention: enable reset only for disposable development or test instances. Treat access to the reset UI as destructive admin access.

### Reset fails to restart veyq

Symptom: the reset UI reports an internal error, or data is cleared but the app does not restart.

Likely cause: the Docker socket is unavailable to the reset container, `RESET_RESTART_CONTAINER` names the wrong container, or Docker rejected the restart request.

Inspect:

```bash
cd /opt/veyq
sudo docker compose logs --tail=300 veyq-reset
sudo grep -E '^(RESET_RESTART_CONTAINER|MEDIA_STORAGE_DIR)=' .env
sudo docker ps --format '{{.Names}}'
```

Safe recovery: restart veyq manually with `sudo docker compose restart veyq`. On a disposable test instance, rerun the setup flow after the app starts.

Prevention: leave `RESET_RESTART_CONTAINER` unset unless the app container name is intentionally changed.
