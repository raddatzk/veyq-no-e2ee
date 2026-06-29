# infra — local dev stack

Docker Compose for the local dev dependencies. Two profiles:

- **default** — `mariadb`: the persistent dev database (prod-parity for `quarkusDev`). The test suites use
  in-memory H2 and need none of this.
- **`s3`** (opt-in) — `garage` + `traefik`: the bundled object store for **media blobs**
  ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md), cr-0028 / cr-0029).

```sh
# database only (the usual dev loop)
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml down        # stop, keep data
docker compose -f infra/docker-compose.yml down -v      # stop AND wipe (fresh instance)

# + the bundled object store (media transport) — start, then init once
docker compose -f infra/docker-compose.yml --profile s3 up -d
./infra/garage-init.sh                                  # idempotent: layout + bucket + dev key
```

## Object store (media, `--profile s3`)

The backend is **operator-blind**: it never streams media bytes, it only **presigns** short-TTL URLs the
client PUT/GETs directly against the store ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)),
via the **vendor-neutral AWS S3 SDK** (works against any S3-compatible store). The bundled store is **Garage**
(S3-compatible, AGPL, built for self-hosting — it replaced the archived MinIO, cr-0029). Garage's image is
shell-less, so a one-shot host script (`garage-init.sh`) drives its CLI via `docker exec` to assign a
single-node layout, create the `veyq` bucket, and import **deterministic dev credentials** (so the e2e + a dev
backend use fixed keys). Re-running it is a no-op once the bucket exists.

- **S3 API** → `http://localhost:19000`. Dev key `GK0123456789abcdef01234567` / secret
  `0123…` (64 hex, see `garage-init.sh`), bucket `veyq`, region `us-east-1` — local throwaway only.
- **Backend e2e** (`BlobBatchE2ETest`): bring the profile up + init, then
  `VEYQ_S3_E2E=1 ./gradlew :backend:test --tests '*BlobBatchE2ETest'` (defaults match the compose; override via
  `VEYQ_S3_ENDPOINT`/`VEYQ_S3_ACCESS_KEY`/`VEYQ_S3_SECRET_KEY`/`VEYQ_S3_BUCKET`/`VEYQ_S3_REGION`).
- **Backend against the store** (`quarkusDev`): point it at the store via env —
  `veyq.blob-store.endpoint=http://localhost:19000`, `…access-key=GK0123456789abcdef01234567`,
  `…secret-key=0123…`. A blank `endpoint` keeps the in-memory dev store (no presign, no byte path); `/api/setup`
  then reports `directTransport: false`.

### Same-origin via Traefik (web, no CORS)

A browser can only PUT/GET a presigned URL if the store is reachable from the web origin **and** allows CORS —
unless the store is **same-origin**. `traefik` path-routes the bucket so it is: it forwards `/<bucket>/…`
**without stripping the prefix** and **preserving the Host header** (SigV4 signs host+path+query, so a
stripPrefix or host rewrite would break the signature). Entry is `http://localhost:18080`; sign presigned URLs
for that public endpoint via `veyq.blob-store.public-endpoint=http://localhost:18080` (CORS-free, ADR 0013 §4).
Native apps PUT/GET directly and are never subject to CORS. (The backend + web are added behind Traefik in a
fuller deployment; this profile routes only the bucket path.)

## Seeding a reproducible dev instance (cr-0046)

Bring up a known `{Server Owner + 127.0.0.1 tenant owner + personal scope}` in one command and persist it
(credentials **and** raw key material) to a gitignored `web/scripts/.seed/dev-seed.json` a harness can load:

```sh
docker compose -f infra/docker-compose.yml down -v && docker compose -f infra/docker-compose.yml up -d
# start the backend writing the dev-invite file (so the seeder reads the bootstrap secret deterministically):
VEYQ_TENANT_INVITE_DEV_FILE=$PWD/backend/build/dev-invites.jsonl ./gradlew -p backend quarkusDev
cd web && npm run seed:dev
```

`veyq.tenant.invite.dev-file` (dev/test only, never written in production) is the second sink of the invite
line the logging mailer already emits — no console-log scraping. `seed:dev` creates the tenant under a hostname
(`POST /api/tenants` rejects a numeric domain) and remaps the domain to `127.0.0.1` in the dev DB so the web
dev server (which presents `Host: 127.0.0.1`) resolves it. Load it from a harness via `web/scripts/devSeed.mts`
(`loadDevSeed()` / `vaultFromSeed()`).
