#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
IMAGE_NAME="${VEYQ_COLLABORATION_IMAGE_LOCAL:-veyq-collaboration:dev}"
CONTAINER_NAME="${VEYQ_COLLABORATION_CONTAINER_NAME:-veyq-collaboration-dev}"

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
docker build -t "$IMAGE_NAME" "$ROOT_DIR/collaboration"
docker run --rm \
  --name "$CONTAINER_NAME" \
  -p "${VEYQ_COLLABORATION_PORT:-8082}:8082" \
  -p "${VEYQ_COLLABORATION_HEALTH_PORT:-8083}:8083" \
  -e PORT=8082 \
  -e HEALTH_PORT=8083 \
  -e VEYQ_BACKEND_URL="${VEYQ_BACKEND_URL:-http://host.docker.internal:8080}" \
  -e JOURNAL_LIVE_SIDECAR_SECRET="${JOURNAL_LIVE_SIDECAR_SECRET:-dev-only-collaboration-sidecar-secret}" \
  -e JOURNAL_SNAPSHOT_DEBOUNCE_MS="${JOURNAL_SNAPSHOT_DEBOUNCE_MS:-750}" \
  -e JOURNAL_DOCUMENT_IDLE_EVICTION_MS="${JOURNAL_DOCUMENT_IDLE_EVICTION_MS:-300000}" \
  "$IMAGE_NAME"
