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
| `platform.auth-session-security` | Cookie sessions, mobile bearer sessions, refresh and logout boundaries. | `partial` | Native restore refreshes expired access sessions before bootstrap, per-account logout revokes inactive accounts before local removal, and full disconnect attempts revocation for every stored account; next add real Keychain integration coverage beyond the in-memory store tests. |
| `auth.local-accounts-passkeys` | Browser login, passkeys, TOTP for password login, invitations and account credential management. | `partial` | Keep credential entry and TOTP challenges in the browser; improve native failure states around `ASWebAuthenticationSession` cancellation, bad callback state and expired handoff codes. |
| `spaces.tenant-group-personal` | Tenant, group and personal Memory scopes with route isolation. | `partial` | Native iOS now switches tenant, group and personal roots through a native root tab bar, selects tenants directly from the compact context sheet, and selects available groups directly on the Groups root page; next make scope switching state resilient across instance changes. |
| `frontend.shell-navigation` | Browser workspace shell, sidebar, topbar, profile menu, breadcrumbs and route helpers. | `partial` | Treat this as native shell parity only: keep tenant context reachable from Memory home, expose connected account management from the context sheet, keep app preferences in Settings and stabilize Memory navigation paths instead of copying the browser sidebar. |
| `frontend.localization-theme` | Browser locale and theme selection. | `partial` | Add app preferences for language/theme if the current native defaults are insufficient; otherwise document iOS system-driven behavior as intentional. |
| `memory.core` | Memory CRUD, nested Memories, covers, favorites, dates, folders, search and detail navigation. | `partial` | Native iOS can create root Memories in the current scope, create Kapitel inside an open Memory, edit title/path/date fields, delete Memories with confirmation, browse organizational folders, create folders in the current folder path, run tenant-wide search across visible tenant, group and personal scopes, keep a tenant-scoped Recent tab for opened/import targets and set/remove covers from existing image media; next close folder rename/move/delete, favorite display/toggle, standalone cover upload and crop adjustment. |
| `memory.media` | Media upload, previews, posters, lightbox, downloads, buckets, metadata correction and delete flows. | `partial` | Native viewer now covers image previews, video playback, basic metadata, original sharing, single-item deletion, captured-at correction and scoped thumbnail/preview/poster caching; next close bucket actions where mobile should support them. |
| `memory.media-live-updates` | Browser Memory detail long-polls for newly created media using an updated timestamp plus media id cursor and merges returned items locally. | `done` | Native detail views use capability-gated polling for the currently open Memory, merge new or changed media, remove deleted media from the server-provided current id set and refresh header counts after visible changes. |
| `memory.media-resumable-upload` | Chunked resumable private and public upload APIs. | `partial` | Native queue persists upload IDs and byte progress, restarts interrupted in-flight work as queued, retries failed uploads without discarding resumable progress, and accepts Share Extension imports through account/tenant/space context selection, local search, Recent targets and Memory target confirmation; next add deeper API-client tests around failed chunk completion and real Share Extension UI automation. |
| `memory.media-upload-eta` | Client-side ETA, stalled state and cancel affordances. | `partial` | Native queue shows uploaded percentage, remaining-time hints and stalled-state messaging; next validate wording in real-device slow-network runs. |
| `memory.media-zip-download` | ZIP downloads for direct and descendant Memory media. | `missing` | Low mobile priority; revisit after upload reliability, read parity and media viewer fundamentals are solid. |
| `memory.journal-markdown` | Markdown rendering, veyq embeds, editing and public share journal permissions. | `partial` | Native iOS renders Markdown text plus `::media:` single-media and carousel embeds, and now supports private authenticated Markdown editing with live preview and REST saving; next add Memory, playlist and track embed cards plus native insertion controls. |
| `memory.journal-live-sync` | Collaborative Markdown journal editing. | `not-v1` | Browser live sync remains web-only because it depends on the Yjs/Hocuspocus editor stack; native iOS intentionally offers REST-backed editing with live preview instead of collaborative sessions for this slice. |
| `memory.maps` | GPS media map, track route rendering and external tile consent. | `partial` | Native detail shows a MapKit section gated by `memory.maps` for media GPS points, grouped media markers that open a group-scoped media viewer, recentering and selectable GPX route overlays; next add any needed tile/provider consent handling for non-Apple map sources if the map provider changes. |
| `memory.tracks` | GPX upload, list, summary, download and embeds. | `partial` | Native iOS can upload GPX files shared from other apps or from an empty Memory map placeholder, list Memory tracks, toggle individual track overlays and show distance, duration and elevation summaries; next add native track edit/delete/download surfaces and journal track embed cards. |
| `memory.playlists` | External playlist links, featured playlist, panel UI and embeds. | `missing` | Add a Memory context section with featured playlist and provider deep links gated by `memory.playlists`. |
| `memory.public-sharing` | Public share auth, roles, notices, terms, management and public route rendering. | `not-v1` | Recognize links and route unsupported targets to browser/auth-safe web surface; native public share viewer comes later. |
| `memory.public-uploader-links` | Upload-only public share flow with restricted visibility. | `not-v1` | Recognize uploader links but keep the complete flow in browser until native public upload is explicitly in scope. |
| `settings.tenant-group-admin` | Tenant and group administration. | `not-v1` | Keep administration in browser; iOS settings should only cover app preferences, connected instances and session actions. |
| `user.profile-account-settings` | Profile, avatar, email, password, passkey and TOTP account settings. | `partial` | Native iOS can show and update display name, profile permalink and email through the existing user settings API; keep avatar, password, passkey, TOTP and account deletion flows in the browser login/account surface for now. |
| `server.setup` | First-owner setup and first tenant creation. | `not-v1` | Keep setup in browser. The app may detect an unconfigured instance and open the browser setup flow. |
| `server.backup-upgrade` | Operator backup and upgrade scripts. | `not-v1` | No native surface. |
| `server.auto-updates` | Optional operator auto-update sidecar. | `not-v1` | No native surface. |
| `api.openapi-contracts` | API-first contracts and generated clients. | `partial` | Either generate a Swift client from OpenAPI or maintain a documented hand-written adapter boundary with tests for decoded models. |
| `platform.api-runtime` | Browser HTTP client behavior, media transport and error localization. | `partial` | Add native API runtime tests for authorization headers, stable error decoding, media URL resolution and ISO date handling. |
| `platform.security-pipeline` | Backend route authorization. | `not-v1` | Covered by server tests; iOS consumes authorized API responses only. |

## First Implementation Track

The fastest path toward browser parity is a private authenticated Memory read track:

1. **Core Memory authoring:** extend the new root/Kapitel create, basic edit, delete, folder-create and media-cover slice with folder rename/move/delete, favorites, standalone cover upload and crop adjustment.
2. **Media viewer:** extend the native viewer beyond image previews, video playback, basic metadata and original sharing toward the remaining supported media actions.
3. **Journal read rendering:** extend the native read-only renderer beyond text and media carousels to Memory, playlist, track and map embeds.
4. **Context sections:** add Playlists read surfaces, then native track edit/delete/download controls.
5. **Upload queue reliability:** continue hardening failed-chunk completion and real-device background/reopen behavior after the initial restart/ETA/stalled-state slice.

This order improves the everyday Memory experience before adding authoring, public share or administration surfaces.

## Coverage Expectations

New iOS behavior should be covered by `e2e.ios` only when real UI automation exists. Until then, use native unit tests for model decoding, API adapters, upload queue state and routing helpers, and keep feature catalog gaps explicit instead of pretending browser tests prove iOS behavior.
