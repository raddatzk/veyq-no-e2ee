# Android UI Styleguide (Jetpack Compose)

How the [shared design parity](ui-design-parity.md) is realised on the Android surface (phone + tablet,
Jetpack Compose). The **token values and component contract live in the parity doc** — this is the thin
platform mapping + Android-specific rules. The Apple SwiftUI app ([ui-apple-styleguide.md](ui-apple-styleguide.md))
is the visual reference; match it with native Compose/Material 3 idioms. Target for cr-0016 (login + unlock).

## Source of truth

- **Tokens:** a **Veyq Compose theme** in `:app` — the parity role colors as a Material 3 `ColorScheme`
  (a `lightColorScheme`/`darkColorScheme` pair) plus a small `VeyqTokens` object for the non-Material bits
  (`radius` 12.dp, `radiusSm` 10.dp, helper/meta roles). Map roles to Material slots sensibly
  (`accent → primary`, `surface → surface`, `danger → error`, `bg → background`, `success`/`warning`/`info`
  as named extras) — but the **rendered values must equal the parity-doc table**, not Material defaults.
  Follow the system light/dark via `isSystemInDarkTheme()`.
- **Components:** mirror the shared contract as composables — `AuthCard`, `VeyqField`, `PrimaryButton`,
  `MessageBox` — so screens compose from them, not raw `Card`/`OutlinedTextField`. Keep names aligned with
  Apple's for cross-surface legibility.
- **Flow:** `connect → sign-in → home`, a `RootScreen` switching on an app-state holder (the Compose analog
  of Apple's `AppModel`), screens `ConnectScreen` / `SignInScreen` / `HomeScreen`.

## Native realisation

- **Material 3 as the toolkit, veyq as the skin.** Use Material components (`OutlinedTextField`, `Button`,
  `Surface`) but themed to the veyq tokens — not stock Material purple. Don't import Material widgets that
  have no parity role (no FABs, no bottom nav for the auth flow).
- **Responsive** via `WindowSizeClass` (material3 `androidx.compose.material3.windowsizeclass`): **Compact**
  → card fills width; **Medium/Expanded** (tablet, large window) → card caps at ~440.dp and centres. This is
  the parity "don't stretch the phone layout on a tablet" rule — verify on a tablet AVD/resizable emulator.
- **Secure entry:** `VeyqField(secure = true)` uses `PasswordVisualTransformation`; the password + Secret
  Key never leave the device (operator-blind).

## Android-specific notes

- **Edge-to-edge / insets:** draw the `bg` behind the system bars and pad content with
  `WindowInsets.safeDrawing` so the card isn't under the status/nav bars.
- **Cleartext to a local instance:** reaching `http://localhost`/a LAN instance needs a network-security
  config allowing cleartext for those hosts (the Android analog of Apple's `NSAllowsLocalNetworking`);
  content stays E2EE regardless. Production HTTPS instances are unaffected.
- **UI tests:** Compose UI tests drive `onNodeWithText`/`...ContentDescription`; give fields stable
  `testTag`s. Screenshots are captured headlessly with **`adb exec-out screencap`** on the emulator — no
  macOS-style TCC wall, so the live UI round-trip + screenshots run here.

## Checklist

- Colors come from the Veyq theme (a parity role), values equal to the parity table — not Material defaults.
- Screen built from `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox` composables.
- Card width-capped + centred on Medium/Expanded; verified on a **phone and a tablet** size.
- Light + dark both checked; content padded for system bars (edge-to-edge).
- Memory/Chapter language; operator-blind reassurance on the unlocked state.
