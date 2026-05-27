# veyq Product Future Work

This document turns the Memory product context into follow-up topics for developers and agents. Items here are not all implementation tasks yet. Many need an ADR, API proposal or UX sketch first.

## Needs ADRs

- Decide sync semantics: last-write-wins now, ETags or conflict resolution later.
- Refine the privacy model for person/participant pages beyond [ADR 0011](../adr/0011-persisted-shares-and-person-resolution.md), especially profile fields, confirmation flows and public-share behavior.
- Decide optional central services: push gateway, managed hosting, update/license services.
- Decide E2E encryption stance for V1 and what metadata remains server-visible.

## Needs Domain Modeling

- Rich location model beyond V1 GPS metadata, including named places, map clusters and relationships between media locations and track geometry.
- Weather metadata model.
- Instance-wide participant/person model with optional user linkage, viewer-scoped identity resolution and no tenant-directory leakage.
- Explicit Memory participation, face occurrence, person-match confirmation and profile visibility rules.
- Persisted Memory share entries that let a tenant, group or user save a source Memory share without copying media or changing source ownership.
- Virtual uploader buckets beyond the current anonymous share identity.
- Live Photo asset representation.

## Needs API Design

- Tenant domain change flow, including ownership/permission rules, validation, invite-link impact and reverse-proxy assumptions.
- Localized download timestamps for Memory ZIPs, including whether to store a user time zone in the profile or pass one per download, plus fallback behavior for public shares without an authenticated user.
- Memory edit/delete UX follow-through, including confirmation copy and optimistic refresh behavior.
- Parent Memory aggregate summary endpoints beyond media list aggregation.
- Track detail/search/update extensions beyond the V1 GPX upload/list/update/delete/content endpoints.
- Person/participant endpoints that avoid cross-Memory graph leakage and return the same empty result shape for unknown, unauthorized and no-visible-match searches.
- Persisted share endpoints for saving, listing, opening and removing remote/shared Memory entries in a target tenant, group or personal scope.
- Instance branding metadata for native apps and deep links.
- Keep API-first contracts complete with preconditions, examples, error cases and `x-implementation-status`.
- Preserve generated backend/frontend API model usage for new Memory APIs.

## Needs UX Design

- Memory detail next pass with combined media/track maps and richer contextual tabs.
- Memory header with places, weather, playlist, participants and track summary.
- Child Memory navigation without file-system feel.
- Parent Memory aggregation indicators.
- Wrong-upload recovery flow when assets are not movable.
- Richer uploader attribution and privacy modes.
- Optional demo Memory and seed-data strategy for public demos.
- "Open in App" banner and universal link behavior.
- Live search fields where useful.
- Remote/shared Memory navigation that clearly shows source tenant/domain, current share access state and target-side visibility without making the source Memory look copied.
- Email-domain masking in user search or invite flows where privacy requires it.

## Needs Technical Spikes

- Media derivation pipeline beyond V1 poster previews: WebP thumbnails, H264 MP4 fallbacks and streaming versions.
- Adaptive video delivery with lower-resolution variants for different network strengths.
- Map hardening beyond V1: marker clustering, manual geotagging, track overlays on Leaflet, offline/self-hosted tiles and OpenStreetMap consent reset UX.
- Playlist metadata fetching, caching and optional OAuth-backed playback for Apple Music and Spotify.
- Weather provider integration.
- Face recognition and image classification feasibility, including ARM hosting.
- Browser/mobile-side analysis feasibility under E2E constraints.
- Semantic search architecture under privacy constraints.
- Face recognition pipeline with instance-wide internal matching, viewer-scoped person resolution and non-enumerable search responses.
- Push gateway design for APNS/FCM with self-hosted instances.
- Federation and remote share bookmarking based on persisted shares rather than direct Memory copying.
- Soft-delete lifecycle for managed shares if share lists need long-term cleanup without losing anonymous upload attribution.

## V1 Guardrails

- Keep V1 video playback original-only. Generate poster previews, but defer transcodes, HLS/DASH and adaptive quality selection.
- Keep comments out of V1.
- Keep local accounts and passkeys first; external identity providers can come later.
- Keep storage local-filesystem compatible.
- Avoid manual asset sort fields in V1.
- Avoid asset moves in V1 unless a later ADR explicitly changes this.
- Avoid self-hosted music files; use external streaming references.
- Avoid public person graph features.
- Keep central infrastructure optional.
- Keep reverse-proxy responsibility for domains, TLS and path rewrites unless a later ADR changes this.
- Keep tenant and group roles separate from global security roles.
- Keep public media identifiers separate from internal database IDs.

## Implemented Baseline To Build On

- Memory UI v1 is the active library surface.
- Appearance can be set to system, light or dark; app tokens and PrimeVue theme behavior stay aligned.
- Memory navigation, folder navigation, Memory CRUD, direct child traversal, headers, covers and favorites exist as API-first backend layers.
- Memory-scoped media upload, resumable chunk uploads, listing, previews, timestamp correction and ZIP downloads exist.
- Journal read/update and Markdown embed rendering exist for private Memories and eligible public shares.
- Public Memory sharing exists with optional passwords, anonymous identities, share roles and upload-only links.
- Media maps exist for geotagged photos/videos with OpenStreetMap opt-in/opt-out stored as a global browser setting.
- GPX tracks exist with activity types, public-share support, original download, archive inclusion and journal embeds.
- Memory playlists exist with one Spotify, Apple Music, YouTube, SoundCloud or Deezer provider link, public-share support, external opening and journal embeds.
- Tenant user invitation, tenant switching and group management exist.
- `includeChildren=true` aggregates media from descendant Memories while preserving direct asset ownership.
- Album/Explorer APIs, frontend flows and database tables have been removed.
- ADRs already capture Memory terminology, direct asset ownership, basic tree invariants and the Album removal cut.
