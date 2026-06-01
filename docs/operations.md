# veyq Operations Runbook

This document is for operators of self-hosted veyq instances. It covers expected failure cases for the major line 1 deployment path: Docker Compose, Traefik, MariaDB and local filesystem media storage.

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

Likely cause: `.env.template` values such as `change-me`, `change-root-me`, `change-mail-me` or `change-reset-me` were not replaced.

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

Prevention: configure the final public host before first setup. Passkeys are bound to the tenant domain where they are created, so changing or adding domains requires users to register passkeys for the new domain.

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

Prevention: back up the `traefik-letsencrypt` volume with the database and media storage before upgrades or host changes.

## Passkeys

### Multi-domain passkey behavior

Passkeys are bound to the browser origin and relying-party ID where they are created. For veyq tenants this means a passkey created on `memories.example.com` cannot be used directly on `memories.other.example`, even when both domains point to the same veyq deployment and the same user account.

veyq handles initial access on another tenant domain with a narrow link flow:

1. The target domain shows local passkey login and other eligible tenant domains.
2. The user chooses an existing domain, for example `memories.example.com`, and authenticates there.
3. The authenticated domain creates a short-lived one-time link code for the target domain.
4. The browser carries that code back to the target domain in the URL fragment.
5. The target domain exchanges the code for a short-lived grant and can only use that grant to create one passkey for the target domain.
6. After the new target-domain passkey is registered, veyq sets normal auth cookies for the target domain.

The link code and grant are not normal session credentials. They cannot read memories, change settings, create passwords, administer tenants or call general protected APIs. The code is single-use, the grant is bound to the target domain, and veyq rejects the flow when the user is not a member of the target tenant or already has a passkey for that target domain.

Passkey reset is separate from cross-domain linking. A user can request a passkey reset email on a tenant domain. The reset token is bound to that domain, creates a new passkey only for that domain, replaces existing passkeys for that same domain and then signs the user in normally.

### Passkey registration or login fails

Symptom: the browser refuses passkey registration/login, shows a WebAuthn origin error, or a previously working passkey stops working after a domain change.

Likely cause: the requested host is not configured as a tenant domain, forwarded host/proto headers are not reaching veyq correctly, or the deployment is served over plain HTTP outside localhost.

Inspect:

```bash
cd /opt/veyq
sudo grep -E '^(VEYQ_TRAEFIK_RULE|WEBAUTHN_RELYING_PARTY_NAME)=' .env
sudo docker compose logs --tail=200 veyq
```

Safe recovery: confirm the browser host is one of the configured tenant domains, confirm Traefik forwards `X-Forwarded-Host` and `X-Forwarded-Proto`, keep `PROXY_ADDRESS_FORWARDING`, `PROXY_ALLOW_X_FORWARDED` and `PROXY_ENABLE_FORWARDED_HOST` enabled for Compose deployments, and restart veyq with `sudo docker compose up -d veyq`.

Prevention: tell users to add a passkey on each tenant domain they use. Localhost development is the only expected HTTP exception.

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

Prevention: always run backup before upgrade, pin exact build tags for real instances, and verify the upgrade path on a test instance when possible.

## Media Storage

### Uploads fail or media disappears after restart

Symptom: uploads return errors, previews are missing, or previously uploaded media is gone after a container restart.

Likely cause: the configured media storage source is missing, `MEDIA_STORAGE_DIR` changed, a host path or CIFS mount has permission problems, or the reset sidecar cleared a disposable instance.

Inspect:

```bash
cd /opt/veyq
sudo docker compose ps
sudo grep -E '^(MEDIA_STORAGE_MODE|MEDIA_STORAGE_MOUNT_TYPE|MEDIA_STORAGE_SOURCE|MEDIA_STORAGE_DIR|MEDIA_CIFS_DEVICE)=' .env
sudo docker volume ls | grep veyq-media || true
sudo docker compose exec veyq sh -lc 'echo "$MEDIA_STORAGE_DIR"; ls -la "$MEDIA_STORAGE_DIR" | head'
sudo docker compose logs --tail=200 veyq
```

Safe recovery: restore the media storage from backup that matches the database backup. If only `MEDIA_STORAGE_DIR` or the mount source changed, return it to the previous value and restart before writing new media.

Prevention: include the configured media storage in backups. For `docker-volume`, back up `infra_veyq-media`; for `cifs-volume`, back up `infra_veyq-media-cifs`; for `host-path`, back up `MEDIA_STORAGE_SOURCE`. Do not enable the reset sidecar for any environment that should preserve data.

### Disk full or permission errors

Symptom: uploads fail, backups fail, or logs mention no space left on device or permission denied.

Likely cause: the Docker host disk is full, the backup directory is full, a host-mounted media path has incorrect ownership, or the CIFS share is unavailable or mounted with incompatible options.

Inspect:

```bash
df -h
docker system df
cd /opt/veyq
sudo docker compose logs --tail=200 veyq
sudo du -sh backups 2>/dev/null || true
```

Safe recovery: free host disk space, move old backups off the server, then retry the failed operation. For host-mounted storage, fix host path ownership and restart veyq. For Docker-managed CIFS, verify `MEDIA_CIFS_DEVICE`, credentials and mount options in `.env`, then recreate the affected container.

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

Safe recovery: confirm the same media exists in the configured media storage and re-upload the asset if the original is missing or truncated. If an additional upstream proxy is used, verify that it passes `Range` headers and partial-content responses.

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

Likely cause: `DB_ROOT_PASSWORD` is missing or wrong, MariaDB is unavailable, Docker cannot mount the configured media storage, the host path is missing, CIFS credentials are wrong, or the host disk is full.

Inspect:

```bash
cd /opt/veyq
sudo ./veyq-server.sh doctor
sudo docker compose ps
sudo docker compose logs --tail=200 veyq-db
sudo grep -E '^(MEDIA_STORAGE_MODE|MEDIA_STORAGE_SOURCE|MEDIA_CIFS_DEVICE)=' .env
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

Prevention: run `doctor`, then `backup`, then `upgrade`. Prefer exact build tags over moving tags for real instances. The helper script automatically adds safe defaults for new optional `.env` values during upgrade, but any new required secret or operator decision without a safe default must be handled manually and belongs in a new major channel.

### Auto-update fails

Symptom: ServerOwner users see an automatic update failure in server settings, or `logs/auto-upgrade.log` reports a failed stage.

Likely cause: the updater could not reconcile or validate configuration, check the registry digest, create a backup, pull the image, restart veyq or observe a healthy container after restart.

If `logs/auto-upgrade.log` or a manual command reports `Cannot read /opt/veyq/.env`, `Cannot write /opt/veyq/.env` or `Permission denied`, the updater may have run an older helper script that replaced `.env` from inside the root-owned sidecar container.

Inspect:

```bash
cd /opt/veyq
sudo docker compose logs --tail=200 veyq-updater
sudo docker compose logs --tail=300 veyq
tail -n 200 logs/auto-upgrade.log
sudo docker compose images
ls -lah backups | tail
```

Safe recovery: inspect the failed stage first. If the app is unhealthy after a new image started, preserve logs and use the matching pre-update backup artifacts. Do not reset the database. If needed, set `VEYQ_IMAGE` to the previous exact `<major>.<buildNumber>` or `sha-*` tag and run `sudo docker compose up -d veyq`.

The current updater sidecar repairs a root-owned `.env` to the install-directory owner on startup when the install directory itself is not root-owned. To repair manually, keep restrictive permissions, then rerun the command with the same user that owns the install directory:

```bash
cd /opt/veyq
sudo chown "$(stat -c '%U:%G' .)" .env
chmod 600 .env
./veyq-server.sh doctor
```

Prevention: use a major-channel image such as `ghcr.io/raddatzk/veyq:1`, keep enough disk space for one database dump plus media archive, and leave `VEYQ_AUTO_UPDATE_INTERVAL_HOURS` at one hour or higher. Major-channel builds must not introduce new required `.env` decisions without a safe default; such changes require a new major version and a manual upgrade.

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

If the reset action was already triggered, restore the database and media storage from backup.

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
