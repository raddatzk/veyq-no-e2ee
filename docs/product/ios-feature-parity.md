# iOS Feature Parity

This document tracks how close the native iOS app is to the browser product surface. It is a planning document, not a release promise. The browser feature catalog under [features](features/README.md) remains the source of truth for implemented web and API behavior.

The purpose of this document is to make the fastest useful iOS work visible without turning Mobile V1 into a full browser clone. Mobile V1 remains focused on instance connection, browser-based login, tenant selection, Memory browsing, Memory details, media viewing and uploads.

API adapters may decode existing `childMemory` fields and operations, but native iOS domain and UI code should call nested Memories `Chapter`s, localized as `Kapitel` in German.

## Status Labels

- `done`: Native iOS has a user-facing implementation for the V1-relevant part of the feature.
- `partial`: Native iOS has the foundation, but important browser behavior or polish is still missing.
- `missing`: Native iOS has no meaningful implementation yet, and the feature is relevant to Mobile V1 or near-term parity.
- `not-v1`: The browser feature is intentionally outside Mobile V1.
- `blocked-by-api`: Native iOS needs an API, capability or contract shape before implementation can be reliable.

## Priority

1. Close Memory read parity before administration parity.
2. Keep private authenticated Memory flows ahead of public share flows.
3. Keep upload reliability and visibility ahead of editing breadth.
4. Add native coverage only for shipped iOS behavior; do not claim browser acceptance criteria as iOS coverage.

## Parity Matrix

| Feature | Browser baseline | iOS status | First useful iOS slice |
| --- | --- | --- | --- |
| `server.capability-discovery` | Public discovery, mobile login metadata and capability inventory. | `partial` | Client capability snapshots are persisted per connected instance and required capability failures are surfaced during connect/activation; next expose an app update or unavailable state for unsupported replacement capabilities. |
| `platform.auth-session-security` | Cookie sessions, mobile bearer sessions, refresh and logout boundaries. | `partial` | Native restore refreshes expired access sessions before bootstrap and logout/revocation remains explicit; next add real Keychain integration coverage beyond the in-memory store tests. |
| `auth.local-accounts-passkeys` | Browser login, passkeys, TOTP for password login, invitations and account credential management. | `partial` | Keep credential entry and TOTP challenges in the browser; improve native failure states around `ASWebAuthenticationSession` cancellation, bad callback state and expired handoff codes. |
| `spaces.tenant-group-personal` | Tenant, group and personal Memory scopes with route isolation. | `partial` | Make scope switching state resilient across instance changes and add iOS tests for visible tenant and scope selection. |
| `frontend.shell-navigation` | Browser workspace shell, sidebar, topbar, profile menu, breadcrumbs and route helpers. | `partial` | Treat this as native shell parity only: stabilize top-level tabs, context sheet and Memory navigation paths instead of copying the browser sidebar. |
| `frontend.localization-theme` | Browser locale and theme selection. | `partial` | Add app preferences for language/theme if the current native defaults are insufficient; otherwise document iOS system-driven behavior as intentional. |
| `memory.core` | Memory CRUD, nested Memories, covers, favorites, dates, folders, search and detail navigation. | `partial` | Kapitel are navigable from detail, available as Share Extension upload targets once opened by the host app, and Memory/Journal section links can be shared from native views; next add favorite display/toggle if capability is present, and add native Memory creation/editing only after read parity is solid. |
| `memory.media` | Media upload, previews, posters, lightbox, downloads, buckets, metadata correction and delete flows. | `partial` | Native viewer now covers image previews, video playback, basic metadata and original sharing; next close bucket, delete and metadata-correction actions where mobile should support them. |
| `memory.media-live-updates` | Browser Memory detail long-polls for newly created media using a `createdAt` plus media id cursor and merges returned items locally. | `missing` | Add capability-gated polling for the currently open Memory so media uploaded from another client appears without a manual refresh. |
| `memory.media-resumable-upload` | Chunked resumable private and public upload APIs. | `partial` | Native queue persists upload IDs and byte progress, restarts interrupted in-flight work as queued, and retries failed uploads without discarding resumable progress; next add deeper API-client tests around failed chunk completion. |
| `memory.media-upload-eta` | Client-side ETA, stalled state and cancel affordances. | `partial` | Native queue shows uploaded percentage, remaining-time hints and stalled-state messaging; next validate wording in real-device slow-network runs. |
| `memory.media-zip-download` | ZIP downloads for direct and descendant Memory media. | `missing` | Low mobile priority; revisit after upload reliability, read parity and media viewer fundamentals are solid. |
| `memory.journal-markdown` | Markdown rendering, veyq embeds, editing and public share journal permissions. | `partial` | Native iOS renders read-only Markdown text plus `::media:` single-media and carousel embeds; next add Memory, playlist and track embed cards before considering native journal editing. |
| `memory.journal-live-sync` | Collaborative Markdown journal editing. | `not-v1` | No native editor or live collaboration in Mobile V1. |
| `memory.maps` | GPS media map, track route rendering and external tile consent. | `missing` | Add a read-only native map section gated by `memory.maps`, using media GPS points first and tracks later. |
| `memory.tracks` | GPX upload, list, summary, download and embeds. | `missing` | Add read-only track summaries and route display after the map section exists; defer track upload/editing. |
| `memory.playlists` | External playlist links, featured playlist, panel UI and embeds. | `missing` | Add a Memory context section with featured playlist and provider deep links gated by `memory.playlists`. |
| `memory.public-sharing` | Public share auth, roles, notices, terms, management and public route rendering. | `not-v1` | Recognize links and route unsupported targets to browser/auth-safe web surface; native public share viewer comes later. |
| `memory.public-uploader-links` | Upload-only public share flow with restricted visibility. | `not-v1` | Recognize uploader links but keep the complete flow in browser until native public upload is explicitly in scope. |
| `settings.tenant-group-admin` | Tenant and group administration. | `not-v1` | Keep administration in browser; iOS settings should only cover app preferences, connected instances and session actions. |
| `user.profile-account-settings` | Profile, avatar, email, password, passkey and TOTP account settings. | `not-v1` | Show current user/session state and open browser for account management if needed. |
| `server.setup` | First-owner setup and first tenant creation. | `not-v1` | Keep setup in browser. The app may detect an unconfigured instance and open the browser setup flow. |
| `server.backup-upgrade` | Operator backup and upgrade scripts. | `not-v1` | No native surface. |
| `server.auto-updates` | Optional operator auto-update sidecar. | `not-v1` | No native surface. |
| `api.openapi-contracts` | API-first contracts and generated clients. | `partial` | Either generate a Swift client from OpenAPI or maintain a documented hand-written adapter boundary with tests for decoded models. |
| `platform.api-runtime` | Browser HTTP client behavior, media transport and error localization. | `partial` | Add native API runtime tests for authorization headers, stable error decoding, media URL resolution and ISO date handling. |
| `platform.security-pipeline` | Backend route authorization. | `not-v1` | Covered by server tests; iOS consumes authorized API responses only. |

## First Implementation Track

The fastest path toward browser parity is a private authenticated Memory read track:

1. **Media viewer:** extend the native viewer beyond image previews, video playback, basic metadata and original sharing toward the remaining supported media actions.
2. **Journal read rendering:** extend the native read-only renderer beyond text and media carousels to Memory, playlist, track and map embeds.
3. **Context sections:** add capability-gated Maps, Tracks and Playlists read surfaces.
4. **Upload queue reliability:** continue hardening failed-chunk completion and real-device background/reopen behavior after the initial restart/ETA/stalled-state slice.

This order improves the everyday Memory experience before adding authoring, public share or administration surfaces.

## Coverage Expectations

New iOS behavior should be covered by `e2e.ios` only when real UI automation exists. Until then, use native unit tests for model decoding, API adapters, upload queue state and routing helpers, and keep feature catalog gaps explicit instead of pretending browser tests prove iOS behavior.
