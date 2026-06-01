# veyq iOS UI Styleguide

This guide complements the web-focused [Memory UI Styleguide](memory-ui-styleguide.md) for native iOS work. It is implementation guidance for the SwiftUI app under `ios/`, not a replacement for Apple's platform conventions or the shared veyq product vocabulary.

veyq on iOS should feel like a native, private Memory archive. It should preserve the emotional, curated direction of the web UI without copying browser layouts into SwiftUI.

## Foundations

Use Swift and SwiftUI, as decided in [ADR 0015](../adr/0015-native-mobile-apps.md). Build with native platform primitives first:

- `NavigationStack` for hierarchical Memory navigation.
- `TabView` only when top-level V1 workflows need persistent switching.
- `List` for dense administrative or queue-like rows.
- `ScrollView` and adaptive grids for Memory, media and contextual content.
- `sheet`, `confirmationDialog` and `alert` for focused modal tasks.

The iOS app lives in `ios/` as a native Xcode project. Keep V1 native: do not replace it with a shared UI framework, web wrapper or cross-platform runtime.

Use `Memory` language for rich content objects. Folder wording is acceptable only for organizational navigation. Do not introduce Album or Explorer naming, routes, models or UI flows.

The current app already has these primary surfaces:

- Instance connection and browser-based native session handoff.
- Memory home with scope, tenant and instance switching.
- Memory detail with gallery, journal, child Memory and context tabs.
- Photos and videos import through `PhotosPicker`.
- Floating upload/search dock and a local upload queue.
- Share extension handoff into the upload target flow.

Treat these surfaces as the baseline when evolving the styleguide. New guidance should reconcile with the existing SwiftUI implementation instead of describing a separate future app.

## Color And Materials

Map the web semantic color roles to iOS design tokens rather than copying CSS variables literally.

- Brand accent maps from `--app-accent` and is used for app identity, selected app-level states, Memory creation cues and focus accents.
- Positive action maps from `--app-success` and is used for save, upload, confirm, progress, map and track states.
- Warning maps from `--app-warning` and is used for shared/contextual notices, not primary actions.
- Danger maps from `--app-danger` and is used only for destructive actions and errors.
- Info maps from `--app-info` and is used for helper states and non-critical metadata.

Define iOS colors with light and dark appearances. Prefer semantic names such as `VeyqAccent`, `VeyqSuccess`, `VeyqWarning`, `VeyqDanger`, `VeyqInfo`, `VeyqSurface` and `VeyqTextSecondary`.

Use system backgrounds and materials where they support native readability. Avoid decorative one-note palettes, heavy gradients and generic cloud-drive styling.

The SwiftUI source of truth for code-level style tokens is `ios/Veyq/VeyqStyle.swift`. Keep it small and semantic:

- Add shared tokens there only when at least two iOS surfaces need the same role.
- Prefer SwiftUI system roles such as `.primary`, `.secondary`, `.background`, `.regularMaterial` and platform button roles before creating a custom token.
- Keep token names product-agnostic where possible, such as `accent`, `success`, `warning`, `danger`, `info`, `surface` and `textSecondary`.
- When a documented role is not present in `VeyqStyle.swift`, either add the token during implementation or adjust this guide so the documented role matches reality.

## Typography And Accessibility

Support Dynamic Type from the start. Use SwiftUI text styles (`.largeTitle`, `.title2`, `.headline`, `.body`, `.subheadline`, `.caption`) instead of fixed sizes unless a media overlay needs a tightly bounded treatment.

Rules:

- Text must not truncate essential Memory titles, dates or upload states without a way to open the full value.
- All icon-only actions need accessibility labels.
- Important actions must be reachable without relying on hover or pointer behavior.
- Respect Reduce Motion for animations, transitions and upload progress effects.
- Keep tap targets at least 44 x 44 points.

## Navigation

The app should make the current instance and tenant clear without making navigation feel like a file manager.

V1 navigation should prioritize:

- Connected instance selection.
- Authenticated tenant selection.
- Memory navigation and favorite/visible Memory entry points.
- Memory detail with media, journal, maps, tracks, playlists and chapters as capability-gated sections.

Folders stay secondary. A folder row helps a user find Memories; it should not be presented as a rich content object.

Incoming HTTPS links and `veyq://open?url=...` links are routing hints. The app must resolve them through the originating instance, current native session, server capabilities and concrete Memory or share permissions.

Use the current Memory home pattern for context switching: one compact affordance opens a native sheet for Memory scope, tenant and instance. Avoid adding persistent sidebars, folder trees or cloud-drive-style path chrome to the main Memory browsing surface.

## Memory Surfaces

Memory cards and detail headers should emphasize:

- Cover media.
- Title.
- Date range.
- Place or context metadata when available.
- Chapter counts and direct media counts where useful.

Use cards for repeated Memory, media, playlist and track objects. Do not nest cards inside cards. Use rows for upload queue items, instance settings and tenant lists.

Media grids should use stable item sizing and avoid layout shifts as thumbnails load. Video media needs a clear play affordance and should not autoplay with sound.

For detail screens, prefer one strong Memory header followed by capability-gated sections. Tabs or segmented controls are acceptable when they reduce scrolling across gallery, journal, child Memories and context, but they must not hide unavailable capabilities as empty product promises.

## Actions And Sheets

Use SF Symbols for common icon actions. Prefer native toolbar items and menus for compact controls:

- Add or upload: `plus`, `square.and.arrow.up` or `photo.badge.plus`.
- Save or confirm: checkmark-style symbols where appropriate.
- Delete: `trash` with destructive styling.
- Cancel or close: `xmark`.
- Retry: `arrow.clockwise`.

Sheets should handle one task at a time. Keep copy short and consequence-focused, especially for privacy, sharing, logout and destructive actions.

Confirmation order should follow iOS conventions: destructive actions use destructive button roles; cancel remains explicit and easy to reach.

Primary commands should live where iOS users expect them:

- Use toolbar items for page-level actions such as upload.
- Use floating or safe-area controls only for persistent cross-screen actions such as search or upload queue status.
- Use `List` sheets for choosing Memory targets, tenants, scopes and instances.
- Use alerts only for short text entry or irreversible confirmation. Prefer a sheet when a task needs validation, multiple fields or explanatory state.

## Upload Queue

Mobile uploads are a first-class iOS workflow. The queue is local per connected instance and should be visible when work is pending, failed or needs user action.

Each queue item should show:

- Target Memory.
- Filename or asset summary.
- Progress and remaining state when available.
- Retry, cancel or remove actions for failed or paused uploads.

Use the resumable Memory media upload APIs when `memory.media-resumable-upload` is advertised. Background uploads are best effort; the UI must show the final state and required user action instead of promising continuous background sync.

The upload queue is an app-level surface, not just a detail-screen accessory. If queued work exists, the user should be able to reach status, failures and retry/removal actions without navigating back to the original Memory.

## Capability And Permission Gating

Every native feature is active only when:

- The app supports the feature.
- The instance advertises the required public capability.
- The concrete Memory or share response permits the action.

Do not infer feature availability from server version strings alone. If a needed capability is missing, hide the action or show a concise unavailable state. If the server exposes a newer unsupported replacement capability, show an app update hint.

## Build Hygiene

iOS targets track the current SDK closely. Keep the app warning-clean for the active deployment target instead of suppressing SDK warnings. Scene-bound UI APIs must use scene-aware initializers; authentication presentation anchors should come from an existing `UIWindow` or from `ASPresentationAnchor(windowScene:)`, never from deprecated frame/default window initializers.

When style guidance changes, update documentation and implementation together when practical. At minimum, do not leave the guide describing tokens, surfaces or workflows that no longer exist in `ios/`.

## Privacy And Sharing

iOS surfaces must preserve the same privacy boundaries as the web product.

- Do not expose public profiles, social feeds, reactions or cross-Memory person discovery.
- Do not show cross-tenant person graphs or unauthorized Memory references.
- Public share and invite links must be verified through HTTPS APIs before showing protected content.
- Uploader-only links are not a full native public share viewer in V1.

Native sessions are separate from browser cookies. Store iOS native session secrets in Keychain and make logout/revocation explicit.

## Agent Checklist

Before finishing iOS-facing product or implementation work:

- Checked this guide, [Native Mobile Apps](mobile-apps.md), [ADR 0015](../adr/0015-native-mobile-apps.md) and the current SwiftUI files under `ios/`.
- Used SwiftUI-native primitives rather than web layout copies.
- Preserved Memory terminology and kept folders organizational.
- Mapped colors from semantic web roles and handled light/dark appearances.
- Supported Dynamic Type, accessibility labels and 44-point tap targets.
- Gated screens and actions through app support, server capabilities and resource permissions.
- Avoided deprecated SDK APIs for the active iOS deployment target.
- Kept public/share surfaces privacy-preserving.
- Treated upload queue states as local per-instance state.
