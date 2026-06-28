#!/usr/bin/env sh
# One-shot init for the bundled Garage store (cr-0029, ADR 0013). Run once after
# `docker compose -f infra/docker-compose.yml --profile s3 up -d`. Idempotent: a no-op once the `veyq` bucket
# exists. Garage's official image is shell-less (scratch), so we drive its CLI from the host via `docker exec`.
#
# Assigns a single-node layout, creates the `veyq` bucket, imports DETERMINISTIC dev credentials (so the
# backend e2e + a dev backend can use fixed keys), and grants the key full bucket access. Throwaway creds only.
set -eu

CONTAINER="${VEYQ_GARAGE_CONTAINER:-veyq-garage}"
BUCKET="${VEYQ_S3_BUCKET:-veyq}"
KEY_ID="${VEYQ_S3_ACCESS_KEY:-GK0123456789abcdef01234567}"
SECRET="${VEYQ_S3_SECRET_KEY:-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef}"

g() { docker exec "$CONTAINER" /garage "$@"; }

echo "waiting for garage…"
i=0
until g status >/dev/null 2>&1; do
  i=$((i + 1)); [ "$i" -gt 60 ] && { echo "garage did not come up"; exit 1; }
  sleep 1
done

if g bucket info "$BUCKET" >/dev/null 2>&1; then
  echo "garage already initialized ($BUCKET exists) — nothing to do"
  exit 0
fi

NODE="$(g node id -q | cut -d@ -f1)"
echo "assigning single-node layout to $NODE"
g layout assign "$NODE" -z local -c 1G
g layout apply --version 1

echo "creating bucket $BUCKET"
g bucket create "$BUCKET"

echo "importing dev key $KEY_ID"
g key import --yes -n veyq-app "$KEY_ID" "$SECRET" || echo "key import skipped (already present?)"
g bucket allow "$BUCKET" --key veyq-app --read --write --owner

echo "garage ready: bucket=$BUCKET key=$KEY_ID endpoint=http://localhost:19000"
