# veyq Architecture

## Target Picture

veyq runs as a single instance with global users and multiple tenants. External domains, TLS and root-path rewrites are managed by an upstream proxy (Traefik in the Compose stack). The backend knows no host mappings and addresses all business data by **opaque scope IDs** (UUIDs) — never by tenant name — in the path: `/api/scopes/{scopeId}/...`, `/api/tenants/{tenantId}/...`.

The defining property is that the backend is **operator-blind (a "blind store")**: it stores and serves only ciphertext. Content keys, names, capture time, GPS, journal text and media bytes are sealed client-side under per-scope content keys and never leave the client in the clear. The server holds wrapped key material it cannot unwrap, sealed columns it cannot read, and blinded dedup/slug tokens it cannot reverse. Authorization is enforced server-side on opaque identifiers; confidentiality is enforced cryptographically on the client.

## Runtime

- The Quarkus/Kotlin backend serves the API only. The Vue frontend is built and deployed separately (its static bundle can be copied into the image, but the backend has no frontend-serving framework wired in).
- MariaDB is the production database (`DB_KIND=mariadb` + a `jdbc:mariadb` `DB_URL`); H2 in-memory is the dev/slice default. Because Flyway owns the schema and migrations are kept to the H2∩MariaDB common type subset, the same migrations run on both.
- Flyway manages the schema through versioned migrations (`backend/src/main/resources/db/migration/`), applied at startup.
- Docker Compose (`infra/docker-compose.yml`) starts Traefik, MariaDB and the app, plus optional `reset` and `updater` sidecars.
- The OpenAPI specs under `docs/api` are the public API target contract; backend and frontend both generate artifacts from them.
- Encrypted media bytes live in an S3-compatible object store (external S3 or the bundled MinIO; `veyq.blob-store.*`), never in the database. The server is decoupled from storage and may hand clients presigned URLs to transfer ciphertext directly (ADR 0021).

## Crypto Suite

All client crypto is pinned to suite **"v1"**, implemented byte-identically on web (`v1.ts`), iOS (`V1.swift`) and Android (`V1.kt`) and locked by conformance vectors in `docs/api/crypto-vectors/v1.json` with a conformance test suite per platform. The backend only ever sees suite-tagged ciphertext (`suiteId: "v1"`) and verifies an Argon2id auth *proof*; it performs no content cryptography.

## Domain Model

The schema (migrations V1–V27) is built around users, opaque scopes, a sealed node tree, sealed blobs and grants. Key tables:

- `veyq_user` — global users: `email`, `display_name`, `server_role`, `system_account`, `created_at`. There is **no password hash here**; password material is an Argon2 verifier (below).
- `veyq_user.auth_verifier*` — the Argon2id PHC auth verifier used to check a login proof. `veyq_password_reset` carries reset material.
- `veyq_identity_key` / `veyq_key_blob` — the user's identity public key and the wrapped private key + master-key wraps ("key bag"). The server stores these but cannot unwrap them.
- `veyq_session` — opaque Bearer sessions, stored only as `token_hash`. Issued at login, invalidated at logout; there is **no refresh/rotation model**.
- `veyq_tenant` — a tenant with a unique `domain`. `veyq_tenant_invite` holds sealed invite secrets + temp-key wraps.
- `veyq_scope` — the unit business data is keyed on (personal / group / tenant). `veyq_scope_grant` records who may access a scope (membership is a *grant*, not a server-readable role table). `veyq_scope_content_key` holds the per-scope content-key wraps; `veyq_scope_item` links items into a scope.
- `veyq_membership` / `veyq_group_membership` — group membership rows carrying a `role`; groups are themselves scopes.
- `veyq_node` — the memory tree: `type` ∈ {FOLDER, MEMORY, CHAPTER}, `parent_id`, blinded `slug_token`, `order_index`, `sealed_content` (name/title/metadata sealed client-side). There is no `veyq_memory` table.
- `veyq_blob` / `veyq_blob_upload` / `veyq_blob_chunk` — the dedup-and-resume blob layer: a per-scope **blinded** `dedup_token` (not a server-visible hash), `byte_size`, and sealed AEAD frames. `veyq_media` attaches a stored blob (`original_blob_id`) to a node with `taken_at`/`mime_type`; `veyq_media_derivative` carries typed derivatives (thumbnail/preview), which are themselves ordinary client-sealed blobs. `veyq_upload_session` backs resumable uploads.
- `veyq_memory_journal` (+ `veyq_memory_journal_update`) — sealed Yjs snapshot + append-only sealed update log for live-editable journals. `veyq_memory_track` — sealed track content. Both store only `sealed_content`/`sealed_*`.
- `veyq_share` — public share links on a node subtree, carrying a `wrapped_content_key` and (V15–V17) sealed password/expiry/role/terms.
- `veyq_server_setup` — the single-owner setup lock row.

The server sees none of the following in cleartext: names, titles, journal/track content, capture time, GPS, MIME of original media beyond what is stored, or media bytes. EXIF/GPS is sealed client-side and never extracted server-side.

## Auth Flow

Authentication is operator-blind: the server verifies an Argon2id password *proof* and returns ciphertext (the wrapped key bag); the actual unlock happens on the client.

1. `POST /api/login` — verifies the Argon2 PHC auth proof on `veyq_user` and returns a JSON `LoginResponse` with an opaque `sessionToken` (Bearer), the `user`, and the wrapped `keyBag`. No cookies are set.
2. Protected calls send `Authorization: Bearer <token>`. Each handler resolves it imperatively via `SessionService.resolve(...)`; there is no Quarkus `SecurityIdentity`/`HttpSecurityPolicy` mechanism. Access control is enforced by guards — `ScopeAccessGuard` (a valid grant + ACTIVE membership), `TenantAdminGuard` — operating on scope/tenant IDs.
3. There is **no token refresh or rotation**. Sessions have a long TTL and are simply revoked on logout.
4. `DELETE /api/me/session` — invalidates the current Bearer session server-side (`SessionService.invalidate`).

The `/api/me/*` surface exposes the caller's own wrapped material and memberships: `grants`, `groups`, `spaces`, `personal-scope`, `key-wraps/{unlockMethod}`, `identity/rotate`.

## Rate Limiting

`RateLimitFilter` (a JAX-RS `ContainerRequestFilter`) and `RateLimiter` (`@ApplicationScoped`, a `ConcurrentHashMap` of fixed-window counters) throttle abuse-prone unauthenticated endpoints. The client IP is resolved by `RateLimitFilter.resolveClientIp(peer, xff)`, which honors `X-Forwarded-For` **only** when the socket peer is a configured trusted proxy (so a client cannot mint fresh buckets by spoofing XFF). Exceeding a limit returns `429` with a `Retry-After` header. Rate limiting is disabled in dev/test. All buckets are keyed `"<name>|<ip>"` with a 60-second window:

| Bucket | Limit / window | Method · path |
| --- | --- | --- |
| `login` | 10 / min | POST `/api/login` |
| `reset-request` | 5 / min | POST `/api/me/password-reset/request` |
| `reset` | 15 / min | POST `/api/me/password-reset/` |
| `invite` | 30 / min | POST `/api/tenant-invites/` |
| `invite-read` | 60 / min | GET `/api/tenant-invites/` (existence/metadata) |
| `share-upload` | 60 / min | POST `/api/public/shares/` |
| `share-chunk` | 600 / min | PUT public-share chunk uploads |

In addition to these request-count buckets there is a per-IP **byte-budget** egress window (`isOverByteBudget`/`recordBytes`, default 500 MiB/min) for unauthenticated public-share GETs, so a large-gallery viewer making many small chunk GETs is not 429'd on request count while bulk egress is still bounded.

## Setup and Invitation Flow

1. `GET /api/setup` — `SetupStatus { setupRequired, suites, appBaseUrl }`; tells the frontend whether initial owner setup is needed and advertises the supported crypto suites.
2. `POST /api/setup/owner` — creates the first server owner as an E2EE registration: the client supplies its identity public key, wrapped private key, master-key wraps, recovery tag, auth verifier and personal-scope keys (`CreateServerOwnerRequest`). The `veyq_server_setup` row (`SetupGate`) locks setup to a single owner.
3. Member invites are issued via `POST /api/tenants/{tenantId}/member-invites` (and `.../member-invites/email`), keyed by tenant **ID**. The invite carries sealed key material, not a plaintext password.
4. Acceptance/registration goes through `/api/tenant-invites/{inviteId}/...` (`material`, `claim`, `accept`, `register`) — an E2EE key-material exchange, not a password-into-cookie flow.

## Permissions

- Server-wide: `ServerRole.ServerOwner` / `ServerRole.ServerMember` (`Identity.kt`). Server actions check the role imperatively (e.g. `TenantProvisioningHandler`); there is no `@ServerRolesRequired` annotation.
- Tenant: tenant-admin actions are gated by `TenantAdminGuard`; routes use `{tenantId}`/`{scopeId}`. There is no membership filter or `tenantName` routing.
- Group/scope: access is gated by `ScopeAccessGuard` (a valid `veyq_scope_grant` + ACTIVE membership). Group roles live in `veyq_group_membership.role`.

Crucially, "visibility" is **cryptographic**: a member can only read a scope they hold a grant for, because only that grant unwraps the scope content key. The server enforces grant presence; the client enforces confidentiality.

## API-first Contract

- Each backend domain has its own OpenAPI file under `docs/api/`: `auth`, `identity`, `keys`, `setup`, `tenants`, `spaces`, `memory`, `content`, `media`, `common`.
- Operations carry `summary`, `description`, examples, error cases and implementation-status hints.
- The Gradle build (`gradle/openapi.gradle.kts`) generates JAX-RS interfaces + Kotlin models; resource classes implement the generated interfaces directly and stay thin (HTTP adapters), delegating to handlers.
- Mappers convert domain types to generated API models at the resource/handler boundary (e.g. `NodeApiMapper`, `MediaApiMapper`).
- The Vue frontend generates TypeScript clients from the same specs.

## Backend Modularity

The backend stays API-first, but internal modularity must not mirror the breadth of an OpenAPI spec or a generated interface. The codebase is **feature-sliced**: `features/<area>/<usecase>/{Resource,Handler}` (e.g. `features/account/login/`, `features/memory/journallive/`, `features/admin/setupserver/`), with cross-cutting concerns in `shared/{security,crypto,content,keymgmt,storage}`.

- Resource classes are HTTP adapters: they implement generated API interfaces and handle JAX-RS `Response`, headers, multipart/temp files and parameter normalization. Business logic and longer-lived orchestration belong in handlers/application services.
- Services are cut by business responsibility or clear technical capability, not by API specs or endpoint lists. A service should be describable in one short sentence.
- Constructor dependencies are a refactoring signal: from ~6–9 dependencies, review the responsibility; from ten on, actively look for separable policies, resolvers, mappers, stores or use cases.
- Use-case services and technical infrastructure stay separate (a media-upload use case decides about node, scope, uploader and result; a resumable-upload session store only knows upload IDs, chunks, TTL and cleanup).
- Access decisions live in small guard/resolver components (`ScopeAccessGuard`, `TenantAdminGuard`, `NodeStructure.requireContainment`) rather than being embedded in large services.
- Business package dependencies stay directed; shared rules are modeled through neutral components or small interfaces.
- Mappers produce target models directly from the richer source model; API DTOs are end products, not intermediate representations, and stay at resource/mapper/handler boundaries.
- Entities and repositories should not grow into catch-all files.

## Memory and Media Access

Folders, memories and chapters are `veyq_node` rows belonging to exactly one scope (`veyq_node.scope_id`); a child inherits its scope by belonging to it. Media attach to a node and live in the same scope. Read/write is possible within a scope the caller holds a grant for — and only then can the client unwrap its content key.

Media are addressed through scope/node paths: `/api/scopes/{scopeId}/nodes/{nodeId}/media` for the per-node list, and `/api/scopes/{scopeId}/blobs/{blobId}/chunks/{i}` for binary frames (uploaded and re-fetched frame-by-frame, resumable). There are no public 16-character media IDs and no tenant-name routing. Identical content within a scope can dedup to the same `veyq_blob` (via the blinded dedup token) while remaining separate `veyq_media` attachments; a blob is deleted once no media still references it.

GPS/EXIF is **not** extracted server-side — it is sealed per-media client-side. The frontend can render an opt-in map (OpenStreetMap / Apple Maps) from client-decrypted coordinates; external tiles are a local, per-domain browser opt-in that can be revoked in settings.

## Memory and Journal Layer

The memory tree (`veyq_node`) and its media/track/journal children are the active business layer: tenant home, personal and group areas, folders, and memory detail all map onto node navigation under a scope.

Tracks (`veyq_memory_track`) are stored as opaque `sealed_content`; there is no server-side GPX parsing. There are **no** playlist or album features.

Journals are live-editable, operator-blind. A web editor requests a short-lived ticket (`POST /api/journal-live/ticket`) and connects to an **in-process WebSocket relay** (`JournalRelay`, built on `quarkus-websockets-next`) that forwards **sealed Yjs ciphertext** between peers — the server never sees plaintext Markdown. Persistence is client-driven: a sealed Yjs snapshot plus an append-only sealed update log in `veyq_memory_journal`(+`_update`); the client writes the Markdown projection on compaction. REST read/update remain as a fallback for non-live clients. Per-message size is capped (`websockets-next.server.max-message-size`) as anti-abuse.

Public shares (`veyq_share`) are separate resources on a node subtree, gated by the share secret and a `wrapped_content_key` (not session cookies). Paths are under `/api/public/shares/{shareId}/...`. Guest contribution (where the share role allows) uploads through the same blob path, throttled by the `share-upload`/`share-chunk` rate buckets and a per-share byte/session cap.

## Testing

Backend tests run with Quarkus Test on H2 in-memory (the `%test` default; not MySQL-compatibility mode) with Flyway migrate at startup. Each slice boots a fresh in-memory DB because every suite registers its own single-owner setup.

Covered are:

- the Argon2id auth-proof verification (suite v1 parameters)
- Bearer session issue/resolve/invalidate (no cookies, no rotation)
- the setup flow and single-server-owner lock
- tenant provisioning and admin guards
- scope grants, group membership and `ScopeAccessGuard`
- the node tree invariants (containment, slug tokens, ordering)
- the blob upload/dedup/resume path and per-share guest-upload caps
- rate-limit client-IP resolution and bucket behavior
- the operator-blind journal CRDT relay
- cross-platform crypto **conformance vectors** (`docs/api/crypto-vectors/v1.json`) on backend + web + iOS + Android
- OpenAPI contract binding (generated interfaces ↔ resources)
- frontend codegen, NDJSON parsing, upload composables and UI components via Vitest
