# Veyq Product Future Work

This document turns the Memory product context into follow-up topics for developers and agents. Items here are not all implementation tasks yet. Many need an ADR, API proposal or UX sketch first.

## Needs ADRs

- Decide sync semantics: last-write-wins now, ETags or conflict resolution later.
- Decide privacy model for person/participant pages.
- Decide optional central services: push gateway, managed hosting, update/license services.
- Decide E2E encryption stance for V1 and what metadata remains server-visible.

## Needs Domain Modeling

- Journal as exactly one Markdown document per Memory.
- Lightweight embed references in journal Markdown.
- Playlist reference and cached metadata model.
- Track model and relationship to map views.
- Weather metadata model.
- Participant/person model with optional user linkage.
- Explicit Memory participation and profile visibility rules.
- Virtual uploader buckets beyond the current anonymous share identity.
- Live Photo asset representation.

## Needs API Design

- Tenant domain change flow, including ownership/permission rules, validation, invite-link impact and reverse-proxy assumptions.
- Bulk image download for internal Memory views and non-uploader public shares, including authorization, subtree scope, ZIP/streaming behavior and media visibility rules.
- Localized download timestamps for Memory ZIPs, including whether to store a user time zone in the profile or pass one per download, plus fallback behavior for public shares without an authenticated user.
- Memory edit/delete UX follow-through, including confirmation copy and optimistic refresh behavior.
- Cover selection and cover removal flows for Memories.
- Parent Memory aggregate summary endpoints beyond media list aggregation.
- Memory header summary endpoint.
- Journal read/update endpoint.
- Playlist attach/update/remove endpoints.
- Track upload/list/detail endpoints.
- Standalone public upload-link flow beyond Memory shares.
- Person/participant endpoints that avoid cross-Memory graph leakage.
- Instance branding metadata for native apps and deep links.
- Keep API-first contracts complete with preconditions, examples, error cases and `x-implementation-status`.
- Preserve generated backend/frontend API model usage for new Memory APIs.

## Needs UX Design

- Download-all-images action for internal Memory views and eligible public share views, hidden for uploader-only shares.
- Memory detail next pass with `Journal`, `Map`, `Tracks` and richer `Unter-Memories` tabs.
- Memory header with cover, time range, places, weather, playlist, participants and track summary.
- Child Memory navigation without file-system feel.
- Parent Memory aggregation indicators.
- Wrong-upload recovery flow when assets are not movable.
- Timestamp correction UI.
- Public share UX polish beyond the baseline Memory view.
- Standalone public upload-link flow for anonymous contributors.
- Richer uploader attribution and privacy modes.
- Tenant switcher.
- Demo Memory and empty-state strategy.
- "Open in App" banner and universal link behavior.
- Server user management.
- Tenant user invite flow where admins create users only with role and email, and invited users set display name and password through the invite link.
- Live search fields where useful.
- Email-domain masking in user search or invite flows where privacy requires it.
- Group creation.
- Group management.

## Needs Technical Spikes

- Media derivation pipeline: WebP thumbnails, previews, H264 MP4 previews, streaming versions.
- Local filesystem storage layout for originals and derived assets.
- Video preview and streaming performance.
- EXIF extraction and correction persistence.
- GPS track parsing and map rendering.
- Playlist metadata fetching and caching for Apple Music and Spotify.
- Weather provider integration.
- Face recognition and image classification feasibility, including ARM hosting.
- Browser/mobile-side analysis feasibility under E2E constraints.
- Semantic search architecture under privacy constraints.
- Push gateway design for APNS/FCM with self-hosted instances.
- Federation and remote share bookmarking.
- Soft-delete lifecycle for managed shares if share lists need long-term cleanup without losing anonymous upload attribution.

## V1 Guardrails

- Keep comments out of V1.
- Keep local accounts first; passkeys can come later.
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
- Memory navigation, folder navigation, Memory CRUD and direct child traversal exist as API-first backend layers.
- Memory-scoped media upload, listing and timestamp correction exist.
- Public Memory sharing exists with optional passwords, anonymous identities and share roles.
- `includeChildren=true` aggregates media from descendant Memories while preserving direct asset ownership.
- Album/Explorer APIs, frontend flows and database tables have been removed.
- ADRs already capture Memory terminology, direct asset ownership, basic tree invariants and the Album removal cut.
