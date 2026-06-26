---
id: cr-0018
title: Android — two-step unlock floor + Emergency Kit import (upload + QR scan)
status: planned          # planned → in-progress → done (then keep as a record)
satisfies: [uc.account.unlock]
adrs: [0005, 0010]
invariants: [operator-blind]
---

## Goal

Bring the Android client to the web's **two-step** sign-in shape and add **Emergency Kit import** on the
Secret-Key floor: split the current single Compose sign-in card into **(1) login** (email + password →
`login()`) and **(2) unlock floor** (Secret Key → `unlock()`), then let a returning user fill the Secret
Key by **uploading** the kit (PDF / any `veyqkit:` token-carrying file) or **scanning** the QR with the
camera. The Android twin of [cr-0017](cr-0017-apple-kit-import.md) (Apple) and the web flow
([cr-0007](cr-0007-kit-import.md)); closes the `secret-key-import-from-kit` criterion of
[uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) for the android surface.

**Scope:** the two-step flow refactor + kit import (file + live QR) on the unlock floor.
**Deferred:** trusted-device auto-unlock (a later android increment), importing a QR from a *static image
file* (live camera scan + kit-PDF/token import only), ML-KEM.

## Context & constraints

- **Web is two-step, native is one** (the gap this closes): web shows email+password (`login()`), then a
  Secret-Key **floor** (`unlock()`) hosting upload + scan. cr-0016 collapsed that into one Compose card;
  this restores parity and gives the kit import its natural home (and the future trusted-device auto-unlock
  its docking point).
- **Kit format is already machine-readable** (do not reinvent): `veyqkit:<b64url(JSON{v:1,email,sk})>`,
  embedded as a QR **and** uncompressed in the PDF bytes. Port `parseKit(text): KitData?` to `:client`
  (prefix match → base64url decode → JSON `{v,email,sk}` validate — a pure function, MockWebServer-free unit
  test). File import reads the picked file's bytes **as text** and runs `parseKit` — no PDF library, exactly
  like web/Apple.
- **Operator-blind:** import is purely on-device; the Secret Key fills the local field, nothing is sent.
  Email is **not** prefilled (already entered at login in the two-step flow — a kit fills only the Secret
  Key).
- **QR scanning = ML Kit + CameraX, bundled** (settled with the user): `com.google.mlkit:barcode-scanning`
  (bundled model, **no** Google Play Services / Code Scanner dependency) + a CameraX preview/analysis use
  case; needs the runtime **`CAMERA`** permission, with graceful fallback to upload/paste on deny / no
  camera. Lives in `:app` (UI head); `:client` stays pure-JVM (the `parseKit` port + file decode is pure
  Kotlin and unit-tested there).
- **Reuse the shipped Compose components** (`VeyqTheme`, `AuthCard`/`VeyqField`/`PrimaryButton`/`MessageBox`,
  `ui-android-styleguide.md`) — the floor card + in-field upload/scan affordances stay in the one veyq look.

## Phases

- [ ] **1 — two-step flow refactor.** `AppModel.Phase` gains `UNLOCK` between `SIGN_IN` and `HOME`; split
  `SignInScreen` into `SignInScreen` (email + password → `login()` → advance to `UNLOCK`) and a new
  `UnlockScreen` (Secret Key, password carried transiently in `AppModel` → `unlock()` → `HOME`);
  `RootScreen` switches the new phase. Update `RoundTripUiTest` to drive the two steps. **Done when:**
  `:client:test` + `:app:assembleDebug` green; the env-gated UI round-trip goes
  connect → login → unlock → home on the emulator.
- [ ] **2 — parseKit + file import.** Port `parseKit(text): KitData?` (+ `KitData(email, secretKey)`) into
  `:client`; add `importKitFromBytes`/text decode. In `:app`, wire a Storage Access Framework picker
  (`ActivityResultContracts.OpenDocument`, types `application/pdf` + `text/*`) behind an in-field **upload**
  affordance on the `UnlockScreen` Secret-Key field; read the stream as UTF-8 text → `parseKit`; on success
  fill the Secret Key, on an unreadable file show a generic message. **Done when:** a `:client:test` parses a
  known `veyqkit:` token + a PDF-shaped byte blob to the exact `KitData`, and a non-kit blob → null;
  `:client:test` green.
- [ ] **3 — live QR scan (ML Kit + CameraX).** A Compose camera overlay (CameraX preview +
  `ImageAnalysis` → ML Kit `BarcodeScanning`, QR format) presented from a **scan** affordance on the floor;
  decoded string → `parseKit` → fill Secret Key; `CAMERA` runtime permission request, cancel + denied / no
  camera fall back to upload/paste. **Done when:** `:app:assembleDebug` green; manual smoke on the emulator
  (the virtual-scene camera can present a QR) fills the Secret Key and unlocks — camera scan shares the
  `parseKit` path, so it's manual/best-effort where a camera exists; the automated round-trip covers the file
  path.
- [ ] **4 — live round-trip + screenshots + docs.** Update the instrumented round-trip for the two-step
  flow; re-capture screenshots (now `connect / signin / unlock-floor / home`, the floor showing upload+scan)
  via TestStorage under `docs/design/screenshots/android/`; record the android ref on
  `secret-key-import-from-kit` in `uc.account.unlock` coverage. **Done when:** round-trip green, screenshots
  committed, `validate_coverage.py` green.

## Definition of done

The Android app signs in in two steps (email+password → login, then a Secret-Key **floor** → unlock) and, on
the floor, a user can fill the Secret Key by **uploading** the Emergency Kit (PDF / token file) or
**scanning** the QR with the camera (CameraX + bundled ML Kit), both decoded **on-device** via the ported
`parseKit`; an unreadable kit fails generically; typing/pasting still works; nothing leaves the device.
`:client` stays pure-JVM (port + file decode + tests there); the camera scan + `CAMERA` permission live in
`:app`. Coverage records the android `secret-key-import-from-kit`. Parity with the web two-step flow
restored. Keep this file as a record after merge.
