# ADR 0021: S3-only Blob Storage and Presigned Direct-to-Store Transfers

## Status

Accepted

## Context

veyq separates the application server from blob storage. Blobs (the already-encrypted media frames) do not live
on the server's filesystem or in the relational DB; they live in an **S3-compatible object store** — either an
external managed S3 (AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO, Garage, …) or the **bundled MinIO
container** an operator can run alongside the stack (`docker compose --profile s3`). The DB holds only metadata
and the chunk manifest. A `memory` backend exists for dev/test only; there is no production filesystem backend.

With storage external to the server, every byte today flows **through** the backend twice: the client base64-
encodes a sealed frame into a JSON body → the backend decodes it and `PUT`s it to S3 (upload), and on download
the backend `GET`s the object from S3 and base64-encodes it back to the client. For a gallery of large media
this makes the backend a bandwidth bottleneck and a memory pressure point, and wastes ~33% on base64.

We want clients to transfer frames **directly** to/from the object store, bypassing the backend for the bytes —
**without ever handing the client the S3 credentials.** Giving a client the S3 access/secret key would grant it
unrestricted, non-expiring access to the whole bucket; that is unacceptable.

## How presigned URLs work

A **presigned URL** is an ordinary object URL (`https://<endpoint>/<bucket>/<key>`) plus query parameters that
carry a **signature**. The holder of the S3 secret key (the backend) computes an HMAC over a canonical form of
a *specific* request — method, bucket, object key, an expiry (`X-Amz-Expires`), and the credential scope — and
appends it as `X-Amz-Signature` (SigV4). The object store recomputes the same HMAC with its copy of the secret
and serves the request only if they match and the URL has not expired.

The signed URL is therefore a **capability**, narrowly scoped to:

- **one HTTP method** — a URL signed for `PUT` cannot `GET` and vice-versa;
- **one object key** — it grants nothing about any other object;
- **a short time window** — minutes, not forever (`X-Amz-Expires`).

The client uses the URL with a plain `PUT`/`GET` and **no credentials of its own**. The S3 access/secret key
never leaves the backend. Access control stays where it belongs: the backend signs a URL only for a caller it
has already authorized (a scope key-holder, or a share guest at the right role) and only for the keys belonging
to that upload/blob. The object store just verifies the signature.

Because veyq frames are **already AEAD ciphertext** sealed client-side under the scope content key, the object
store (and anyone who ever sees a presigned URL) only ever moves opaque ciphertext — consistent with the
operator-blind model.

## Decision

1. **Storage is S3-only.** `BlobStore` has exactly one production implementation, `S3BlobStore` (MinIO Java SDK,
   which speaks to any S3-compatible endpoint). `InMemoryBlobStore` is dev/test only. Credentials come from
   operator env (`BLOB_STORE_S3_*`), are never persisted to the DB, never logged, masked in any config dump
   (`smallrye.config.secret-keys`), and never returned by any endpoint.

2. **Add presigned direct-to-store as an optional fast path**, with the proxied path as the always-available
   fallback (used by the `memory` backend and any store that cannot presign):

   - `BlobStore` gains `presignPut(key, ttl)`, `presignGet(key, ttl)` (return `null` when unsupported) and
     `stat(key)` (object size, or `null` if absent).
   - **Upload:** `startBlob` dedups as today. For a new upload the backend, on request, returns a **presigned
     `PUT` URL per still-missing chunk key**. The client `PUT`s the **raw sealed frame bytes** (no base64, no
     JSON) straight to S3. `complete` then **stats each expected object** in S3 to confirm presence and size,
     writes the chunk manifest from those sizes, enforces the cumulative byte budget, and promotes the frames
     to a blob — deleting the objects and rejecting the upload if verification fails.
   - **Download:** `GET .../blobs/{blobId}/presigned-chunks` returns a **presigned `GET` URL per chunk**
     (empty when the store can't presign); the client fetches the raw frames directly from S3 and
     `openFrame`s them (the AAD position check still applies).

   **Upload presigns everything; download presigns only multi-frame blobs (`totalChunks > 1`) — the asymmetry
   is intentional.** On upload the presigned `PUT` URLs ride along in the `getBlobUpload` (resume) response the
   client must fetch anyway to learn which frames are still missing — so presigning every chunk, even a
   single-frame thumbnail, costs **no extra round-trip**. On download there is no such piggyback request: the
   client already knows `totalChunks` from the `BlobRef`, so fetching presigned `GET` URLs is a **dedicated
   extra request**. For a one-frame thumbnail/preview (the hot gallery path, many in parallel) that doubles the
   round-trips for no bandwidth win, so the client stays on the proxied chunk endpoint for single-frame blobs
   and only takes the direct-from-store path for large multi-frame originals. The **client** makes this call;
   the backend simply offers presigned URLs whenever the store supports it. Rule of thumb: *presign when there
   is already a request to carry the URLs, or when the payload is big enough that one extra round-trip pays for
   itself.*

3. **Anti-abuse moves to declare-then-verify.** The backend cannot see bytes mid-PUT, so per-frame size is no
   longer rejected at PUT time. Instead: the declared `byteSize`/`totalChunks` are bounded at `start` (as
   today), the open-session cap still applies, and `complete` verifies the actual object sizes against the
   budget and **purges** the staged objects if they exceed it. Abandoned presigned uploads are reclaimed by the
   existing GC (stale OPEN sessions) plus the unreferenced-blob GC (ADR 0007).

## Consequences

- The backend stops proxying blob bytes on the fast path: large galleries and downloads no longer stream
  through the app process, and base64 overhead disappears for direct transfers.
- **Infra:** for a browser/mobile client to reach a presigned URL, the **object store endpoint must be
  reachable by the client.** External S3 already is. The bundled MinIO must be published on its own public
  route (a Traefik router / subdomain), not just the internal Docker network, and the **bucket needs a CORS
  policy** allowing `PUT`/`GET` from the app origin for browser uploads. The presigned URL's host is the
  client-facing endpoint, which may differ from the backend's internal endpoint.
- **Security:** a leaked presigned URL grants only that one object + method until it expires; keep the TTL
  short. The credentials themselves never reach the client. Access control is enforced at URL issuance.
- **Fallback preserved:** clients detect whether the backend offered presigned URLs; if not (e.g. the `memory`
  dev backend, or an operator who keeps everything proxied), they use the existing proxied chunk endpoints.
  This keeps dev/test (no real S3) working and lets an operator disable direct transfer.
- **Backend-signed presigning requires the backend to hold the store's secret in plaintext at signing time**
  (SigV4 is an HMAC under the secret). This is fine in BOTH deployment models because the backend is always the
  store's rightful owner (ADR 0022): self-hosted = the operator's own S3; veyq.cloud = the SaaS provider's
  bucket. End users do not bring their own S3, so there is no per-tenant credential the backend must be blind
  to. Content is E2EE regardless — holding the S3 creds never lets the backend decrypt media (no content key).

## Update — direct-to-store only + batched transport

The presigned fast path described above became the **only** transport: the proxied per-chunk endpoints
(`putBlobChunk`/`getBlobChunk` and their public-share mirrors) and the client fallbacks were **removed**. The
backend now handles only the control plane; every byte travels client↔store via presigned URLs. Two reasons it
is safe to drop the fallback: the store is **blind** (only AEAD ciphertext, per-object short-TTL URLs), so
exposing it to clients leaks nothing; and this is **greenfield** (no installed base), so the removal breaks no
deployed client.

**Batched control plane.** To avoid `3N` round-trips for `N` photos (original + preview + thumbnail each), the
per-blob endpoints were replaced by three batch endpoints (authed + public-share mirrors):

- `…/blob-uploads-batch` — dedup/open + presign **many** blobs in one call (pass an `uploadId` to resume).
- `…/nodes/{nodeId}/media-batch` — complete many uploads + attach media in one call (per-item best-effort).
- `…/blob-downloads-batch` — presign GET URLs for a whole gallery page in one call.

A photo upload is then `1 batch-start → parallel direct PUTs → 1 batch-finalize`; a gallery page is
`1 batch-presign-get → parallel direct GETs`.

**Public endpoint.** Presigned URLs are signed for `veyq.blob-store.s3-public-endpoint` (the endpoint **clients**
reach), which may differ from the internal `s3-endpoint` (e.g. a bundled MinIO on a private compose hostname).
`S3BlobStore` keeps a second MinIO client purely for URL signing (presigning is local HMAC; it never connects).
Empty ⇒ sign against `s3-endpoint` (external S3, or any publicly-reachable store).

**CORS is browser-only.** Native apps PUT/GET presigned URLs directly and are never subject to CORS — it is a
**web-only** concern, configured **on the store** (veyq cannot inject CORS headers into a direct fetch):

| Store topology | CORS | Where configured |
|---|---|---|
| Bundled MinIO via Traefik **path route** (same origin as the web app) | **none needed** (same-origin) | — |
| Bundled/self MinIO on a **separate** host/subdomain | required | `MINIO_API_CORS_ALLOW_ORIGIN` (server env) |
| **AWS S3** | required | **bucket** CORS config (`PutBucketCors`) |
| **Azure Blob** | required | **storage-account** CORS (blob-service properties) |

The CORS rule must allow the web origin with methods `PUT`+`GET` and expose `ETag`. For the bundled MinIO behind
Traefik, route the **bucket path** to MinIO **without** stripping the prefix and with the Host header preserved —
SigV4 signs host+path+query, so the public path must arrive at MinIO byte-identical to what was signed.

**Preflight (loud failure).** With no proxied fallback, a misconfigured store would mean silently-broken media.
`BlobStorePreflight` probes at startup: it presigns a throwaway key and issues a CORS-style `OPTIONS` against the
**public** endpoint, logging a clear, actionable warning if the public route is unreachable or returns no
`Access-Control-Allow-Origin` for the web origin. `/api/setup` also advertises `directTransport: ok|unavailable`.
The probe is advisory (never blocks startup — the backend can't always hairpin to its own public host).

**Dev/test.** The in-memory backend cannot presign, so it no longer has any media-transport path; transport is
exercised by opt-in e2e tests against a real MinIO (`VEYQ_S3_E2E=1`), and local dev runs the bundled MinIO.
