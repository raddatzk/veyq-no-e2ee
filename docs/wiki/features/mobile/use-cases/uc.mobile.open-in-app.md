# UC: Open a link in the native app

- **ID:** uc.mobile.open-in-app
- **Primary actor:** Memory Curator
- **Secondary actor:** Invited User
- **Goal:** Follow a veyq link straight into the native app — landing on the right screen (a Memory, a
  share, or an invitation) instead of the web.
- **Preconditions:** The native app (iOS or Android) is installed; for content links, the user can
  unlock the relevant keys.
- **Postconditions (success):** The app opens (cold or warm) and routes to the link's target — an
  invite-accept flow or an in-app destination; any secret in the URL **fragment** is consumed
  client-side and never sent to the server.

## Link model
A custom **`veyq://`** scheme with two hosts: **`invite`** (accept-invitation links) and **`open`**
(navigate to a space / Memory / share). iOS registers the scheme (Info.plist) and handles it via
`.onOpenURL → handleDeepLink`; Android declares an `intent-filter` (scheme `veyq`, hosts
`invite`/`open`) and handles it via `onNewIntent → captureDeepLink`.

## Main flow
1. The user taps a `veyq://` link (or a web "open in app" affordance).
2. The OS **launches** the app (cold start) or **foregrounds** it (warm) and delivers the URL.
3. The app parses **host + path + fragment**.
4. For **invite** → it enters the accept-invitation flow (recovering any temp key / passphrase from the
   fragment). For **open** → it navigates to the target space / Memory / share.
5. If the keys for the target aren't loaded, the app **prompts to unlock / enroll** before showing
   content.

## Alternate / exception flows
- A1: **Cold start** — the deep link is captured at launch and replayed once the app is ready.
- A2: **Warm start** — `onOpenURL` / `onNewIntent` routes without restarting.
- E1: The app **isn't installed** → the custom-scheme link does nothing; the originating **web** page
  remains the fallback (the web app handles the same link).
- E2: An **unknown / malformed** host or path → the app opens to a safe default (home) rather than
  failing.
- E3: A **protected invite** without its passphrase → the app prompts for the passphrase before
  proceeding.

## Quality notes
- *Confidentiality:* link secrets live in the URL **fragment** and are processed on-device; the operator
  never receives them.
- *Parity:* iOS and Android handle the same `veyq://` hosts, so a link behaves identically on both.
- *Robustness:* cold and warm starts both route correctly; bad links degrade to home.

## Related
- Feature: [Mobile](../_index.md) · Actors: [Memory Curator](../../../actors.md),
  [Invited User](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md) (capability discovery),
  [Security Model](../../../crosscutting/security-model.md) (token-in-fragment)
- Related use-cases: `uc.sharing.share-public-viewer`, `uc.account.register-via-invitation`,
  `uc.spaces.create-group`
