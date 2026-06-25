# veyq Product Future Work

This document lists deferred Memory product, architecture, API, UX and research candidates for developers and agents. It is not the implementation baseline and should not duplicate current behavior.

Current implemented behavior is documented in the feature catalog, OpenAPI contracts, ADRs and architecture notes:

- [Feature Catalog](features/README.md): source of truth for implemented feature behavior, acceptance criteria, test links and explicit coverage gaps.
- [API Contracts](../api): public API target contracts and implementation status.
- [Architecture](../architecture.md): current backend/frontend architecture and domain model.
- [ADRs](../adr/README.md): durable product and architecture decisions.
- [Capabilities](capabilities.md): advertised server capability IDs and compatibility metadata.

## Future Work Discipline

- Keep this file for work that is not yet part of the accepted implementation baseline.
- Do not use this file to describe implemented behavior in detail. If behavior exists, document it in the relevant feature YAML, OpenAPI contract, architecture doc, ADR or capability entry instead.
- When a future-work item becomes implementation work, first decide which artifact owns the truth: ADR for durable decisions, OpenAPI for public API behavior, feature YAML for acceptance criteria and tests, architecture docs for current system structure, or styleguides for product-facing UI rules.
- When implementation ships, remove or rewrite the future-work item so it only captures remaining open questions, follow-up scope or known gaps.
- Keep future-work entries narrow enough to be actionable. Split entries that mix ADR, API, UX, data model and infrastructure work unless the coupling is the point being researched.
- Avoid adding speculative implementation details unless they are constraints that should shape the decision. Prefer "needs ADR/API design/spike" language over preselecting storage tables, endpoints, providers or libraries too early.
- If a future-work item contradicts implemented behavior, update this file in the same change that updates the implementation-facing source of truth.

## Next Stewardship Priority

- Keep this Future Work inventory aligned with the feature catalog and implementation docs. New product or backend work should leave behind exactly one clear state: still deferred here, accepted and covered in the feature catalog, or intentionally captured as an ADR/API/architecture follow-up.
- Backend stewardship priority:
  1. Complete follow-ups for user lifecycle and first TOTP password-login support where needed by UX and recovery flows.
  2. Tenant-scoped email delivery settings.
  3. Localized email templates.
  4. Tenant domain change flow.
  5. ServerOwner web-managed configuration UI for selected bootstrap/configuration values.

## Needs ADRs

- Decide sync semantics: last-write-wins now, ETags or conflict resolution later.
- Decide live update semantics for Memory surfaces, including WebSocket versus SSE transport, event ordering, reconnect/replay behavior and how updates interact with existing REST list refreshes.
- Refine the privacy model for person pages beyond current tenant-user Memory participants and [ADR 0011](../adr/0011-persisted-shares-and-person-resolution.md), especially profile fields, confirmation flows and public-share behavior.
- Decide optional central services: push gateway, managed hosting, update/license services and the hosted offering split described in [Hosting and Operations Strategy](hosting-and-operations-strategy.md).
- Decide E2E encryption stance for major line 1 and what metadata remains server-visible.
- Decide TOTP recovery codes, ServerOwner reset/disable authority, tenant or server-owner enforcement policy and mobile auth handoff behavior beyond the first password-login TOTP support.
- Refine follow-up details for [ADR 0017](../adr/0017-user-lifecycle-and-tenant-membership-removal.md), including final UI copy, re-authentication prompts and operational recovery guidance.
- Decide whether veyq should add a simple per-scope file storage area ("Ablage") that runs parallel to Memories, and define the product boundary so it does not become a cloud-drive or Memory-media replacement.

## Needs Domain Modeling

- Rich location model beyond major line 1 GPS metadata, including named places, map clusters and relationships between media locations and track geometry.
- General Memory location model for an explicit place on a Memory itself, independent of media GPS metadata or uploaded GPX tracks.
- Weather metadata model.
- Person model beyond current tenant-user Memory participants, with optional user linkage, viewer-scoped identity resolution and no tenant-directory leakage.
- Face occurrence, person-match confirmation and profile visibility rules for recognized people who are not just explicit Memory participants.
- Persisted Memory share entries that let a tenant, group or user save a source Memory share without copying media or changing source ownership.
- Advanced uploader privacy modes and cross-Memory recovery beyond the current persistent uploader bucket model.
- Live Photo asset representation.
- Scope-bound Ablage folders and files separate from Memory folders and Memory media, with `TENANT`, `GROUP` and `USER` scope roots.

## Needs API Design

- Tenant domain change flow, including ownership/permission rules, validation, invite-link impact and reverse-proxy assumptions.
- Tenant-scoped email delivery settings so invitation, account and share emails can use sender identity, reply-to/contact details and branding that match the tenant domain while falling back to instance SMTP defaults.
- Localized email template model for invites, password resets, email changes and account/security notifications, including locale selection, tenant branding variables, safe fallbacks and template versioning.
- Optional TenantOwner approval mode for tenant registration links, including pending join requests, approve/reject semantics, expiration/revocation behavior, notifications, and privacy-preserving responses that do not expose tenant membership or account existence.
- Time-zone policy for Memory ZIP timestamps if locale-aware archive naming is not enough, including whether to store a user time zone in the profile or pass one per download.
- Memory media download timestamp policy so user-corrected or overridden media timestamps are carried into downloaded originals or ZIP archive entries where the target format can represent them.
- User account deletion/deactivation endpoints for [ADR 0017](../adr/0017-user-lifecycle-and-tenant-membership-removal.md), including permission checks, active-session invalidation, passkey/credential cleanup, tenant membership soft removal and anonymization of audit-relevant references.
- Mutation UX follow-up for [ADR 0020](../adr/0020-memory-and-folder-mutation-semantics.md), including affected-content counts, reusable same-space move target selection and error-specific copy.
- Parent Memory aggregate summary endpoints beyond media list aggregation.
- Live Memory event API for newly created, updated or deleted media, media buckets, tracks, playlists, journals, covers, child Memories and share-visible changes, with tenant/scope/public-share authorization filtering.
- Uploader-only public share delete semantics so an uploader can remove media uploaded through the same share identity without gaining broader Memory delete access or visibility into other uploaders' assets.
- Advanced track search, import and visualization beyond the current GPX upload/list/update/delete/content endpoints.
- Person endpoints beyond current Memory participants that avoid cross-Memory graph leakage and return the same empty result shape for unknown, unauthorized and no-visible-match searches.
- Persisted share endpoints for saving, listing, opening and removing remote/shared Memory entries in a target tenant, group or personal scope.
- Native mobile app discovery, client capabilities and auth handoff API contracts from [Native Mobile Apps](mobile-apps.md).
- Native mobile upload-target API for the iOS Share Extension and future Android share intents, returning authorized Memories, known child Memories/Kapitel and upload buckets in one compact response so extensions do not need N+1 Memory detail requests or full in-extension browsing. This should include capability gating, privacy-preserving tenant/scope filtering, stale-cache hints and a fallback plan for offline snapshots.
- Instance branding metadata for native apps after Mobile V1.
- TOTP recovery and policy endpoints beyond first password-login support, including recovery codes, ServerOwner reset/disable and tenant/server enforcement controls.
- ServerOwner-only web-managed server configuration for selected `.env`-backed values, with `.env` remaining the bootstrap and recovery source. Candidate settings include SMTP configuration including password, auth cookie names and the secure-cookie flag. The API should define secret masking, validation, test-mail behavior and whether changes apply at runtime or require restart.
- ServerOwner-only storage capacity reporting for server settings, showing available and used space for the configured media storage without exposing host filesystem details or cross-tenant usage breakdowns.
- Ablage APIs for listing, folder creation, upload, download, rename/move and delete, with scope permissions and storage-key privacy.
- Keep API-first contracts complete with preconditions, examples, error cases and `x-implementation-status`.
- Preserve generated backend/frontend API model usage for new Memory APIs.

## Needs UX Design

- Memory detail next pass with combined media/track maps and richer contextual tabs.
- Memory header with places, weather and richer track/participant summaries beyond the current cover, playlist and participant surfaces.
- Memory map fallback for an explicit Memory location, shown as the default map position when the Memory has no geotagged media and no GPX track geometry.
- Video poster/title image selection that lets users choose the exact frame or an existing Memory gallery image used as the video's visible title image or preview.
- Child Memory navigation without file-system feel.
- Memory and folder mutation UX polish for [ADR 0020](../adr/0020-memory-and-folder-mutation-semantics.md), including affected-content counts and clear guidance that Memories remain the primary content objects.
- Parent Memory aggregation indicators.
- Wrong-upload recovery flow for cross-Memory mistakes while preserving direct Memory asset ownership.
- Richer uploader attribution and privacy modes beyond current uploader display names and media buckets.
- Live update UX for Memory detail views so new media, tracks and other context added by another client appear without a manual refresh, with subtle indicators when lists changed, uploads finish, or a reconnect requires a full reload.
- Optional demo Memory, seed-data strategy and homepage-linked demo instance with a mock backend so visitors can explore the Memory UI without real accounts, uploads or tenant data.
- Native mobile "Open in App" banner and verified-link behavior from [Native Mobile Apps](mobile-apps.md), including Safari Smart App Banners after App Store/developer registration and an app-internal browser fallback when a recognized link is not supported natively yet.
- Local client capability notifications for app-only features such as the iOS Share Extension. These should use the same product language as server capability notices, but be emitted and dismissed locally by the app instead of being delivered or acknowledged through the backend.
- Browser tab title should include the active tenant name where available, with privacy-conscious fallbacks for setup, login, personal scope and public-share pages.
- Live search fields where useful.
- Remote/shared Memory navigation that clearly shows source tenant/domain, current share access state and target-side visibility without making the source Memory look copied.
- Email-domain masking in user search or invite flows where privacy requires it.
- Tenant email settings UI for tenant owners, including domain-aligned sender preview, reply-to/contact fields, test email, validation status and clear fallback behavior when tenant-specific settings are incomplete.
- Email template management and review workflow, including correct German/English translations, preview/test-send support and copy QA for transactional mail across setup, invitation, password and account flows.
- Tenant registration-link approval UX where TenantOwners can choose whether a public join link activates users immediately or places them into a review queue, and registrants get a clear pending/approved/rejected state without gaining tenant access before approval.
- Dedicated public tenant contact fields and optional server-side audit logging for tenant terms/share notice acceptance, beyond the browser-local acceptance model in [ADR 0012](../adr/0012-share-notices-and-tenant-terms.md).
- Password setup and change forms should ask users to enter the new password twice, provide a visibility toggle for password fields, and show inline mismatch feedback before submitting setup, invitation acceptance, password reset or password change requests.
- TOTP recovery-code display, ServerOwner reset/disable authority and tenant/server enforcement controls beyond the current account-level enrollment and removal UI.
- Account deletion/deactivation UX for [ADR 0017](../adr/0017-user-lifecycle-and-tenant-membership-removal.md), with explicit consequences for tenant access, owned settings, public profile visibility, personal-scope deletion and historical attribution.
- Server settings storage capacity panel for ServerOwners, with clear free/used storage values, warning states for low disk space and copy that treats the values as operational instance health rather than user-facing quota.
- Scope-root Ablage rubric alongside Memories and folders, using UI label `Ablage` and a language-neutral route segment such as `files`.

## Needs Technical Spikes

- ChatGPT-backed GitHub agent review for pull requests, including diff-scoped feature detection, acceptance-criteria coverage checks, advisory PR comments and a manual broader PR assessment mode.
- Media derivation pipeline beyond major line 1 poster previews: WebP thumbnails, H264 MP4 fallbacks and streaming versions.
- Adaptive video delivery with lower-resolution variants for different network strengths.
- Map hardening beyond major line 1: marker clustering, manual geotagging, track overlays on Leaflet, offline/self-hosted tiles and OpenStreetMap consent reset UX.
- Playlist metadata fetching, caching and optional OAuth-backed playback for Apple Music and Spotify.
- Weather provider integration.
- Face recognition and image classification feasibility, including ARM hosting.
- Browser/mobile-side analysis feasibility under E2E constraints.
- Nextcloud-style Share Extension on-demand upload-target browsing, including App Group account metadata, Keychain Access Group credentials, a minimal extension API client, cache-first target loading, token refresh policy, offline fallback to the current snapshot and keeping the actual upload queue in the host app.
- Semantic search architecture under privacy constraints.
- WebSocket/SSE live-update infrastructure for Memory events, including auth/session handling, horizontal scaling, reverse-proxy compatibility, backpressure, heartbeat, reconnect and fallback polling for self-hosted deployments. This should also replace the current media long-poll implementation's fixed-interval database wakeup with event-driven notification or a similarly low-load mechanism.
- Face recognition pipeline with instance-wide internal matching, viewer-scoped person resolution and non-enumerable search responses.
- Push gateway design for APNS/FCM with self-hosted instances.
- Federation and remote share bookmarking based on persisted shares rather than direct Memory copying.
- Mock-backend demo deployment for the public homepage, including fixture generation, media asset licensing, deterministic reset behavior and clear boundaries from production/self-hosted backend code.
- Managed hosting operations and control-plane spike based on [Hosting and Operations Strategy](hosting-and-operations-strategy.md), including IaC provisioning, staged rollouts, rollbacks, version pinning, maintenance windows, backups, monitoring and billing integration.
- Soft-delete lifecycle for managed shares if share lists need long-term cleanup without losing anonymous upload attribution.
- Dynamic runtime overrides for Quarkus and Compose-sourced configuration, including which values can safely apply without restart and which must remain `.env` or restart-only.

## Major Line 1 Guardrails

- Keep major line 1 video playback original-only. Generate poster previews, but defer transcodes, HLS/DASH and adaptive quality selection.
- Keep comments out of major line 1.
- Keep local accounts and passkeys first; external identity providers can come later.
- Keep TOTP recovery, reset and enforcement policy outside major line 1 unless a later ADR explicitly promotes it.
- Keep storage local-filesystem compatible.
- Avoid manual asset sort fields in major line 1.
- Avoid cross-Memory asset moves in major line 1 unless a later ADR explicitly changes direct ownership rules. Bucket-level moves inside one Memory already exist.
- Avoid self-hosted music files; use external streaming references.
- Avoid public person graph features.
- Keep Ablage separate from Memories: no automatic Memory integration, no public Memory-share exposure, no gallery/timeline behavior and no file-to-Memory import unless a later ADR explicitly adds it.
- Keep central infrastructure optional.
- Keep reverse-proxy responsibility for domains, TLS and path rewrites unless a later ADR changes this.
- Keep dangerous infrastructure values such as database passwords, media mounts, Traefik rules and WebAuthn relying-party IDs out of the WebUI unless a later ADR and API design explicitly promote them.
- Keep tenant and group roles separate from global security roles.
- Keep public media identifiers separate from internal database IDs.

## Baseline References

The implemented baseline intentionally lives outside this file. Before using a future-work item as implementation input, check the relevant feature YAML, OpenAPI contract, architecture doc and ADRs. If those sources already cover the behavior, update or remove the future-work item instead of treating it as a second source of truth.
