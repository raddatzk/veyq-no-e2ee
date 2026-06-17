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
   - **Download:** the backend returns a **presigned `GET` URL per chunk**; the client fetches the raw frames
     directly from S3 and `openFrame`s them (the AAD position check still applies).

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
