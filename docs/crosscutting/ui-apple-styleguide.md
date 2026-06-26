# Apple UI Styleguide (SwiftUI)

How the [shared design parity](ui-design-parity.md) is realised on the Apple surface (macOS / iOS /
iPadOS, one universal SwiftUI app). The **token values and component contract live in the parity doc** —
this is the thin platform mapping + Apple-specific rules. Shipped in cr-0015 (login + unlock).

## Source of truth

- **Tokens:** `apple/App/VeyqStyle.swift` — `enum VeyqStyle` holds the canonical role colors as adaptive
  `Color(light:dark:)` (resolved via a dynamic `UIColor`/`NSColor`, so they follow the system appearance),
  plus `radius`/`radiusSm`. These are the parity-doc values; keep them byte-equal to that table.
- **Components:** `AuthCard`, `VeyqField`, `PrimaryButton`, `MessageBox` in the same file map 1:1 to the
  shared component contract. Reuse them; don't hand-roll a one-off card or field.
- **Flow:** `App/` — `RootView` switches `connect → signIn → home` off `AppModel`; screens are
  `ConnectView` / `SignInView` / `HomeView`.

## Native realisation

- **Native, not Catalyst.** This is a true macOS app target alongside iOS, so UIKit-only APIs are off
  limits — `Color(light:dark:)`, keyboard traits, etc. are behind `#if canImport(UIKit)/AppKit`.
- **Responsive** via a `maxWidth` frame cap on the card (centred) — inert on iPhone (already narrower),
  keeps iPad/Mac from stretching. SwiftUI `horizontalSizeClass` where finer adaptation is needed.
- **Adaptive color** follows the system light/dark automatically (no manual theme toggle).

## Apple-specific notes

- **`SecureField` + XCUITest:** secure fields drop characters under `typeText`; the UI round-trip test
  prefills via a `#if DEBUG` launch-env seam (see `apple/CLAUDE.md` / the screenshots README). Not a visual
  rule — a testing gotcha to remember.
- **Text input:** `.textInputAutocapitalization`/`.keyboardType` are iOS-only — guard with `#if os(iOS)`.
- **Strings** are NFC-normalised at the crypto boundary (`VeyqCrypto`), not the view's concern.

## Checklist

- New color/letter-spacing comes from `VeyqStyle` (a parity role), not a literal.
- New screen built from `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox`.
- Card width-capped + centred; verified on an iPhone **and** an iPad/Mac size.
- Light + dark both checked (adaptive colors make this cheap).
