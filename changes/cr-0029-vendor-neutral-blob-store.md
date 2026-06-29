---
id: cr-0029
title: Vendor-neutral blob store — drop deprecated MinIO (AWS S3 SDK + bundled Garage)
status: done             # planned → in-progress → done (the file is kept as a record)
satisfies: [en.memory.media]   # hardening the cr-0028 media transport against a deprecated dependency
adrs: ["0013"]
invariants: [operator-blind, self-hostable]
---

## Goal

Remove the **deprecated MinIO** dependency that cr-0028 introduced, per the new *no deprecated/unmaintained
dependencies* working rule ([CLAUDE.md](../CLAUDE.md)). MinIO's OSS server was **archived in Feb 2026** and its
only successor, **AIStor, is commercial** (subscription from ~$96k/yr) — which would break *self-hostable by
default*. The fix is **vendor-neutral**, in two independent parts:

1. **Backend SDK:** replace `io.minio:minio` with the **AWS SDK for Java v2** (`software.amazon.awssdk:s3` +
   `S3Presigner`). It presigns + stats against *any* S3-compatible endpoint, is actively maintained
   (Apache-2.0), and removes the MinIO-branded client from our code. Aligns with [ADR 0013 §1](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)
   ("S3-compatible, not vendor-specific"). **Store-agnostic** — independent of the bundled-store choice.
2. **Bundled store:** replace the MinIO image with **Garage** (Deuxfleurs) — S3-compatible, AGPL, built for
   small self-hosted deployments, stable (v2.x, in production since 2020), supports the exact subset veyq uses
   (presigned PUT/GET + `stat`; no versioning/object-lock/erasure-coding needed). **Not AIStor** (commercial).

No contract, crypto, or client-data-layer change — the wire + the `S3BlobStore`/`InMemoryBlobStore` interface
are unchanged; only the SDK behind `S3BlobStore` and the bundled image swap.

## Context & constraints

- **Operator-blind / self-hostable (invariants):** unchanged — the backend still only presigns + stats; bytes
  travel client↔store. Garage runs as a separate AGPL service (the AGPL falls on the store operator, never on
  veyq), so the bundled default stays cost-free + self-hostable. Wer zentral fährt zeigt veyq via die
  vendor-neutral SDK auf ein „großes" S3 (R2/B2/AWS).
- **Path-style addressing** is forced on both the client and the presigner — custom endpoints (Garage, MinIO,
  …) can't serve virtual-host-style bucket subdomains.
- **Region must match** the store (SigV4 signs it): Garage's `s3_region` is set to `us-east-1` to match the
  backend default, so no per-store config drift.
- **Garage setup friction:** Garage's official image is shell-less (scratch + binary), so the single-node
  layout + bucket + key init is a one-shot host script (`infra/garage-init.sh`) driving the CLI via
  `docker exec` — idempotent, a no-op once the `veyq` bucket exists. Deterministic dev key so the e2e + a dev
  backend use fixed credentials.

## Phases

- [x] **1 — backend (SDK migration, store-agnostic).** `build.gradle.kts` `io.minio:minio` → `software.amazon.awssdk:s3`;
  `S3BlobStore` rewritten on `S3Client` (stat/delete) + `S3Presigner` (presign PUT/GET), path-style forced;
  `BlobStoreProducer` builds both from the same config; `BlobBatchE2ETest` bucket-init + creds moved to the AWS
  SDK + deterministic Garage dev key. **Done:** hermetic `./gradlew check` green (the in-memory store path is
  unchanged; 90 tests, e2e skipped).
- [x] **2 — infra (bundled store).** `infra/docker-compose.yml` MinIO → **Garage** (`dxflrs/garage`, S3 on
  `:19000`, admin on `:19003`) + `garage.toml` (single-node, `s3_region=us-east-1`); dropped the `minio`/
  `minio-createbucket` services; Traefik path-route now targets Garage's `:3900`. `infra/garage-init.sh`
  (host `docker exec`, idempotent: layout assign/apply + bucket create + key import + allow). `infra/README.md`
  updated. **Done:** `docker compose --profile s3 config` valid.
- [x] **3 — verify e2e.** Brought the Garage profile up + `garage-init.sh`, ran `BlobBatchE2ETest`
  (`VEYQ_S3_E2E=1`) **green against real Garage (1/1)** — proves AWS-SDK presigning (PUT+GET, path-style, SigV4
  region) + declare-then-verify `stat` (headObject) + the byte round-trip against the new store.
- [x] **docs.** ADR 0013 §1/§4 + consequences made vendor-neutral (AWS SDK v2; bundled Garage; MinIO/AIStor
  note); `infra/README.md`; the *no deprecated dependencies* rule in `CLAUDE.md`. cr-0028 left as its record
  with a pointer here.

## Parity ledger

| Surface | Vendor-neutral blob store (no deprecated dep) | Status |
|---|---|---|
| backend | `S3BlobStore` on AWS SDK v2 (presign + stat), in-memory unchanged | ✅ |
| infra | bundled **Garage** + Traefik route + idempotent init | ✅ |
| e2e | `BlobBatchE2ETest` green vs real Garage | ✅ |
| clients (web/apple/android) | **no change** — wire + data layer untouched | ✅ n/a |

## Definition of done

The backend signs/stats via the vendor-neutral AWS S3 SDK against any S3-compatible store; the bundled
dev/self-host default is Garage (no deprecated/commercial dependency); hermetic suites stay green and the
opt-in e2e passes against Garage; ADR 0013 + infra docs reflect the vendor-neutral stance; the
*no deprecated dependencies* rule is recorded. The media feature (cr-0028) is unchanged on the wire.
