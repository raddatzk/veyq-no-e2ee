# veyq Memory Product Context

This document captures the product and architecture context discussed for veyq so future work can turn it into ADRs, API contracts, UI designs and implementation tasks.

It is intentionally broader than the current implementation. Memory UI v1 and Memory APIs are now the active baseline. Folders remain organizational; albums are no longer part of the active product, API or database schema.

## Product Positioning

veyq is a self-hostable memory sharing product. It should not feel like a generic gallery, cloud drive or folder browser.

The core promise is a private archive that can also be shared beautifully with friends and family. The original inspiration was a travel and vacation video portfolio, but the concept generalizes to trips, events, weddings, family moments and personal archives.

Product qualities:

- Modern, minimal and dark-theme-friendly.
- Focused on memories instead of files.
- Curated and story-oriented rather than storage-oriented.
- Suitable for self-hosting on arbitrary domains.
- Able to support managed hosting later without making `veyq.app` mandatory.

Branding notes:

- Project name: `veyq`.
- Primary domain idea: `veyq.app`.
- Logo direction: minimal, abstract geometric `V`, usable on dark backgrounds.

## Core Domain Concept

The final product concept is `Memory`, replacing earlier `Album` or `Collection` framing for new product language.

A Memory can be small or large:

- A single moment.
- A day.
- A trip.
- A wedding.
- A larger journey containing child memories.

Memories form a nested tree:

- A Memory may have a parent Memory.
- A Memory may have child Memories.
- Parent Memories aggregate content from their child Memories.
- Sharing can apply to a Memory subtree.

Each asset belongs to exactly one Memory. Assets are not movable in V1. If an asset is assigned to the wrong Memory, the intended workflow is delete and re-upload rather than move. This keeps ownership, sync and audit behavior simpler.

Folders remain organizational only. They are not rich content objects and should not compete with Memories as the product center.

## Memory Surface

A Memory is a mixed media and context object. It may contain:

- Gallery media.
- One journal.
- Map context.
- GPS tracks.
- Child Memories.
- Weather context.
- Playlist or music context.
- Participants.
- Time range and places.

Proposed tabs inside a Memory:

- `Gallery`
- `Journal`
- `Map`
- `Tracks`
- `Child Memories`

Proposed Memory header:

- Cover image or cover video.
- Time range.
- Places.
- Weather summary.
- Header playlist player for the featured playlist, with playlist-level previous/next controls when the Memory has multiple playlists.
- Participants.
- Track summary.

## Assets and Ordering

Assets are uploaded directly into either a parent Memory or a child Memory.

Asset ordering in V1 should be derived from EXIF or `takenAt`. There should be a way to correct wrong EXIF timestamps, but no separate manual sort field in V1.

Live Photos should be modeled as one asset with an image plus a short video component.

Original uploads must be stored unchanged. Derived media can include:

- WebP thumbnails.
- JPEG or WebP previews.
- H264 MP4 video previews.
- Streaming versions for videos.

## Journal

Each Memory has exactly one journal.

The journal should be Markdown-based with a lightweight veyq-specific embed syntax. It should support references to assets, playlists and other contextual elements without turning into a heavy CMS.

The supported Markdown and `veyq:` embed syntax are documented in [Journal Markdown](journal-markdown.md).

## Music and Playlists

veyq should store external streaming references only, not self-hosted MP3 files. The playlist model is defined by [ADR 0010](../adr/0010-memory-playlist-links.md), with implementation detail in [Memory Playlists](playlists.md).

Supported references:

- Apple Music links.
- Spotify links.
- Optional deep links.

Playlist context can attach to trips, events or small moments. A veyq playlist has one provider link in V1. A Memory can mark one playlist as featured for the Memory Header link; if no playlist is featured, the first playlist is used as the header default. Header previous/next controls switch between playlists. Automatic cross-provider playlist matching and multiple provider links are deferred.

Playlist metadata may be cached for display later, but V1 opens provider links externally rather than embedding playback widgets.

## Maps, Tracks and Weather

GPS and track data are first-class context for travel and outdoor Memories.

The product should have a separate Tracks tab rather than hiding track data only inside a map. Maps and tracks can still be linked visually.

Weather can enrich Memory headers and timelines where useful.

## Sharing and Upload Links

veyq should support public sharing without becoming a social network.

Sharing ideas:

- Publicly shareable Memories.
- Shareable Memory subtrees.
- Granular permissions.
- Public upload links similar to file-request links.
- Example use case: a wedding DJ uploads their own photos into a wedding Memory.

Permission ideas:

- `VIEW_MEMORY`
- `VIEW_OWN_UPLOADS`
- `VIEW_ALL_UPLOADS`
- `UPLOAD`
- `EDIT_OWN_UPLOADS`
- `EDIT_MEMORY`

Share roles can map onto those permissions, for example:

- Viewer links.
- Contributor links.
- Uploader links.
- Editor links.

Public shares must avoid social graph leakage. Public pages should not reveal where a person appears elsewhere unless the viewer is authorized for those other Memories.

## Upload Ownership

Uploads can come from registered or anonymous uploaders.

Anonymous uploaders should provide a display name. The uploader of an asset must remain traceable, and the UI should be able to show who uploaded what.

A useful mental model is a virtual bucket per uploader inside a Memory. This can support privacy-sensitive collaborative uploads where uploaders may see only their own uploads or all uploads depending on permissions.

## People and Participants

People may be recognized across the instance, but their visible identity and profile surfaces are viewer-scoped.

A person may optionally link to a global user account, but the person model should not require a user account. Public participant pages should remain minimal.

Authenticated users may see other Memories for a person only if they are authorized for those Memories. There must be no public "where else does this person appear" feature.

Person lookup and search must be non-enumerable. The product should not reveal whether a person exists, exists but is unauthorized, or exists but has no matches in the viewer's visible Memories. A viewer may resolve person identities only through their own tenant memberships, explicit visible Memory participation or visible shared content.

## Tenants, Groups and Scopes

One veyq server can host multiple tenants.

Users are global to the server and may belong to multiple tenants. The UI should support fast tenant switching.

Content scopes:

- Tenant.
- Group.
- Personal.

Group model:

- No nested groups.
- All group members have the same content rights.
- Group admins mostly manage membership and settings.
- Content should not have restrictive owner concepts inside a group.

## Federation and Remote Sharing

veyq should remain self-hosted and domain-flexible.

Future ideas:

- Persist or bookmark Memory shares from another tenant or instance without copying the source Memory.
- Let a target tenant, group or user decide who sees the persisted share entry while source access remains controlled by the original share.
- No direct copying in V1.
- Later federation, import or offline support.
- Home-instance concept.
- Users can stay on their own domain.
- No central `veyq.app` requirement.

## Apps and Deep Links

Mobile and web architecture:

- Vue web app.
- Quarkus backend.
- Native mobile apps later.

Deep-link ideas:

- Universal Links.
- Support arbitrary self-hosted domains.
- Official veyq app.
- Optional white-label apps.
- Instance branding metadata.
- "Open in App" banner.

## Demo and Onboarding

veyq should be understandable through examples rather than a heavy tutorial.

Ideas:

- Public demo instance.
- Demo Memories with seed data.
- Inline hints and empty states.
- Example Memories instead of modal-heavy onboarding.

## Backend and Sync Decisions

Backend direction:

- Quarkus.
- Kotlin backend codebase.
- Gradle wrapper.
- Flyway-managed database migrations.
- Hibernate ORM Panache.
- MariaDB in production-oriented runtime.
- Local accounts and passkeys first.
- Local filesystem storage first.
- Docker volumes.
- No comments in V1.

Synchronization:

- Last-write-wins initially.
- Later conflict resolution or ETag-based strategies can be introduced where needed.

## Current Implementation Notes

These points are already documented in the README and describe the current system baseline rather than the full Memory target model.

Runtime and deployment:

- veyq is currently a Quarkus/Vue application.
- The frontend is Vue 3 with TypeScript.
- The backend serves API and the built frontend.
- Docker Compose is the local runtime path.
- The application exposes a health endpoint at `/q/health`.
- Domains, TLS and root-path rewrites are intentionally handled by an admin-controlled reverse proxy in front of veyq.
- The backend currently does not model host mappings; tenant scoping uses a technical tenant `name` in the path.
- The real `infra/.env` is not versioned and contains domains, database credentials, reset credentials and the Let's Encrypt email.
- Docker Compose reads the same `.env` file for variable substitution into the app, database, Traefik and optional reset sidecar services.
- A reset sidecar exists for test instances. It clears veyq database tables except `flyway_schema_history`, clears the media volume and restarts the app while keeping schema and volumes.

Setup and accounts:

- A fresh instance starts without seed users.
- Initial setup is two-step: create the first `ServerOwner`, then optionally create the first tenant with a domain.
- Admin setup is locked once any user exists.
- Only global `ServerOwner` users may create new tenants.
- Each tenant currently has exactly one domain when created.

Current routing and scopes:

- Tenants are addressed by technical path names, for example `/friends` in the frontend and `/api/friends/groups` in the backend.
- Current Memory navigation scopes use paths such as `/urlaub` for tenant scope, `/@familie/urlaub` for group scope and `/~me/urlaub` for personal scope.
- All members of a visible scope may currently read and write there.
- Media belong directly to one Memory and inherit that Memory's scope.

API-first workflow:

- OpenAPI 3.1 specs under `docs/api` are the public backend API target contracts.
- Each operation should document preconditions, examples, error cases and `x-implementation-status`.
- Backend resources implement generated JAX-RS interfaces.
- Backend JSON payloads are generated Kotlin models rather than handwritten public DTOs.
- The frontend generates TypeScript clients and models from the same specs.
- Frontend feature modules keep slim facade imports that delegate to generated clients.
- NDJSON streaming and binary/chunk upload flows remain adapter-backed while using generated request and response types.

Current OpenAPI domains:

- `auth`
- `setup`
- `users`
- `tenants`
- `groups`
- `server`
- `settings`
- `memories`
- `frontend`

Authentication and security:

- Authentication uses `HttpOnly` access and refresh token cookies.
- Refresh rotates tokens and sets a new cookie pair.
- Logout invalidates current tokens and clears cookies.
- `VeyqHttpSecurityPolicy` allows only explicitly public API paths without login.
- `ServerOwner` is a global server role.
- Tenant owners can invite tenant members by email.
- Existing global users are reused by email during invitation; otherwise a pending user is created.
- Invitation links are built on the assigned tenant domain.
- Tenant membership is checked for routes with `{tenantName}`.
- Tenant and group roles are not mixed into global `SecurityIdentity` roles.

Current media access:

- Media have an internal `Long` ID and a public 16-character `publicId`.
- APIs expose the `publicId` as `id`.
- Binary media content supports range requests.
- Memory uploads use multipart form uploads to a concrete Memory.

Current test coverage:

- Backend tests cover password hashing, cookies, token rotation, token cleanup, auth, setup, tenant handling, user management, settings, Memory navigation, Memory ownership, upload, media streaming, GPX parsing and playlist provider URL parsing.
- Contract tests cover OpenAPI descriptions, examples, preconditions, implemented endpoints, resource interface binding and generated payload usage.
- Frontend checks include `vue-tsc`, ESLint, Vitest and Vite build through `npm run check`; component tests cover Memory detail flows, maps/tracks/playlists, consent helpers and settings panels.

## Privacy and API Rules

APIs must return only authorized Memories and related entities.

Privacy rules:

- No public cross-Memory graph for people.
- No social-graph leakage through public shares.
- Public participant views stay minimal.
- Authenticated access can reveal richer relationships only through authorized Memories.

## Encryption and Search

End-to-end encryption remains an open research topic because it conflicts with server-side analysis, previews, search and AI features.

Discussed directions:

- Client-side processing.
- Local indexes.
- Server-side metadata where acceptable.
- Hybrid approaches.
- Search under E2E constraints.
- Browser or mobile AI.
- Processing only when media is loaded.
- Battery impact for mobile face recognition.

## AI and Asset Analysis

Future AI-assisted media analysis ideas:

- Face recognition.
- Image classification.
- Semantic search.
- InsightFace exploration.
- ARM hosting constraints.

Any AI feature must respect the privacy model and the self-hosting story.

## Push Notifications

Push notification ideas:

- APNS and FCM for mobile apps.
- Optional central push gateway.
- Self-hosted instances register with the gateway.
- Comparable tradeoffs to Nextcloud.
- Central infrastructure must stay optional.

## Hosting, Licensing and Distribution

Licensing and business-model ideas:

- AGPL as a possible base.
- Trademark protection for the veyq name and logo.
- Community and commercial model.
- Official hosting offers.
- No hard DRM strategy.
- Friendly upselling.
- Optional signed license tokens for commercial features.

Managed hosting and distribution:

- Prefer EU hosting.
- Public Docker images.
- GitHub Actions.
- Signed containers.
- SBOM and provenance.
- Open deployment manifests.
- Managed services where appropriate.

## Frontend and Internationalization

Frontend direction:

- Vue frontend.
- Translations live in the frontend bundle.
- Hashed assets.
- Aggressive browser caching for static frontend assets.
- Backend returns stable error codes.
- Frontend localizes user-facing errors and labels.

## Documentation Strategy

veyq should be easy for human developers and AI agents to work on.

Documentation ideas:

- `AGENTS.md` as context entry point.
- ADRs for meaningful architecture and product decisions.
- `docs/adr` structure.
- Operational rules.
- Context documents for Codex and other LLM agents.

Development may intentionally combine ChatGPT, Codex, local LLMs and tools such as Open WebUI. Product knowledge should be centralized in repository docs instead of remaining only in chats.
