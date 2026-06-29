---
id: cr-0017
title: Apple — two-step unlock floor + Emergency Kit import (upload + QR scan)
status: done             # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.unlock]
adrs: ["0005", "0010"]
invariants: [operator-blind]
---

## Goal

Bring the Apple client to the web's **two-step** sign-in shape and add **Emergency Kit import** on
**both** steps: split the current single sign-in card into **(1) login** (email + password → `login()`)
and **(2) unlock floor** (Secret Key → `unlock()`), then let a returning user import their kit by
**uploading** it (PDF / any `veyqkit:` token-carrying file) or **scanning** the QR with the camera —

- on the **login** screen: import fills the **email** and **parks the Secret Key** (the kit carries
  email + Secret Key, **not** the password — that is always typed); after login the floor opens with the
  Secret Key already pre-filled;
- on the **unlock floor**: import fills the **Secret Key** (the fallback for someone who logged in
  manually and only needs the kit at the floor).

This is the Apple twin of the web flow ([cr-0007](cr-0007-kit-import.md)) and reuses the web's
`pendingSecretKey` parking idea (today fed by the setup handover; here also by a login-screen kit import).
Closes the `secret-key-import-from-kit` criterion of
[uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) for the apple surface.

**Scope:** the two-step flow refactor + kit import (file + live QR) on **both** the login screen and the
unlock floor, with email-prefill + Secret-Key parking from a login-screen import.
**Deferred:** trusted-device auto-unlock (a later apple increment — the floor is the only post-login path
for now), importing a QR from a *static image file* (live camera scan + kit-PDF/token import only), ML-KEM.

## Context & constraints

- **Web is two-step, native is one** (the gap this closes): web `LoginView` shows email+password first
  (`login()`), then a Secret-Key **floor** card (`unlock()`) where upload + scan live. cr-0015 collapsed
  that into one card ("mirroring the legacy single-form UX"); this restores parity and gives the kit import
  its natural home (and the future trusted-device auto-unlock its docking point — login → maybe auto-unlock
  → else floor).
- **Kit format is already machine-readable** (do not reinvent): `kitPayload` writes
  `veyqkit:<b64url(JSON{v:1,email,sk})>` as a QR **and** uncompressed in the PDF bytes; web `parseKit(text)`
  regex-extracts it. Port `parseKit` to Swift (a ~6-line pure function: prefix match → base64url decode →
  JSON `{v,email,sk}` validate). File import reads the bytes **as text** and runs `parseKit` — no PDF
  library, exactly like web (`importKitFromFile`).
- **Operator-blind:** import is purely on-device — the kit is read locally, the email + Secret Key fill
  local fields / parked state, nothing is sent. No invariant at risk. The kit carries **email + Secret Key,
  not the password**, so an import never fills the password.
- **Secret-Key parking (web parity):** `AppModel` holds a `pendingSecretKey` (mirrors web's
  `pendingSecretKey`). A login-screen import sets `email` + parks the Secret Key; on entering the floor the
  `UnlockView` pre-fills its Secret-Key field from the parked value (then clears it from the model). A
  floor import just fills the field directly.
- **QR scanning = AVFoundation** (settled with the user): `AVCaptureSession` + `AVCaptureMetadataOutput`
  (`.qr`) wrapped for SwiftUI — **universal** so it works on the iOS/iPadOS **and** macOS camera (VisionKit
  `DataScannerViewController` is iOS-only; AVFoundation keeps the universal app at parity). Camera needs
  `NSCameraUsageDescription` in Info.plist; permission-denied / no-camera degrades gracefully to
  upload/paste (mirrors web).
- **Reuse the shipped components** (`VeyqStyle`, `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox`) — the
  floor card and the in-field upload/scan affordances stay in the one veyq look (parity doc).

## Phases

- [x] **1 — two-step flow refactor.** `AppModel.Phase` gains `.unlock` between `.signIn` and `.home`; add a
  transient `pendingSecretKey`; split `SignInView` into `SignInView` (email + password → `login()` → advance
  to `.unlock`) and a new `UnlockView` (Secret Key, password carried in `AppModel` transiently → `unlock()`
  → `.home`, pre-filling its field from `pendingSecretKey`); `RootView` switches the new phase; "Use a
  different instance" still resets. Update `RoundTripUITests` to drive the two steps. **Done when:**
  `swift build` + `swift test` green; the env-gated UI round-trip still goes connect → login → unlock → home.
  **DONE** — `AppModel` carries the password between steps; `SignInView` (email+password → `app.login`),
  new `UnlockView` (Secret Key → `app.unlock`, pre-fills from `pendingSecretKey`); macOS + iOS builds green,
  `swift test` 20/20, UITest drives connect → sign-in → unlock → home.
- [x] **2 — parseKit + file import (both steps).** Port `parseKit(_:) -> KitData?` to `VeyqKit` (+
  `KitData{email, secretKey}`); add `importKitFromFile` reading the picked file's bytes as UTF-8 text and
  `parseKit`-ing (kit PDF / token text). Wire a SwiftUI `.fileImporter` (allowed: `.pdf`, `.plainText`)
  behind an **upload** affordance on **both** the `SignInView` (import → fill email + park Secret Key) and
  the `UnlockView` Secret-Key field (import → fill Secret Key); an unreadable file shows a generic message.
  **Done when:** a `VeyqKitTests` case parses a known `veyqkit:` token + a PDF-shaped blob to the exact
  `KitData`, a non-kit blob → nil, and a login-screen import sets `email` + `pendingSecretKey`; `swift test`
  green.
  **DONE** — `Sources/VeyqKit/Kit.swift` (`parseKit` + `importKitFromData`, base64url padding restored);
  `KitTests` 3/3 (token round-trip, PDF-shaped bytes, non-kit/wrong-version → nil). Shared `KitImportBar`
  (upload now, scan in phase 3); SignInView import fills email + parks SK (+ confirmation), UnlockView import
  fills SK. macOS + iOS builds green.
- [x] **3 — live QR scan (AVFoundation, universal).** A `KitScanner` (UI/NSViewControllerRepresentable over
  `AVCaptureSession` + metadata `.qr`) presented from a **scan** affordance on **both** steps; decoded
  string → `parseKit` → (login) email + parked Secret Key / (floor) Secret Key; cancel + permission-denied /
  no-camera fall back to upload/paste. `NSCameraUsageDescription` added. **Done when:** `swift build` green
  on macOS + iOS; manual smoke: scanning a kit QR on the login screen fills the email and pre-fills the
  Secret Key at the floor, and unlocks (camera scan shares the `parseKit` path, so it's manual where a camera
  exists — the automated round-trip covers the file path).
  **DONE** — `KitScanner` (universal `CameraPreview`: `UIView`/`NSView` over `AVCaptureVideoPreviewLayer`;
  `CameraScanController` requests access, wires a `.qr` metadata output, `nonisolated(unsafe)` session on a
  serial queue for Swift-6 concurrency); scan sheet on both screens, foreign QRs ignored, denied/no-camera
  → message. `NSCameraUsageDescription` added. macOS + iOS builds green.
- [x] **4 — live round-trip + screenshots + docs.** Update the live UI round-trip for the two-step flow;
  re-capture screenshots (now `connect / signin / unlock-floor / home`, the **login + floor** both showing
  upload+scan) under `docs/design/screenshots/apple/`; record the apple ref on `secret-key-import-from-kit`
  in `uc.account.unlock` coverage; note the two-step floor + kit import (both steps) in the use-case if
  needed. **Done when:** round-trip green, screenshots committed, `validate_coverage.py` green.
  **DONE** — `RoundTripUITests` (two-step) green on the iOS-26 simulator against the live instance;
  screenshots committed (`ios-01-connect` / `ios-02-signin` / `ios-03-unlock` / `ios-04-home`, sign-in +
  floor both showing Upload kit / Scan QR) + README updated; `secret-key-import-from-kit` apple ref recorded;
  `validate_coverage.py` green.

## Definition of done

The Apple app signs in in two steps (email+password → login, then a Secret-Key **floor** → unlock). A kit
can be imported on **both** steps — by **uploading** the Emergency Kit (PDF / token file) or **scanning**
the QR (iOS/iPadOS/macOS via AVFoundation), decoded **on-device** via the ported `parseKit`: a login-screen
import fills the email and parks the Secret Key (pre-filled at the floor), a floor import fills the Secret
Key. The kit never carries the password. An unreadable kit fails generically; typing/pasting still works;
nothing leaves the device. Coverage records the apple `secret-key-import-from-kit`. Parity with the web
two-step flow restored. Keep this file as a record after merge.
