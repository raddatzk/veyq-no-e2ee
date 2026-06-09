# Native Mobile Apps

This document turns ADR 0015 into a product and implementation roadmap for the official native veyq apps. It is planning context, not an implemented release scope.

## Product Direction

veyq should have first-class native apps for iOS and Android. The apps should support self-hosted and managed instances equally, and a user should be able to connect more than one instance.

Mobile V1 focuses on the highest-value mobile workflows:

- Connect a veyq instance.
- Authenticate through browser-based login with native session handoff.
- Select a tenant after login.
- Browse Memory navigation and Memory details.
- View Memory media.
- Create, edit and delete Memories with the existing title, path name and date range fields.
- Create organizational folders for Memory navigation.
- Set and remove Memory covers from existing image media.
- Upload photos and videos to a Memory.
- Keep a local upload queue with retry and clear failure states.
- Use best-effort background upload support where iOS and Android allow it.
- Manage basic user profile fields such as display name, permalink and email.

Mobile V1 is under active native implementation, but app-store distribution and release support are tracked separately from the browser/server major line until the release notes explicitly promote mobile apps into a supported release scope.

The current browser-to-iOS parity map is tracked in [iOS Feature Parity](ios-feature-parity.md). It should be updated before or alongside meaningful native scope changes. As additional clients become active, veyq should keep parity tracking current across browser, iOS, Android and API behavior, including explicit notes for intentionally platform-specific differences.

## V1 Non-Goals

Mobile V1 should not try to become the complete web app:

- No full administration surface. V1 settings are limited to app preferences, connected-instance state, basic user profile fields and session actions.
- No full native public share viewer.
- No uploader-only public share flow.
- No white-label or instance-branded app variants.
- No offline Memory editing.
- No full background sync.
- No native editing for tracks or playlists.
- No push notifications.
- No shared UI framework.

Public share and invite links may be recognized and routed, but protected content still requires the app to verify access through the instance APIs.

## Architecture

The V1 apps use native platform UI:

- iOS: Swift and SwiftUI.
- Android: Kotlin and Jetpack Compose.

The iOS app lives under `ios/` as a native SwiftUI Xcode project. It should follow the iOS-specific product and interaction guidance in [veyq iOS UI Styleguide](ios-ui-styleguide.md), using the existing [Memory UI Styleguide](memory-ui-styleguide.md) as the shared product and color-role baseline.

App-store versions are independent from backend `major.build` versions. A current app can support multiple maintained server major lines when capabilities and API adapters allow it. The app follows its own major-plus-build release line. When support for a server capability that has been removed from the server for at least one year is dropped from the app, increment the app major version.

Each connected instance has separate local state:

- base URL and discovery metadata
- public client capability snapshot
- native session
- authenticated user and tenant bootstrap state
- upload queue
- cached Memory navigation/media metadata
- scoped in-memory and on-disk caches for derived thumbnails, previews and posters

Kotlin Multiplatform or another shared core can be considered later for API adapters, upload state machines or compatibility logic, but V1 should not depend on shared UI or a shared runtime.

## Capabilities And Permissions

The app is capability-gated. A feature is active only when all three conditions are true:

- The app supports the feature.
- The server advertises the required capability.
- The concrete Memory or public-share response permits the action.

Server capabilities describe what the instance knows how to do. Resource permissions describe what the current user or share session may do on a specific object.

Examples:

- `memory.media-zip-download` says the server supports Memory media ZIP downloads.
- `canViewGallery` on a public share says this share session may view gallery content.
- A download action needs app support, the server capability and the concrete resource permission.

The initial public client capability response should include non-sensitive `ALL_USERS` capabilities such as:

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

ServerOwner-only capabilities such as setup, backup/upgrade and auto-updates should remain out of public client capability discovery.

If a server major changes a capability API incompatibly, the old capability is removed and a new capability id is added. The app must not call APIs for missing capabilities. If it sees a newer unsupported replacement capability, it should show an app update hint instead of offering a broken workflow. App releases may keep adapters for removed capabilities during the compatibility window. Once a removed capability has been absent from maintained server releases for at least one year, a new app major may remove that adapter and no longer support servers that depend on the old capability.

## Instance Discovery

`/.well-known/veyq` should be the first unauthenticated probe for a user-entered server URL. It should identify the instance as veyq and point to the API base URL and mobile login/handoff entrypoints.

`GET /api/client-capabilities` should return public, client-relevant capabilities and compatibility hints. It is not a permission endpoint and should not expose ServerOwner-only operational capabilities.

After login, the app loads authenticated bootstrap data. The existing `/api/frontend/bootstrap` shape is a useful precedent because it already returns the current user and tenant summaries. A mobile-specific bootstrap can be added later if mobile needs a different contract.

## Authentication

Mobile login uses the system browser for the actual web/password/passkey login and then hands a native session back to the app.

Flow:

1. The app opens the instance login through `ASWebAuthenticationSession` on iOS or Custom Tabs/browser auth on Android.
2. The user logs in through the existing web flow.
3. The server redirects to `veyq://auth/callback` with a short-lived one-time code.
4. The app exchanges the code over HTTPS with the originating instance.
5. The server returns a native session.

The native session is separate from web HttpOnly cookies. iOS stores it in Keychain. Android stores it in Android Keystore-backed storage. Logout and revocation must cover native sessions explicitly.

Existing passkey-link endpoints are an architectural precedent for short-lived grants, but they are not the mobile auth API.

## Links And App Install

Arbitrary self-hosted HTTPS links must always work in the browser. The official app cannot rely on arbitrary self-hosted domains opening directly through Universal Links or Android App Links.

iOS behavior:

- Known verified domains can use Universal Links.
- Self-hosted pages should render Safari Smart App Banners through page metadata once the official App Store id exists.
- The Smart App Banner `app-argument` should contain the canonical HTTPS URL for the current Memory, public share, invite target or other app-openable page.
- When the Safari Smart App Banner is available, the web UI should not render a second in-page "Open in app" action on iOS.

Android behavior:

- Known verified domains can use Android App Links.
- Self-hosted pages use a web fallback affordance.

Fallback behavior:

- Web pages can offer a dismissable "Open in app" action.
- That fallback affordance is deferred until a non-iOS native app exists. iOS relies on the Safari Smart App Banner instead.
- The fallback URL form is `veyq://open?url=<encoded canonical https url>`.
- If the app is not installed, the browser page remains usable and may show install links.
- If the app recognizes a link but does not support the target natively yet, it opens the canonical HTTPS URL in an app-internal browser or web-auth-safe browser surface instead of attempting a partial native flow.
- Custom scheme links are routing hints only. They never grant access.

The app resolves any incoming link by checking the instance, native session, capabilities and Memory/share authorization through HTTPS APIs.

Initial implementation scope:

- The backend exposes white-label app metadata in frontend bootstrap. `VEYQ_MOBILE_IOS_APP_STORE_ID` maps to `veyq.mobile.ios.app-store-id` and is returned as `mobileApps.iosAppStoreId`.
- The browser sets Safari Smart App Banner metadata from the runtime bootstrap app id and the current canonical HTTPS URL.
- The browser does not render an in-page app-open banner while iOS is the only native app platform.
- iOS accepts `veyq://open?url=<encoded canonical https url>` links, matches them to a stored or active instance, and opens authenticated Memory paths through the Memory navigation APIs.
- Public share links are recognized but remain browser-first until the native public share viewer is implemented.

## Uploads

Mobile uploads should use the existing Memory resumable upload APIs when the server advertises `memory.media-resumable-upload`.

The app keeps a local queue per instance. Queue entries should include enough local metadata to resume or retry while respecting platform limits around file access and background execution.

V1 upload behavior:

- retry failed chunks
- resume known incomplete server upload sessions when possible
- surface clear user-visible failure states
- allow cancel/removal from the local queue
- use best-effort background upload on both platforms

Background upload is not a guarantee. iOS and Android may pause, cancel or delay background work. The UI should describe final state and required user action rather than promising continuous background sync.

## Later Phases

Likely follow-up phases:

- Full native public share viewer.
- Native uploader-only public share flow.
- Richer Memory creation and editing.
- Native track and playlist management.
- Offline read cache.
- Offline editing with conflict rules.
- Push notifications through optional infrastructure.
- Optional shared core for API adapters or upload logic.
- Instance branding and white-label/commercial app variants.

Each later phase should add or depend on explicit capabilities rather than assuming server behavior from the version string alone.
