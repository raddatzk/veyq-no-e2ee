#!/usr/bin/env bash
set -euo pipefail

VEYQ_DIR="${VEYQ_DIR:-/opt/veyq}"
VEYQ_REPO="${VEYQ_REPO:-raddatzk/veyq}"
VEYQ_REF="${VEYQ_REF:-main}"
RAW_BASE="${RAW_BASE:-https://raw.githubusercontent.com/${VEYQ_REPO}/${VEYQ_REF}}"

COMPOSE_FILES=(-f docker-compose.yml)

usage() {
  cat <<'USAGE'
Usage: veyq-server.sh <command>

Commands:
  install   Download Compose files, create .env and optionally run setup prompts.
  setup     Prompt for common .env values without changing volumes.
  upgrade   Refresh Compose files, pull the configured image and restart veyq.
  status    Show Docker Compose service status.
  doctor    Validate local server configuration without changing services.
  backup    Create a database dump and media archive in ./backups.

Environment:
  VEYQ_DIR   Install directory. Default: /opt/veyq
  VEYQ_REPO  GitHub repo to download infra files from. Default: raddatzk/veyq
  VEYQ_REF   Branch, tag or SHA to download infra files from. Default: master
USAGE
}

main() {
  local command="${1:-}"
  case "$command" in
    install) install ;;
    setup) setup_env ;;
    upgrade) upgrade ;;
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
  mkdir -p "$VEYQ_DIR"
  cd "$VEYQ_DIR"
  download_infra

  if [ ! -f .env ]; then
    cp .env.example .env
    chmod 600 .env
    echo "Created $VEYQ_DIR/.env"
    if prompt_yes_no "Run interactive setup now?" "y"; then
      setup_env
    else
      echo "Edit $VEYQ_DIR/.env before starting veyq."
    fi
  else
    echo "Keeping existing $VEYQ_DIR/.env"
  fi

  if prompt_yes_no "Pull and start veyq now?" "y"; then
    compose pull
    compose up -d
    wait_for_health
  fi
}

upgrade() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  mkdir -p backups
  cp docker-compose.yml "backups/docker-compose.$(timestamp).yml" 2>/dev/null || true
  cp .env "backups/env.$(timestamp)" 2>/dev/null || true
  download_infra
  compose pull
  compose up -d
  wait_for_health
}

download_infra() {
  download "scripts/veyq-server.sh" "veyq-server.sh"
  chmod 755 "veyq-server.sh"
  download "infra/docker-compose.yml" "docker-compose.yml"
  download "infra/.env.example" ".env.example"
  mkdir -p reset
  download "infra/reset/Dockerfile" "reset/Dockerfile"
  download "infra/reset/app.py" "reset/app.py"
  download "infra/reset/requirements.txt" "reset/requirements.txt"
}

setup_env() {
  cd "$VEYQ_DIR"
  require_file .env

  echo "Configuring $VEYQ_DIR/.env"
  local public_host letsencrypt_email image
  public_host="$(prompt "Public veyq host, without protocol" "$(env_value WEBAUTHN_RP_ID "veyq.example.com")")"
  letsencrypt_email="$(prompt "Let's Encrypt email" "$(env_value LETSENCRYPT_EMAIL "admin@example.com")")"
  image="$(prompt "veyq image" "$(env_value VEYQ_IMAGE "ghcr.io/${VEYQ_REPO%/*}/veyq:latest")")"

  set_env VEYQ_IMAGE "$image"
  set_env LETSENCRYPT_EMAIL "$letsencrypt_email"
  set_env VEYQ_TRAEFIK_RULE "Host(\`${public_host}\`)"
  set_env WEBAUTHN_ORIGINS "https://${public_host}"
  set_env WEBAUTHN_RP_ID "$public_host"
  set_env WEBAUTHN_RP_NAME "$(prompt "Passkey display name" "$(env_value WEBAUTHN_RP_NAME "veyq")")"
  set_env VEYQ_SOURCE_URL "$(prompt "Source URL for AGPL notices" "$(env_value VEYQ_SOURCE_URL "https://github.com/${VEYQ_REPO}")")"
  set_env VEYQ_SUPPORT_URL "$(prompt "Optional donation/support URL" "$(env_value VEYQ_SUPPORT_URL "")")"

  set_env DB_PASSWORD "$(prompt_secret_or_random "Database app password" "$(env_value DB_PASSWORD "")")"
  set_env DB_ROOT_PASSWORD "$(prompt_secret_or_random "Database root password" "$(env_value DB_ROOT_PASSWORD "")")"

  echo "Mail settings are required for invitations and password resets."
  set_env MAIL_FROM "$(prompt "Mail sender address" "$(env_value MAIL_FROM "noreply@${public_host}")")"
  set_env MAIL_HOST "$(prompt "SMTP host" "$(env_value MAIL_HOST "smtp.example.com")")"
  set_env MAIL_PORT "$(prompt "SMTP port" "$(env_value MAIL_PORT "587")")"
  set_env MAIL_USERNAME "$(prompt "SMTP username" "$(env_value MAIL_USERNAME "noreply@${public_host}")")"
  set_env MAIL_PASSWORD "$(prompt_secret_or_random "SMTP password" "$(env_value MAIL_PASSWORD "")")"
  set_env MAIL_LOGIN "$(prompt "SMTP login mode" "$(env_value MAIL_LOGIN "REQUIRED")")"
  set_env MAIL_TLS "$(prompt "SMTP TLS enabled" "$(env_value MAIL_TLS "true")")"

  if prompt_yes_no "Enable reset sidecar? Only use this for disposable test instances." "n"; then
    set_env COMPOSE_PROFILES "reset"
    local reset_host
    reset_host="$(prompt "Reset host, without protocol" "reset.${public_host}")"
    set_env RESET_TRAEFIK_RULE "Host(\`${reset_host}\`)"
    set_env RESET_USERNAME "$(prompt "Reset username" "$(env_value RESET_USERNAME "admin")")"
    set_env RESET_PASSWORD "$(prompt_secret_or_random "Reset password" "$(env_value RESET_PASSWORD "")")"
  else
    remove_env COMPOSE_PROFILES
  fi

  chmod 600 .env
  echo "Setup complete. Review $VEYQ_DIR/.env before starting or upgrading."
}

backup() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
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
  docker run --rm -v "$(compose_project)_veyq-media:/data:ro" -v "$PWD/backups:/backup" alpine \
    tar czf "/backup/${name}-media.tgz" -C /data .
  echo "Wrote backups/${name}.env, backups/${name}.sql and backups/${name}-media.tgz"
}

doctor() {
  require_command docker
  require_compose
  cd "$VEYQ_DIR"
  require_file .env
  require_file docker-compose.yml

  local failed=0
  echo "Checking $VEYQ_DIR"
  for key in VEYQ_IMAGE LETSENCRYPT_EMAIL VEYQ_TRAEFIK_RULE DB_PASSWORD DB_ROOT_PASSWORD WEBAUTHN_ORIGINS WEBAUTHN_RP_ID MAIL_FROM MAIL_HOST MAIL_PORT MAIL_USERNAME MAIL_PASSWORD; do
    if ! check_env_required "$key"; then
      failed=1
    fi
  done

  for key in DB_PASSWORD DB_ROOT_PASSWORD MAIL_PASSWORD; do
    if check_env_default_secret "$key"; then
      failed=1
    fi
  done
  if env_value COMPOSE_PROFILES "" | grep -Eq '(^|,)reset(,|$)'; then
    for key in RESET_TRAEFIK_RULE RESET_USERNAME RESET_PASSWORD; do
      if ! check_env_required "$key"; then
        failed=1
      fi
    done
    if check_env_default_secret RESET_PASSWORD; then
      failed=1
    fi
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

compose_project() {
  basename "$VEYQ_DIR" | tr -c 'a-zA-Z0-9' ''
}

download() {
  local source_path="$1"
  local target_path="$2"
  local url="${RAW_BASE}/${source_path}"
  echo "Downloading $source_path"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$url" -o "$target_path"
  elif command -v wget >/dev/null 2>&1; then
    wget -qO "$target_path" "$url"
  else
    echo "curl or wget is required to download $url" >&2
    exit 1
  fi
}

prompt() {
  local label="$1"
  local default="$2"
  local value
  read -r -p "$label [$default]: " value
  printf '%s' "${value:-$default}"
}

prompt_yes_no() {
  local label="$1"
  local default="$2"
  local value
  read -r -p "$label [${default}]: " value
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
  if [ -n "$current" ] && [ "$current" != "change-me" ] && [ "$current" != "change-root-me" ] && [ "$current" != "change-mail-me" ] && [ "$current" != "change-reset-me" ]; then
    read -r -p "$label [keep existing]: " value
    printf '%s' "${value:-$current}"
  else
    read -r -p "$label [generated]: " value
    printf '%s' "${value:-$generated}"
  fi
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
  line="$(grep -E "^${key}=" .env 2>/dev/null | tail -n 1 || true)"
  if [ -z "$line" ]; then
    printf '%s' "$default"
  else
    printf '%s' "${line#*=}"
  fi
}

set_env() {
  local key="$1"
  local value="$2"
  local escaped
  escaped="$(printf '%s' "$value" | sed 's/[\/&]/\\&/g')"
  if grep -qE "^#?${key}=" .env; then
    sed -i "s/^#\?${key}=.*/${key}=${escaped}/" .env
  else
    printf '\n%s=%s\n' "$key" "$value" >> .env
  fi
}

remove_env() {
  local key="$1"
  if grep -qE "^${key}=" .env; then
    sed -i "/^${key}=/d" .env
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
    change-me|change-root-me|change-mail-me|change-reset-me)
      echo "Replace default secret in .env: $key" >&2
      return 0
      ;;
    *)
      return 1
      ;;
  esac
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

main "$@"
