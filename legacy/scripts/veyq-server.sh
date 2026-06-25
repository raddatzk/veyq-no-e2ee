#!/usr/bin/env bash
set -euo pipefail

VEYQ_DIR="${VEYQ_DIR:-$PWD}"
VEYQ_REPO="${VEYQ_REPO:-raddatzk/veyq}"
VEYQ_REF="${VEYQ_REF:-main}"
RAW_BASE="${RAW_BASE:-https://raw.githubusercontent.com/${VEYQ_REPO}/${VEYQ_REF}}"

COMPOSE_FILES=(-f docker-compose.yml)
VEYQ_ENV_SCHEMA_VERSION="4"
REQUIRED_ENV_KEYS=(VEYQ_IMAGE LETSENCRYPT_EMAIL VEYQ_TRAEFIK_RULE DB_PASSWORD DB_ROOT_PASSWORD MAIL_FROM MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_PASSWORD)
SECRET_ENV_KEYS=(DB_PASSWORD DB_ROOT_PASSWORD MAIL_PASSWORD WEBAUTHN_SESSION_ENCRYPTION_KEY)
DEFAULTABLE_ENV_KEYS=(VEYQ_ENV_SCHEMA_VERSION VEYQ_AUTO_UPDATE_ENABLED VEYQ_AUTO_UPDATE_INTERVAL_HOURS VEYQ_AUTO_UPDATE_JITTER_MINUTES WEBAUTHN_SESSION_ENCRYPTION_KEY)
COLLABORATION_PROFILE_REQUIRED_ENV_KEYS=(VEYQ_COLLABORATION_IMAGE JOURNAL_LIVE_TOKEN_SECRET JOURNAL_LIVE_SIDECAR_SECRET)
COLLABORATION_PROFILE_SECRET_ENV_KEYS=(JOURNAL_LIVE_TOKEN_SECRET JOURNAL_LIVE_SIDECAR_SECRET)
RESET_PROFILE_REQUIRED_ENV_KEYS=(RESET_TRAEFIK_RULE RESET_USERNAME RESET_PASSWORD)
RESET_PROFILE_SECRET_ENV_KEYS=(RESET_PASSWORD)
UPDATER_PROFILE_REQUIRED_ENV_KEYS=(VEYQ_UPDATER_NOTIFY_TOKEN)

usage() {
  cat <<'USAGE'
Usage: veyq-server.sh <command>

Commands:
  install   Download Compose files, create .env and optionally run setup prompts.
  setup     Prompt for common .env values without changing volumes.
  upgrade   Refresh Compose files, pull the configured image and restart veyq.
  auto-upgrade
            Check the configured image and update veyq when auto-updates are enabled.
  status    Show Docker Compose service status.
  doctor    Validate local server configuration without changing services.
  backup    Create a database dump and media archive in ./backups.

Environment:
  VEYQ_DIR   Install directory. Default: current directory
  VEYQ_REPO  GitHub repo to download infra files from. Default: raddatzk/veyq
  VEYQ_REF   Branch, tag or SHA to download infra files from. Default: main
USAGE
}

main() {
  local command="${1:-}"
  case "$command" in
    install) install ;;
    setup) setup_env ;;
    upgrade) upgrade ;;
    upgrade-continue) upgrade_continue ;;
    auto-upgrade) auto_upgrade ;;
    status) compose ps ;;
    doctor) doctor ;;
    backup) backup ;;
    ""|-h|--help|help) usage ;;
    *) echo "Unknown command: $command" >&2; usage; exit 2 ;;
  esac
}

install() {
  require_command docker
  require_compose
  VEYQ_DIR="$(prompt "Install directory" "$VEYQ_DIR")"
  if [ -z "$VEYQ_DIR" ]; then
    echo "Install directory is required." >&2
    exit 1
  fi
  mkdir -p "$VEYQ_DIR"
  cd "$VEYQ_DIR"
  if [ -f .env ]; then
    require_env_readable
    require_env_writable
  fi
  download_infra

  local setup_default="n"
  if [ ! -f .env ]; then
    cp .env.template .env
    chmod 600 .env
    echo "Created $VEYQ_DIR/.env"
    apply_env_defaults
    setup_default="y"
  else
    echo "Keeping existing $VEYQ_DIR/.env"
    apply_env_defaults
    if env_needs_setup; then
      echo "Existing .env still needs setup."
      setup_default="y"
    fi
  fi

  if prompt_yes_no "Run interactive setup now?" "$setup_default"; then
    setup_env
  else
    echo "Edit $VEYQ_DIR/.env before starting veyq."
  fi

  local start_default="y"
  if env_needs_setup; then
    start_default="n"
  fi
  if prompt_yes_no "Pull and start veyq now?" "$start_default"; then
    if env_needs_setup; then
      echo "Cannot start veyq with an incomplete .env. Run ./veyq-server.sh setup first." >&2
      exit 1
    fi
    pull_services
    compose up -d
    wait_for_health
  fi
}

upgrade() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  require_env_readable
  require_env_writable
  mkdir -p backups
  cp docker-compose.yml "backups/docker-compose.$(timestamp).yml" 2>/dev/null || true
  cp .env "backups/env.$(timestamp)" 2>/dev/null || true
  download_infra
  exec env VEYQ_DIR="$VEYQ_DIR" VEYQ_REPO="$VEYQ_REPO" VEYQ_REF="$VEYQ_REF" RAW_BASE="$RAW_BASE" ./veyq-server.sh upgrade-continue
}

upgrade_continue() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  require_env_readable
  require_env_writable
  require_file docker-compose.yml
  ensure_env_ready_for_upgrade interactive
  pull_services
  compose up -d
  wait_for_health
}

download_infra() {
  download "scripts/veyq-server.sh" "veyq-server.sh"
  chmod 755 "veyq-server.sh"
  download "infra/docker-compose.yml" "docker-compose.yml"
  download "infra/.env.template" ".env.template"
  mkdir -p reset
  download "infra/reset/Dockerfile" "reset/Dockerfile"
  download "infra/reset/app.py" "reset/app.py"
  download "infra/reset/requirements.txt" "reset/requirements.txt"
}

setup_env() {
  cd "$VEYQ_DIR"
  require_file .env
  require_env_readable
  require_env_writable

  echo "Configuring $VEYQ_DIR/.env"
  apply_env_defaults
  set_env VEYQ_ENV_SCHEMA_VERSION "$VEYQ_ENV_SCHEMA_VERSION"
  local public_host letsencrypt_email image
  public_host="$(prompt "Public veyq host, without protocol" "$(webauthn_rp_id_default)")"
  letsencrypt_email="$(prompt "Let's Encrypt email" "$(env_value LETSENCRYPT_EMAIL "admin@example.com")")"
  image="$(prompt "veyq image" "$(env_value VEYQ_IMAGE "ghcr.io/${VEYQ_REPO%/*}/veyq:latest")")"

  set_env VEYQ_IMAGE "$image"
  set_env LETSENCRYPT_EMAIL "$letsencrypt_email"
  set_env VEYQ_TRAEFIK_RULE "Host(\`${public_host}\`)"
  set_env WEBAUTHN_RELYING_PARTY_NAME "$(prompt "Passkey display name" "$(env_value WEBAUTHN_RELYING_PARTY_NAME "veyq")")"
  set_env WEBAUTHN_SESSION_ENCRYPTION_KEY "$(prompt_secret_or_random "Passkey session encryption key" "$(env_value WEBAUTHN_SESSION_ENCRYPTION_KEY "")")"
  set_env VEYQ_SOURCE_URL "$(prompt "Source URL for AGPL notices" "$(env_value VEYQ_SOURCE_URL "https://github.com/${VEYQ_REPO}")")"

  set_env DB_PASSWORD "$(prompt_secret_or_random "Database app password" "$(env_value DB_PASSWORD "")")"
  set_env DB_ROOT_PASSWORD "$(prompt_secret_or_random "Database root password" "$(env_value DB_ROOT_PASSWORD "")")"

  setup_media_storage_env

  echo "Mail settings are required for invitations and password resets."
  set_env MAIL_FROM "$(prompt "Mail sender address" "$(env_value MAIL_FROM "noreply@${public_host}")")"
  set_env MAIL_HOST "$(prompt "SMTP host" "$(env_value MAIL_HOST "smtp.example.com")")"
  set_env MAIL_PORT "$(prompt "SMTP port" "$(env_value MAIL_PORT "587")")"
  set_env MAIL_USERNAME "$(prompt "SMTP username" "$(env_value MAIL_USERNAME "noreply@${public_host}")")"
  set_env MAIL_PASSWORD "$(prompt_secret "SMTP password" "$(env_value MAIL_PASSWORD "")")"
  set_env MAIL_LOGIN "$(prompt "SMTP login mode" "$(env_value MAIL_LOGIN "REQUIRED")")"
  set_env MAIL_TLS "$(prompt "SMTP TLS enabled" "$(env_value MAIL_TLS "true")")"

  local collaboration_default="n"
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)collaboration(,|$)'; then
    collaboration_default="y"
  fi
  if prompt_yes_no "Enable collaborative Journal editing sidecar?" "$collaboration_default"; then
    add_compose_profile "collaboration"
    set_env VEYQ_COLLABORATION_IMAGE "$(prompt "veyq collaboration image" "$(env_value VEYQ_COLLABORATION_IMAGE "ghcr.io/${VEYQ_REPO%/*}/veyq-collaboration:latest")")"
    set_env JOURNAL_LIVE_TOKEN_SECRET "$(prompt_secret_or_random "Journal live token secret" "$(env_value JOURNAL_LIVE_TOKEN_SECRET "")")"
    set_env JOURNAL_LIVE_SIDECAR_SECRET "$(prompt_secret_or_random "Journal live sidecar secret" "$(env_value JOURNAL_LIVE_SIDECAR_SECRET "")")"
  else
    remove_compose_profile "collaboration"
  fi

  if prompt_yes_no "Enable reset sidecar? Only use this for disposable test instances." "n"; then
    add_compose_profile "reset"
    local reset_host
    reset_host="$(prompt "Reset host, without protocol" "reset.${public_host}")"
    set_env RESET_TRAEFIK_RULE "Host(\`${reset_host}\`)"
    set_env RESET_USERNAME "$(prompt "Reset username" "$(env_value RESET_USERNAME "admin")")"
    set_env RESET_PASSWORD "$(prompt_secret_or_random "Reset password" "$(env_value RESET_PASSWORD "")")"
  else
    remove_compose_profile "reset"
  fi

  if prompt_yes_no "Enable auto-update sidecar?" "n"; then
    add_compose_profile "updater"
    set_env VEYQ_AUTO_UPDATE_ENABLED "true"
    set_env VEYQ_AUTO_UPDATE_INTERVAL_HOURS "$(prompt "Auto-update check interval in hours" "$(env_value VEYQ_AUTO_UPDATE_INTERVAL_HOURS "6")")"
    set_env VEYQ_AUTO_UPDATE_JITTER_MINUTES "$(prompt "Auto-update jitter in minutes" "$(env_value VEYQ_AUTO_UPDATE_JITTER_MINUTES "30")")"
    set_env VEYQ_UPDATER_NOTIFY_TOKEN "$(prompt_secret_or_random "Updater notification token" "$(env_value VEYQ_UPDATER_NOTIFY_TOKEN "")")"
  else
    set_env VEYQ_AUTO_UPDATE_ENABLED "false"
    remove_compose_profile "updater"
  fi

  chmod 600 .env
  echo "Setup complete. Review $VEYQ_DIR/.env before starting or upgrading."
}

backup() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  require_env_readable
  mkdir -p backups
  local db_root_password
  local db_name
  db_root_password="$(env_value DB_ROOT_PASSWORD "")"
  db_name="$(env_value DB_NAME "veyq")"
  if [ -z "$db_root_password" ]; then
    echo "DB_ROOT_PASSWORD is required for backups." >&2
    exit 1
  fi
  local name="veyq-backup-$(timestamp)"
  cp .env "backups/${name}.env"
  compose exec -T veyq-db mariadb-dump -u root -p"${db_root_password}" "${db_name}" > "backups/${name}.sql"
  docker run --rm -v "$(media_backup_mount):/data:ro" -v "$PWD/backups:/backup" alpine \
    tar czf "/backup/${name}-media.tgz" -C "$(media_backup_path)" .
  echo "Wrote backups/${name}.env, backups/${name}.sql and backups/${name}-media.tgz"
}

ensure_env_ready_for_upgrade() {
  local mode="${1:-noninteractive}"
  if reconcile_env && doctor; then
    return 0
  fi

  echo "Environment is incomplete for this veyq version." >&2
  if [ "$mode" = "interactive" ] && is_interactive && prompt_yes_no "Run interactive setup now?" "y"; then
    setup_env
    reconcile_env
    doctor
    return 0
  fi

  echo "Fix .env or run ./veyq-server.sh setup before upgrading." >&2
  return 1
}

reconcile_env() {
  local failed=0
  apply_env_defaults

  if ! validate_env; then
    failed=1
  fi
  return "$failed"
}

apply_env_defaults() {
  local key value
  sync_env_with_template
  migrate_legacy_env_names
  set_env VEYQ_ENV_SCHEMA_VERSION "$VEYQ_ENV_SCHEMA_VERSION"
  for key in "${DEFAULTABLE_ENV_KEYS[@]}"; do
    [ "$key" = "VEYQ_ENV_SCHEMA_VERSION" ] && continue
    value="$(env_value "$key" "")"
    if [ -z "$value" ]; then
      value="$(default_env_value "$key")"
      set_env "$key" "$value"
      if is_secret_env_key "$key"; then
        echo "Added default .env value: $key=<generated>"
      else
        echo "Added default .env value: $key=$value"
      fi
    fi
  done
}

sync_env_with_template() {
  local tmp
  if [ ! -f .env.template ]; then
    return
  fi
  require_env_readable
  require_env_writable
  tmp="$(mktemp)"
  awk '
    function active_key(line, raw) {
      if (line ~ /^[A-Za-z_][A-Za-z0-9_]*=/) {
        raw = line
        sub(/=.*/, "", raw)
        return raw
      }
      return ""
    }
    function template_key(line, raw) {
      if (line ~ /^#?[A-Za-z_][A-Za-z0-9_]*=/) {
        raw = line
        sub(/^#/, "", raw)
        sub(/=.*/, "", raw)
        return raw
      }
      return ""
    }
    FNR == NR {
      key = active_key($0)
      if (key != "") {
        if (!(key in values)) {
          order[++count] = key
        }
        values[key] = substr($0, length(key) + 2)
      }
      next
    }
    {
      key = template_key($0)
      if (key != "" && key in values) {
        print key "=" values[key]
        used[key] = 1
        next
      }
      print
    }
    END {
      customHeaderPrinted = 0
      for (i = 1; i <= count; i++) {
        key = order[i]
        if (!(key in used)) {
          if (!customHeaderPrinted) {
            print ""
            print "#### CUSTOM VALUES PRESERVED FROM PREVIOUS .env ####"
            customHeaderPrinted = 1
          }
          print key "=" values[key]
        }
      }
      if (customHeaderPrinted) {
        print "#### CUSTOM VALUES PRESERVED FROM PREVIOUS .env ####"
      }
    }
  ' .env .env.template > "$tmp"
  if ! cmp -s "$tmp" .env; then
    cat "$tmp" > .env
    echo "Synchronized .env with .env.template"
  fi
  rm -f "$tmp"
}

migrate_legacy_env_names() {
  migrate_env_name QUARKUS_WEBAUTHN_RELYING_PARTY_NAME WEBAUTHN_RELYING_PARTY_NAME
  migrate_env_name QUARKUS_WEBAUTHN_ORIGINS WEBAUTHN_ORIGINS
  migrate_env_name QUARKUS_HTTP_PROXY_PROXY_ADDRESS_FORWARDING PROXY_ADDRESS_FORWARDING
  migrate_env_name QUARKUS_HTTP_PROXY_ALLOW_X_FORWARDED PROXY_ALLOW_X_FORWARDED
  migrate_env_name QUARKUS_HTTP_PROXY_ENABLE_FORWARDED_HOST PROXY_ENABLE_FORWARDED_HOST
  migrate_env_name VEYQ_JOURNAL_LIVE_WEBSOCKET_URL JOURNAL_LIVE_WEBSOCKET_URL
  migrate_env_name VEYQ_JOURNAL_LIVE_TOKEN_SECRET JOURNAL_LIVE_TOKEN_SECRET
  migrate_env_name VEYQ_JOURNAL_LIVE_TOKEN_TTL_MINUTES JOURNAL_LIVE_TOKEN_TTL_MINUTES
  migrate_env_name VEYQ_JOURNAL_LIVE_SIDECAR_SECRET JOURNAL_LIVE_SIDECAR_SECRET
}

migrate_env_name() {
  local old_key="$1"
  local new_key="$2"
  local old_value
  old_value="$(env_value "$old_key" "")"
  if [ -z "$old_value" ]; then
    return
  fi
  if [ -z "$(env_value "$new_key" "")" ]; then
    set_env "$new_key" "$old_value"
    echo "Migrated .env value: $old_key -> $new_key"
  fi
  remove_env "$old_key"
}

validate_env() {
  local key failed=0
  for key in "${REQUIRED_ENV_KEYS[@]}"; do
    if ! check_env_required "$key"; then
      failed=1
    fi
  done

  for key in "${SECRET_ENV_KEYS[@]}"; do
    if check_env_default_secret "$key"; then
      failed=1
    fi
  done

  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)reset(,|$)'; then
    for key in "${RESET_PROFILE_REQUIRED_ENV_KEYS[@]}"; do
      if ! check_env_required "$key"; then
        failed=1
      fi
    done
    for key in "${RESET_PROFILE_SECRET_ENV_KEYS[@]}"; do
      if check_env_default_secret "$key"; then
        failed=1
      fi
    done
  fi

  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)collaboration(,|$)'; then
    for key in "${COLLABORATION_PROFILE_REQUIRED_ENV_KEYS[@]}"; do
      if ! check_env_required "$key"; then
        failed=1
      fi
    done
    for key in "${COLLABORATION_PROFILE_SECRET_ENV_KEYS[@]}"; do
      if check_env_default_secret "$key"; then
        failed=1
      fi
    done
  fi

  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)updater(,|$)' || [ "$(env_value VEYQ_AUTO_UPDATE_ENABLED "false")" = "true" ]; then
    for key in "${UPDATER_PROFILE_REQUIRED_ENV_KEYS[@]}"; do
      if ! check_env_required "$key"; then
        failed=1
      fi
    done
  fi

  if ! check_media_storage_env; then
    failed=1
  fi

  [ "$failed" -eq 0 ]
}

default_env_value() {
  case "$1" in
    VEYQ_ENV_SCHEMA_VERSION) printf '%s' "$VEYQ_ENV_SCHEMA_VERSION" ;;
    VEYQ_AUTO_UPDATE_ENABLED) printf '%s' "false" ;;
    VEYQ_AUTO_UPDATE_INTERVAL_HOURS) printf '%s' "6" ;;
    VEYQ_AUTO_UPDATE_JITTER_MINUTES) printf '%s' "30" ;;
    WEBAUTHN_SESSION_ENCRYPTION_KEY) random_secret ;;
    *)
      echo "No default registered for $1" >&2
      return 1
      ;;
  esac
}

is_interactive() {
  [ -r /dev/tty ] && [ -w /dev/tty ]
}

doctor() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  require_file docker-compose.yml
  require_env_readable

  local failed=0
  echo "Checking $VEYQ_DIR"
  if ! validate_env; then
    failed=1
  fi

  if compose config >/dev/null; then
    echo "Docker Compose config is valid."
  else
    failed=1
  fi

  if [ "$failed" -ne 0 ]; then
    echo "Doctor found issues. Fix .env and run doctor again." >&2
    exit 1
  fi

  echo "Doctor passed."
}

wait_for_health() {
  echo "Waiting for veyq health check..."
  local container="${VEYQ_CONTAINER:-veyq}"
  for _ in $(seq 1 60); do
    if docker inspect --format '{{.State.Health.Status}}' "$container" 2>/dev/null | grep -q healthy; then
      echo "veyq is healthy."
      return 0
    fi
    sleep 5
  done
  echo "veyq did not report healthy within the timeout. Check: docker compose logs veyq" >&2
  return 1
}

compose() {
  docker compose "${COMPOSE_FILES[@]}" "$@"
}

pull_services() {
  echo "Pulling veyq application image first..."
  compose pull veyq
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)collaboration(,|$)'; then
    echo "Pulling veyq collaboration image..."
    compose pull veyq-collaboration
  fi
  echo "Pulling supporting service images..."
  compose pull traefik veyq-db
}

auto_upgrade() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  require_env_readable
  require_env_writable
  mkdir -p logs
  exec > >(tee -a logs/auto-upgrade.log) 2>&1

  echo "[$(timestamp)] Starting auto-upgrade check"
  if [ "$(env_value VEYQ_AUTO_UPDATE_ENABLED "false")" != "true" ]; then
    echo "Auto-update is disabled."
    return 0
  fi

  local stage="PRECHECK"
  local image=""
  local from_version=""
  local to_version=""
  local remote_digest=""
  trap 'notify_update_failed "$stage" "$from_version" "$to_version" "$image" "$remote_digest"; echo "[$(timestamp)] Auto-upgrade failed in stage $stage" >&2' ERR

  ensure_env_ready_for_upgrade noninteractive
  image="$(env_value VEYQ_IMAGE "")"
  from_version="$(container_env veyq VEYQ_VERSION)"

  stage="DIGEST_CHECK"
  remote_digest="$(remote_image_digest "$image")"
  local local_digest
  local_digest="$(local_image_digest "$image")"
  echo "Configured image: $image"
  echo "Local digest: ${local_digest:-unknown}"
  echo "Remote digest: ${remote_digest:-unknown}"
  if [ -n "$local_digest" ] && [ -n "$remote_digest" ] && [ "$local_digest" = "$remote_digest" ]; then
    echo "veyq image is already up to date."
    trap - ERR
    return 0
  fi

  stage="BACKUP"
  backup

  stage="PULL"
  compose pull veyq
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)collaboration(,|$)'; then
    compose pull veyq-collaboration
  fi

  stage="START"
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)collaboration(,|$)'; then
    compose up -d veyq veyq-collaboration
  else
    compose up -d veyq
  fi

  stage="HEALTHCHECK"
  wait_for_health
  to_version="$(container_env veyq VEYQ_VERSION)"

  trap - ERR
  notify_update_completed "$from_version" "$to_version" "$image" "$remote_digest" || true
  echo "[$(timestamp)] Auto-upgrade completed"
}

remote_image_digest() {
  local image="$1"
  docker buildx imagetools inspect "$image" 2>/dev/null | awk '/Digest:/ { print $2; exit }'
}

local_image_digest() {
  local image="$1"
  local digest
  digest="$(docker image inspect "$image" --format '{{range .RepoDigests}}{{println .}}{{end}}' 2>/dev/null | awk -F@ 'NF == 2 { print $2; exit }' || true)"
  printf '%s' "$digest"
}

container_env() {
  local container="$1"
  local key="$2"
  docker inspect "$container" --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null |
    awk -F= -v key="$key" '$1 == key { print substr($0, length(key) + 2); exit }'
}

notify_update_completed() {
  notify_updater "update-completed" "$1" "$2" "$3" "$4" ""
}

notify_update_failed() {
  notify_updater "update-failed" "$2" "$3" "$4" "$5" "$1" || true
}

notify_updater() {
  local endpoint="$1"
  local from_version="$2"
  local to_version="$3"
  local image="$4"
  local digest="$5"
  local stage="$6"
  local token
  token="$(env_value VEYQ_UPDATER_NOTIFY_TOKEN "")"
  if [ -z "$token" ]; then
    echo "Skipping updater notification because VEYQ_UPDATER_NOTIFY_TOKEN is empty."
    return 0
  fi
  local url="${VEYQ_INTERNAL_URL:-http://veyq:8080}/api/internal/updater/${endpoint}"
  local payload
  payload="{\"fromVersion\":\"$(json_escape "$from_version")\",\"toVersion\":\"$(json_escape "$to_version")\",\"image\":\"$(json_escape "$image")\",\"digest\":\"$(json_escape "$digest")\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ\")\""
  if [ -n "$stage" ]; then
    payload="${payload},\"stage\":\"$(json_escape "$stage")\""
  fi
  payload="${payload}}"
  curl -fsS -X POST "$url" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    --data "$payload" >/dev/null
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

setup_media_storage_env() {
  local current_mode mode
  current_mode="$(media_storage_mode)"
  mode="$(prompt_storage_mode "$current_mode")"
  set_env MEDIA_STORAGE_MODE "$mode"

  case "$mode" in
    docker-volume)
      set_env MEDIA_STORAGE_MOUNT_TYPE "volume"
      set_env MEDIA_STORAGE_SOURCE "veyq-media"
      remove_env MEDIA_STORAGE_MOUNT_TARGET
      remove_env MEDIA_STORAGE_DIR
      ;;
    host-path)
      set_env MEDIA_STORAGE_MOUNT_TYPE "bind"
      set_env MEDIA_STORAGE_SOURCE "$(prompt_absolute_path "Host media storage path" "$(env_value MEDIA_STORAGE_SOURCE "/mnt/veyq-media")")"
      remove_env MEDIA_STORAGE_MOUNT_TARGET
      remove_env MEDIA_STORAGE_DIR
      ;;
    cifs-volume)
      set_env MEDIA_STORAGE_MOUNT_TYPE "volume"
      set_env MEDIA_STORAGE_SOURCE "veyq-media-cifs"
      set_env MEDIA_STORAGE_MOUNT_TARGET "$(prompt_absolute_path "Container CIFS share mount path" "$(env_value MEDIA_STORAGE_MOUNT_TARGET "/data")")"
      set_env MEDIA_STORAGE_DIR "$(prompt_absolute_path "veyq media storage path inside the CIFS mount" "$(env_value MEDIA_STORAGE_DIR "/data/veyq-media")")"
      set_env MEDIA_CIFS_DEVICE "$(prompt "CIFS share path" "$(env_value MEDIA_CIFS_DEVICE "//nas.example.com/veyq-media")")"
      echo "CIFS mount options examples:"
      echo "  username=veyq,password=change-cifs-me,vers=3.0,uid=1000,gid=1000,file_mode=0660,dir_mode=0770"
      echo "  credentials=/root/veyq-cifs.creds,vers=3.0,uid=1000,gid=1000,file_mode=0660,dir_mode=0770"
      echo "  credentials=/root/veyq-cifs.creds,vers=3.0,prefixpath=veyq,uid=1000,gid=1000,file_mode=0660,dir_mode=0770"
      set_env MEDIA_CIFS_OPTIONS "$(prompt "CIFS mount options" "$(env_value MEDIA_CIFS_OPTIONS "username=veyq,password=change-cifs-me,vers=3.0,uid=1000,gid=1000,file_mode=0660,dir_mode=0770")")"
      ;;
  esac
}

prompt_storage_mode() {
  local default="$1"
  local value
  while true; do
    value="$(prompt "Media storage mode (docker-volume, host-path, cifs-volume)" "$default")"
    case "$value" in
      docker-volume|host-path|cifs-volume)
        printf '%s' "$value"
        return 0
        ;;
      *)
        echo "Choose docker-volume, host-path or cifs-volume." >&2
        ;;
    esac
  done
}

prompt_absolute_path() {
  local label="$1"
  local default="$2"
  local value
  while true; do
    value="$(prompt "$label" "$default")"
    case "$value" in
      /*)
        printf '%s' "$value"
        return 0
        ;;
      *)
        echo "$label must be an absolute path." >&2
        ;;
    esac
  done
}

media_storage_mode() {
  local mode
  mode="$(env_value MEDIA_STORAGE_MODE "")"
  if [ -n "$mode" ]; then
    printf '%s' "$mode"
    return 0
  fi

  local mount_type source
  mount_type="$(env_value MEDIA_STORAGE_MOUNT_TYPE "volume")"
  source="$(env_value MEDIA_STORAGE_SOURCE "veyq-media")"
  if [ "$mount_type" = "bind" ]; then
    printf '%s' "host-path"
  elif [ "$source" = "veyq-media-cifs" ]; then
    printf '%s' "cifs-volume"
  else
    printf '%s' "docker-volume"
  fi
}

media_backup_mount() {
  local mode source
  mode="$(media_storage_mode)"
  source="$(env_value MEDIA_STORAGE_SOURCE "veyq-media")"
  case "$mode" in
    docker-volume|cifs-volume)
      printf '%s' "$(compose_project)_${source}"
      ;;
    host-path)
      printf '%s' "$source"
      ;;
    *)
      echo "Unsupported MEDIA_STORAGE_MODE for backup: $mode" >&2
      exit 1
      ;;
  esac
}

media_backup_path() {
  local storage_dir mount_target
  storage_dir="$(env_value MEDIA_STORAGE_DIR "/data/veyq-media")"
  mount_target="$(env_value MEDIA_STORAGE_MOUNT_TARGET "/data/veyq-media")"
  if [ "$storage_dir" = "$mount_target" ]; then
    printf '%s' "/data"
    return 0
  fi
  case "$storage_dir" in
    "$mount_target"/*)
      printf '%s' "/data/${storage_dir#"$mount_target"/}"
      ;;
    *)
      echo "MEDIA_STORAGE_DIR must be inside MEDIA_STORAGE_MOUNT_TARGET for backups." >&2
      exit 1
      ;;
  esac
}

check_media_storage_env() {
  local failed=0
  local mode mount_type source storage_dir mount_target
  mode="$(media_storage_mode)"
  mount_type="$(env_value MEDIA_STORAGE_MOUNT_TYPE "volume")"
  source="$(env_value MEDIA_STORAGE_SOURCE "veyq-media")"
  storage_dir="$(env_value MEDIA_STORAGE_DIR "/data/veyq-media")"
  mount_target="$(env_value MEDIA_STORAGE_MOUNT_TARGET "/data/veyq-media")"

  case "$storage_dir" in
    /*) ;;
    *)
      echo "MEDIA_STORAGE_DIR must be an absolute container path." >&2
      failed=1
      ;;
  esac

  case "$mount_target" in
    /*) ;;
    *)
      echo "MEDIA_STORAGE_MOUNT_TARGET must be an absolute container path." >&2
      failed=1
      ;;
  esac

  if [ "$storage_dir" != "$mount_target" ]; then
    case "$storage_dir" in
      "$mount_target"/*) ;;
      *)
        echo "MEDIA_STORAGE_DIR must be inside MEDIA_STORAGE_MOUNT_TARGET." >&2
        failed=1
        ;;
    esac
  fi

  case "$mode" in
    docker-volume)
      if [ "$mount_type" != "volume" ]; then
        echo "MEDIA_STORAGE_MOUNT_TYPE must be volume for MEDIA_STORAGE_MODE=docker-volume." >&2
        failed=1
      fi
      if [ -z "$source" ]; then
        echo "MEDIA_STORAGE_SOURCE is required for MEDIA_STORAGE_MODE=docker-volume." >&2
        failed=1
      fi
      ;;
    host-path)
      if [ "$mount_type" != "bind" ]; then
        echo "MEDIA_STORAGE_MOUNT_TYPE must be bind for MEDIA_STORAGE_MODE=host-path." >&2
        failed=1
      fi
      case "$source" in
        /*) ;;
        *)
          echo "MEDIA_STORAGE_SOURCE must be an absolute host path for MEDIA_STORAGE_MODE=host-path." >&2
          failed=1
          ;;
      esac
      if [ -n "$source" ] && [ ! -d "$source" ]; then
        echo "MEDIA_STORAGE_SOURCE does not exist or is not a directory: $source" >&2
        failed=1
      fi
      ;;
    cifs-volume)
      if [ "$mount_type" != "volume" ]; then
        echo "MEDIA_STORAGE_MOUNT_TYPE must be volume for MEDIA_STORAGE_MODE=cifs-volume." >&2
        failed=1
      fi
      if [ "$source" != "veyq-media-cifs" ]; then
        echo "MEDIA_STORAGE_SOURCE must be veyq-media-cifs for MEDIA_STORAGE_MODE=cifs-volume." >&2
        failed=1
      fi
      if ! check_env_required MEDIA_CIFS_DEVICE; then
        failed=1
      fi
      if ! check_env_required MEDIA_CIFS_OPTIONS; then
        failed=1
      fi
      case "$(env_value MEDIA_CIFS_OPTIONS "")" in
        *change-cifs-me*)
          echo "Replace default secret in .env: MEDIA_CIFS_OPTIONS" >&2
          failed=1
          ;;
      esac
      ;;
    *)
      echo "MEDIA_STORAGE_MODE must be docker-volume, host-path or cifs-volume." >&2
      failed=1
      ;;
  esac

  [ "$failed" -eq 0 ]
}

compose_project() {
  local project
  project="$(compose config 2>/dev/null | awk '/^name:/ { print $2; exit }' || true)"
  if [ -n "$project" ]; then
    printf '%s' "$project"
  else
    basename "$VEYQ_DIR" | tr -cd 'a-zA-Z0-9'
  fi
}

download() {
  local source_path="$1"
  local target_path="$2"
  local url="${RAW_BASE}/${source_path}"
  local tmp
  echo "Downloading $source_path"
  tmp="$(mktemp "${target_path}.tmp.XXXXXX")"
  if command -v curl >/dev/null 2>&1; then
    if ! curl -fsSL "$url" -o "$tmp"; then
      rm -f "$tmp"
      return 1
    fi
  elif command -v wget >/dev/null 2>&1; then
    if ! wget -qO "$tmp" "$url"; then
      rm -f "$tmp"
      return 1
    fi
  else
    rm -f "$tmp"
    echo "curl or wget is required to download $url" >&2
    exit 1
  fi
  mv "$tmp" "$target_path"
}

prompt() {
  local label="$1"
  local default="$2"
  local value
  read_prompt "$label [$default]: " value
  printf '%s' "${value:-$default}"
}

prompt_yes_no() {
  local label="$1"
  local default="$2"
  local value
  read_prompt "$label [${default}]: " value
  value="${value:-$default}"
  case "$value" in
    y|Y|yes|YES|Yes|j|J|ja|JA|Ja) return 0 ;;
    *) return 1 ;;
  esac
}

prompt_secret_or_random() {
  local label="$1"
  local current="$2"
  local value
  local generated
  generated="$(random_secret)"
  if [ -n "$current" ] && [ "$current" != "change-me" ] && [ "$current" != "change-root-me" ] && [ "$current" != "change-mail-me" ] && [ "$current" != "change-reset-me" ] && [ "$current" != "dev-only-change-me" ]; then
    read_prompt "$label [keep existing]: " value
    printf '%s' "${value:-$current}"
  else
    read_prompt "$label [generated]: " value
    printf '%s' "${value:-$generated}"
  fi
}

prompt_secret() {
  local label="$1"
  local current="$2"
  local value
  if [ -n "$current" ] && [ "$current" != "change-me" ] && [ "$current" != "change-root-me" ] && [ "$current" != "change-mail-me" ] && [ "$current" != "change-reset-me" ] && [ "$current" != "dev-only-change-me" ]; then
    read_prompt "$label [keep existing]: " value
    printf '%s' "${value:-$current}"
  else
    read_prompt "$label [required]: " value
    printf '%s' "$value"
  fi
}

read_prompt() {
  local message="$1"
  local __resultvar="$2"
  local input
  if [ -r /dev/tty ] && [ -w /dev/tty ]; then
    printf '%s' "$message" > /dev/tty
    IFS= read -r input < /dev/tty || input=""
  else
    read -r -p "$message" input || input=""
  fi
  printf -v "$__resultvar" '%s' "$input"
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 36 | tr -d '\n'
  elif command -v sha256sum >/dev/null 2>&1; then
    date +%s%N | sha256sum | cut -d' ' -f1
  else
    date +%s%N | shasum -a 256 | cut -d' ' -f1
  fi
}

env_value() {
  local key="$1"
  local default="$2"
  local line
  require_env_readable
  line="$(grep -E "^${key}=" .env 2>/dev/null | tail -n 1 || true)"
  if [ -z "$line" ] || [ -z "${line#*=}" ]; then
    printf '%s' "$default"
  else
    printf '%s' "${line#*=}"
  fi
}

webauthn_rp_id_default() {
  local value
  value="$(env_value VEYQ_PUBLIC_HOST "veyq.example.com")"
  if [ "$value" = "cloud.example.com" ]; then
    printf '%s' "veyq.example.com"
  else
    printf '%s' "$value"
  fi
}

set_env() {
  local key="$1"
  local value="$2"
  local tmp
  require_env_readable
  require_env_writable
  tmp="$(mktemp)"
  if grep -qE "^#?${key}=" .env; then
    awk -v key="$key" -v value="$value" '
      index($0, key "=") == 1 || index($0, "#" key "=") == 1 {
        print key "=" value
        next
      }
      { print }
    ' .env > "$tmp"
    cat "$tmp" > .env
    rm -f "$tmp"
  else
    printf '\n%s=%s\n' "$key" "$value" >> .env
    rm -f "$tmp"
  fi
}

remove_env() {
  local key="$1"
  local tmp
  require_env_readable
  require_env_writable
  if grep -qE "^${key}=" .env; then
    tmp="$(mktemp)"
    awk -v key="$key" '$0 !~ "^" key "=" { print }' .env > "$tmp"
    cat "$tmp" > .env
    rm -f "$tmp"
  fi
}

add_compose_profile() {
  local profile="$1"
  local current
  current="$(env_value COMPOSE_PROFILES "")"
  if printf '%s' "$current" | grep -Eq "(^|,)${profile}(,|$)"; then
    return
  fi
  if [ -z "$current" ]; then
    set_env COMPOSE_PROFILES "$profile"
  else
    set_env COMPOSE_PROFILES "${current},${profile}"
  fi
}

remove_compose_profile() {
  local profile="$1"
  local current next
  current="$(env_value COMPOSE_PROFILES "")"
  next="$(printf '%s' "$current" | tr ',' '\n' | awk -v profile="$profile" 'NF && $0 != profile { values[++count] = $0 } END { for (i = 1; i <= count; i++) printf "%s%s", (i > 1 ? "," : ""), values[i] }')"
  if [ -z "$next" ]; then
    remove_env COMPOSE_PROFILES
  else
    set_env COMPOSE_PROFILES "$next"
  fi
}

check_env_required() {
  local key="$1"
  local value
  value="$(env_value "$key" "")"
  if [ -z "$value" ]; then
    echo "Missing required .env value: $key" >&2
    return 1
  fi
  return 0
}

check_env_default_secret() {
  local key="$1"
  local value
  value="$(env_value "$key" "")"
  case "$value" in
    change-me|change-root-me|change-mail-me|change-reset-me|dev-only-change-me)
      echo "Replace default secret in .env: $key" >&2
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

is_secret_env_key() {
  local wanted="$1"
  local key
  for key in "${SECRET_ENV_KEYS[@]}" "${RESET_PROFILE_SECRET_ENV_KEYS[@]}" "${COLLABORATION_PROFILE_SECRET_ENV_KEYS[@]}"; do
    [ "$key" = "$wanted" ] && return 0
  done
  return 1
}

env_needs_setup() {
  local key
  for key in "${REQUIRED_ENV_KEYS[@]}"; do
    if [ -z "$(env_value "$key" "")" ]; then
      return 0
    fi
  done

  for key in "${SECRET_ENV_KEYS[@]}"; do
    case "$(env_value "$key" "")" in
      change-me|change-root-me|change-mail-me|change-reset-me|dev-only-change-me) return 0 ;;
    esac
  done

  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)reset(,|$)'; then
    for key in "${RESET_PROFILE_REQUIRED_ENV_KEYS[@]}"; do
      if [ -z "$(env_value "$key" "")" ]; then
        return 0
      fi
    done
    for key in "${RESET_PROFILE_SECRET_ENV_KEYS[@]}"; do
      case "$(env_value "$key" "")" in
        change-me|change-root-me|change-mail-me|change-reset-me|dev-only-change-me) return 0 ;;
      esac
    done
  fi
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)collaboration(,|$)'; then
    for key in "${COLLABORATION_PROFILE_REQUIRED_ENV_KEYS[@]}"; do
      if [ -z "$(env_value "$key" "")" ]; then
        return 0
      fi
    done
    for key in "${COLLABORATION_PROFILE_SECRET_ENV_KEYS[@]}"; do
      case "$(env_value "$key" "")" in
        change-me|change-root-me|change-mail-me|change-reset-me|dev-only-change-me) return 0 ;;
      esac
    done
  fi
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)updater(,|$)' || [ "$(env_value VEYQ_AUTO_UPDATE_ENABLED "false")" = "true" ]; then
    for key in "${UPDATER_PROFILE_REQUIRED_ENV_KEYS[@]}"; do
      if [ -z "$(env_value "$key" "")" ]; then
        return 0
      fi
    done
  fi

  return 1
}

timestamp() {
  date -u +%Y%m%dT%H%M%SZ
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "$1 is required." >&2
    exit 1
  fi
}

require_compose() {
  if ! docker compose version >/dev/null 2>&1; then
    echo "Docker Compose v2 is required." >&2
    exit 1
  fi
}

require_file() {
  if [ ! -f "$1" ]; then
    echo "Missing $VEYQ_DIR/$1. Run install first." >&2
    exit 1
  fi
}

require_env_readable() {
  if [ ! -r .env ]; then
    echo "Cannot read $VEYQ_DIR/.env." >&2
    echo "The file may be owned by root from a previous sudo run." >&2
    echo "Run this command with sudo, or intentionally change ownership and keep permissions restrictive." >&2
    exit 1
  fi
}

require_env_writable() {
  if [ ! -w .env ]; then
    echo "Cannot write $VEYQ_DIR/.env." >&2
    echo "The file may be owned by root from a previous sudo run." >&2
    echo "Run this command with sudo, or intentionally change ownership and keep permissions restrictive." >&2
    exit 1
  fi
}

main "$@"
