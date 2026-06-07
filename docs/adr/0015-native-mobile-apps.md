# ADR 0015: Native Mobile Apps

## Status

Accepted

## Context

veyq is intended to support native mobile apps for iOS and Android without making managed hosting or central veyq infrastructure mandatory. The current product baseline is a Quarkus backend and Vue web app with OpenAPI-first APIs, HttpOnly-cookie web authentication, public Memory shares and a server capability registry.

Mobile clients have different constraints from the web frontend. They need platform-native link handling, app-store release versions, secure native session storage, local upload queues and best-effort background uploads. Self-hosted instances can run on arbitrary domains, so arbitrary HTTPS Memory links cannot be assumed to open directly in the official app through Universal Links or Android App Links.

Mobile compatibility also needs to account for maintained server major lines. App versions and backend versions should not be coupled. The app should enable workflows from server capabilities and concrete Memory or public-share permissions, not from server version strings alone.

## Decision

Build first-class native apps:

- iOS uses Swift and SwiftUI.
- Android uses Kotlin and Jetpack Compose.
- Mobile V1 does not use Capacitor, React Native, Flutter or a shared UI framework.
- A shared core, such as Kotlin Multiplatform for API or upload logic, remains a later option and is not part of V1.

Keep app-store versions separate from backend `major.build` versions. Backend major lines describe server compatibility. Mobile app releases describe app-store delivery. The app uses its own major-plus-build release line. Compatibility is expressed through server capabilities and maintained server major lines.

Increment the app major version when the app drops support for a server capability that has been removed from maintained server releases for at least one year. Until that window has passed, app releases can keep compatibility adapters for removed or replaced capabilities when doing so is practical.

The official app supports multiple instances. Self-hosted and managed instances are equal. Mobile V1 does not include white-label apps or instance branding. Per instance, the app stores capabilities, native auth state, tenant context and upload queue state separately.

Mobile V1 focuses on viewing and uploading Memories:

- connect an instance
- log in and out
- select a tenant using bootstrap-style authenticated data
- browse Memory navigation and Memory details
- view media
- upload media through a local queue with retry
- use best-effort native background upload support where the platforms allow it

Mobile V1 is under active native implementation, but app-store distribution and release support are tracked separately from the browser/server major line until release scope is explicitly changed later.

Add minimal unauthenticated instance discovery at `/.well-known/veyq`. It identifies the server as veyq and exposes the API base URL and mobile login or handoff entrypoints.

Add public client capability discovery at `GET /api/client-capabilities`. The response exposes client-relevant, non-sensitive `ALL_USERS` server capabilities. The current expected baseline is:

- `auth.passkeys`
- `auth.mobile-handoff`
- `spaces.tenant-group-personal`
- `memory.core`
- `memory.media`
- `memory.media-resumable-upload`
- `memory.media-upload-eta`
- `memory.media-zip-download`
- `memory.journal-markdown`
- `memory.maps`
- `memory.tracks`
- `memory.playlists`
- `memory.public-sharing`
- `memory.public-uploader-links`

ServerOwner-only capabilities, such as setup, backup/upgrade and auto-updates, stay out of the public client capability response.

Keep server capabilities separate from resource permissions. Public share fields such as `canViewGallery`, `canUpload`, `canEdit` and `canDelete` describe what a concrete share session can do. They are not server capabilities. A mobile feature is active only when the app supports it, the server advertises the required capability and the concrete Memory or share response permits the action.

If a capability's API contract changes incompatibly in a new server major, remove the old capability and introduce a new capability id. Apps must not call APIs for missing capabilities. If a server exposes a newer unsupported replacement capability, the app shows an update hint instead of offering a broken workflow. After the one-year removed-capability compatibility window, a new app major may remove the old adapter and stop supporting servers that still depend on that capability.

Mobile login uses system browser authentication plus native session handoff:

- The app opens `ASWebAuthenticationSession` on iOS or a Custom Tabs/browser auth flow on Android.
- The user logs in through the existing web password or passkey flow.
- The server redirects to `veyq://auth/callback` with a short-lived one-time code.
- The app exchanges the code over HTTPS for a native session.
- Existing web HttpOnly-cookie auth remains unchanged for web clients.

Native sessions are separate from browser cookies. iOS stores them in Keychain. Android stores them in Android Keystore-backed storage. Logout and revocation must cover native sessions explicitly.

Deep links and install affordances follow platform constraints:

- Arbitrary self-hosted HTTPS URLs always work in the browser.
- iOS Safari uses Smart App Banners with `app-argument` set to the canonical HTTPS URL once an official App Store id exists.
- Universal Links and Android App Links are used only for known verified domains.
- The fallback link form is `veyq://open?url=<encoded canonical https url>`.
- Custom scheme links are routing hints only and never grant access.

## Consequences

The mobile apps can feel native and integrate with platform upload, storage and link behavior without copying the web UI into a wrapper.

The backend needs new public discovery and client capability contracts, plus a mobile auth handoff flow and native-session revocation model. These are additive to the existing web APIs.

Capability-driven UI adds discipline to mobile development. Screens, tabs, actions and upload strategies need explicit required capabilities and must also honor Memory or public-share permissions.

Arbitrary self-hosted Memory links will open in the browser first. The native app can still be offered through Safari Smart App Banners, verified links for known domains and custom-scheme fallbacks, but the browser remains the reliable universal entry point.

Public shares can be opened natively later by combining client capabilities with `GET /api/public-shares/{shareId}`. Mobile V1 only routes share and invite links into the appropriate app, browser or login flow; it does not implement a full native public share viewer.
