# veyq Architecture

## Target Picture

veyq runs as a single instance with global users and multiple tenants. External domains, TLS and root-path rewrites are managed by an upstream proxy. The backend knows no host mappings and scopes business data via the tenant name in the path.

## Runtime

- The Quarkus backend serves the API and the embedded Vue frontend.
- MariaDB is the production database.
- Flyway manages the schema through versioned migrations.
- Docker Compose starts the app and the database locally.
- The OpenAPI specs under `docs/api` are the public API target contract.
- Backend and frontend use generated API artifacts from the same specs.

## Domain Model

- `veyq_user`
  - global users including `email`, `password_hash`, `display_name`, `role`, `password_set_at`
- `veyq_user_invitation`
  - hashed invitation tokens, expiry date, accepted/revoked timestamps
- `veyq_refresh_token` / `veyq_access_token`
  - hashed tokens, expiry dates, revocation and refresh rotation
- `veyq_tenant`
  - tenant-scoped area with a unique `name` and a unique `domain`
- `veyq_tenant_member`
  - user membership per tenant with `TenantOwner` or `TenantMember`
- `veyq_tenant_group`
  - groups within a tenant
- `veyq_tenant_group_member`
  - group membership with `GroupOwner` or `GroupMember`
- `veyq_media_blob`
  - tenant-scoped, content-addressed originals and previews with internal SHA-256, storage key, preview storage key, MIME type, byte size and extracted camera/GPS/dimension data
- `veyq_media`
  - memory-specific media assets with internal `id`, public 16-character `public_id`, tenant, uploader or share identity, access scope, direct memory assignment, title, original filename and a reference to `veyq_media_blob`
- `veyq_memory`
  - the new memory target structure with public `public_id`, parent memory, access scope, cover media and direct media assignment
- `veyq_memory_track`
  - memory-specific GPX tracks with public `public_id`, title, activity type, bounding box, distance, point count, time span and a storage key for the original GPX file
- `veyq_memory_share` / `veyq_memory_share_identity` / share token tables
  - public share links for memory subtrees, anonymous share identities, share-scoped access/refresh tokens and roles for viewer, contributor, manager and uploader
- `veyq_config`
  - technical app configuration such as Argon2id parameters

## Auth Flow

1. `POST /api/auth/login`
   - validates email/password
   - issues access and refresh tokens
   - sets both tokens as cookies

2. Protected API calls
   - `VeyqAuthenticationMechanism` reads the matching cookie
   - `VeyqIdentityProvider` validates the token and builds the `SecurityIdentity`
   - `RequestContextFilter` populates the request-scoped `RequestContext`
   - `VeyqHttpSecurityPolicy` blocks anonymous requests to protected `/api/**` paths

3. `POST /api/auth/refresh`
   - reads the refresh cookie
   - rotates the token
   - sets a new cookie pair

4. `POST /api/auth/logout`
   - invalidates the current tokens
   - clears both cookies

## Setup and Invitation Flow

1. `GET /api/setup`
   - reports whether initial admin setup or tenant creation should be offered in the frontend

2. `POST /api/setup/admin`
   - creates the first global server owner
   - is locked once a user exists

3. `POST /api/tenants/{tenantName}/users/invite`
   - lets a TenantOwner invite a user into the tenant by email
   - uses an existing global user with the same email or creates a pending user
   - creates an invitation with a TTL and sends a link in the format `https://{tenant.domain}/invite/{userId}/{token}`

4. `POST /api/users/{userId}/invite/accept`
   - accepts the invitation with `{ password, token }`
   - sets the password and auth cookies

## Permissions

Global rights:

- `ServerRole.ServerOwner`
- `ServerRole.ServerMember`
- `@ServerRolesRequired(...)` protects server-wide actions such as `POST /api/tenants` and user management.

Tenant rights:

- `TenantMembershipFilter` applies automatically to routes with `@PathParam("tenantName")`.
- An unknown tenant yields `404`.
- An anonymous request yields `401`.
- A logged-in user without tenant membership yields `403`.
- `@TenantOwnerRequired` is intended only for owner-only tenant actions.

Group rights:

- Whoever creates a group becomes `GroupOwner`.
- `@GroupOwnerRequired` protects group administration.
- Normal group membership is separate from tenant membership.

## API-first Contract

- Each backend API domain has its own OpenAPI file: `auth`, `setup`, `users`, `tenants`, `groups`, `server`, `settings`, `memories`.
- Operations include `summary`, `description`, examples, error cases, `x-preconditions`, `x-implementation-status` and, where needed, role/membership hints.
- The Gradle build generates JAX-RS interfaces and Kotlin models. Resource classes implement the generated interfaces directly.
- Services convert between domain entities/enums and generated API models at domain boundaries.
- `OpenApiContractTest` verifies spec completeness, implemented endpoints, resource interface binding and that JSON signatures use the generated models.
- The Vue frontend generates TypeScript fetch clients into `frontend/src/api/generated/<domain>` and uses them via `frontend/src/api/generatedClient.ts`.
- Frontend-specific facades remain as stable imports; they encapsulate generated clients, error mapping, cookie refresh and media special cases.

## Backend Modularity

The backend layers should stay API-first, but internal modularity must not mirror the breadth of an OpenAPI spec or a generated interface.

- Resource classes are HTTP adapters. They implement generated API interfaces and handle JAX-RS `Response`, cookies, headers, multipart/temp files and simple parameter normalization. Business logic and longer-lived orchestration belong in smaller application services.
- Services are cut by business responsibility or clear technical capabilities, not by API specs, generated interfaces or endpoint lists. A service should be describable in a single short sentence.
- Constructor dependencies are a refactoring signal. From roughly six to nine dependencies, review the responsibility deliberately; from ten dependencies on, actively investigate a service for separable policies, resolvers, mappers, stores or use cases.
- Use-case services and technical infrastructure stay separate. A memory media upload use case decides about memory, scope, uploader, bucket and API result; a resumable upload session store only knows upload IDs, chunks, session metadata, TTL and cleanup.
- Visibility, roles, scope resolution, path resolution and share access belong in small policy or resolver components, for example `ShareAccessPolicy`, `MemoryVisibilityPolicy` or `LibraryPathResolver`, instead of being embedded in large services.
- Business package dependencies should stay directed. If `memory` builds on `library`, then `library` must not reach back directly into `memory`. Shared rules are modeled through neutral components or small interfaces.
- Mappers produce target models directly from the richer source model. Chains like `domain.toSummary().toSomethingElse()` are to be avoided, because API DTOs are end products and not intermediate representations.
- Generated API models stay at resource, mapper or application-service boundaries. Policies, technical services and storage components preferably work with domain objects or their own small command/result types.
- Shared service logic is preferably composed in. Base classes for tenant, scope, path or context logic increase coupling and should not be expanded further as a default pattern.
- Entities and repositories should not grow into unstructured catch-all files. New larger aggregates or repository groups get their own files or clearly bounded files.
- Refactorings happen incrementally and risk-based. First, clearly separable policies, resolvers, mappers, session stores, archive or derivative components are extracted; afterwards, broad use-case services are cut further.

## Memory and Media Access

Folders, memories and media have a single access scope:

- `TENANT`: all tenant members
- `GROUP`: members of the respective tenant group
- `USER`: only the respective user

Child folders and root-level memories in folders inherit the scope of their parent folder. Child memories inherit the scope of their parent memory. Media take on the scope of the memory they are uploaded into. Tenant membership is a precondition for every tenant API; within a visible scope, members may read and write.

Media are addressed publicly via `Media.publicId`. Memory media lists run through `/api/{tenantName}/memories/{memoryId}/media`; binary content and previews run through `/api/{tenantName}/memory-media/{mediaId}/...` and support range requests for original content. Internally, content is read via `Media.blob.storageKey`; hashes, blob IDs and storage keys are not exposed through public APIs.

GPS coordinates extracted from EXIF remain part of the media metadata. The frontend can build a Leaflet-based media map from them. External OpenStreetMap tiles are not loaded automatically; the decision lives as a global browser opt-in under `veyq:osm-tiles-enabled` and can be revoked from the map, the public share page or settings.

## Memory Layer

Memory UI and the Memory APIs are the active business layer. Tenant home, personal areas, group areas, folders and memory details are represented through memory navigation and folder and memory endpoints.

Memories are nestable and belong to exactly one tenant, group or user scope. Child memories inherit the scope of their parent memory. New memory media uploads write the direct `memory_id` to `veyq_media`; in the memory model an asset belongs to exactly one memory. Identical uploads within a tenant can reuse the same `veyq_media_blob` but remain separate `veyq_media` assets. `includeChildren=true` on memory media lists aggregates media from visible descendant memories without changing direct ownership.

Originals and previews belong to `veyq_media_blob`. Previews are stored per blob and reused. When a media asset is deleted, the blob is only removed once no other media still points to it. Explicit memory cover uploads remain separate memory files; covers can alternatively point to an existing memory media asset.

GPX tracks belong directly to a memory. Uploads are parsed server-side and stored with activity type, bounds, distance, point count and an optional time span. The original GPX file stays downloadable and is included in memory ZIP archives. Public shares may read, upload, edit or delete tracks depending on the role. Journal references to deleted tracks are preserved as Markdown and render as unavailable.

Memory playlists also belong directly to a memory. `veyq_memory_playlist` stores title, note, featured status and exactly one external playlist link with provider and external ID. Major line 1 supports Spotify, Apple Music, YouTube, SoundCloud and Deezer. Public shares may read playlists and, with contributor/manager rights, edit them depending on the role. External players are not embedded in major line 1; the UI opens the stored provider link externally. Journal references to deleted playlists are preserved as Markdown and render as unavailable.

Memory journals are stored as Markdown in `veyq_memory_journal`. Web editors can request a short-lived live session for internal memories and public manager shares and connect through the optional `veyq-collaboration` Hocuspocus/Yjs sidecar. The sidecar synchronizes open editor sessions, keeps active Yjs documents in memory only, and persists Markdown snapshots back to veyq over a sidecar-authenticated backend channel; the backend remains the authorization and embed-validation boundary. REST reads and REST updates remain as a fallback and for native read-only clients.

Public shares are separate resources on memory subtrees. Registered users who have internal access to the root memory receive a `redirectUrl` to the normal internal memory route when opening a share link. External visitors use share-scoped HttpOnly cookies on `/api/public-shares`; uploader-only shares stay limited to the focused upload flow.

The baseline migration creates the memory table, the direct `memory_id` assignment on media, tenant-scoped blob storage, memory GPX tracks and memory playlists. Album tables are removed from the schema; non-production legacy data is not migrated further.

## Testing

Backend tests run with Quarkus Test, H2 in MySQL mode and Flyway clean/migrate per test run.

Covered are:

- password hashing and Argon2id parameters
- cookie creation
- token rotation, revocation and cleanup
- auth endpoints
- the setup flow
- server-owner tenant creation
- the tenant membership filter
- group scoping and group owner
- scope-based memory/media visibility and `publicId`
- memory tree invariants, scope visibility, memory media ownership and cover rules
- public share roles, share cookies, upload flows, memory track behavior and memory playlist behavior
- OpenAPI contract tests for API-first binding
- frontend codegen facades, NDJSON parsing, upload composables and UI components via Vitest
