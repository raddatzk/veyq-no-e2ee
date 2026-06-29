# Android client screenshots — login + unlock (cr-0016)

The veyq Android app (Jetpack Compose, phone/tablet) driven through the **connect → sign-in → unlock**
flow against a real local instance. Same design system, copy, and flow as the Apple captures
([../apple](../apple)) — one veyq look across heads (ADR 0010: shared contracts, native everywhere).

| file | screen |
|------|--------|
| `android-01-connect.png` | Connect — enter the instance URL |
| `android-02-signin.png`  | Sign in (step 1) — email + password, with **Upload kit / Scan QR** (fills email + parks Secret Key) |
| `android-03-unlock.png`  | Unlock floor (step 2) — Secret Key + the **device unlock-level chooser**, with **Upload kit / Scan QR** |
| `android-04-home.png`    | Unlocked — the **Memory shell** (cr-0039): personal space, Memory + folder cards |
| `android-05-folder.png`  | Drilled into a folder (compact — single pane) |
| `android-06-home-wide.png`   | The Memory shell on an **expanded** (≈900dp) window — the two-pane `PermanentNavigationDrawer` (spaces rail + content) |
| `android-07-folder-wide.png` | Drilled into a folder on the expanded window |

The two-step flow + Emergency-Kit import (upload + live QR scan, on both steps) landed in cr-0018; the
floor's **device unlock-level chooser** (`android-03-unlock.png`) landed in cr-0021. The Memory shell +
its responsive compact/expanded captures landed in cr-0039 (the expanded shots were taken by resizing the
phone **portrait** to ≈900dp, since the `veyq_tablet` AVD would not cold-boot locally).

## Device trust (cr-0021, L1 — "only password")

Driven by `RoundTripUiTest.trustDeviceLifecycle` through the **real UI + real Android Keystore** (a
non-extractable Keystore-HMAC `deviceFactor`), against a fresh live instance: floor unlock at "Only my
password" → trusted home → sign out → re-login **folds straight to home** (no floor) → untrust → re-login
back to the floor.

| file | screen |
|------|--------|
| `android-trust-01-floor-chooser.png`      | Floor with the level chooser — no default; L0/L1 active, L2/L3 "Coming soon" |
| `android-trust-02-home-trusted.png`       | Unlocked home — trusted, with **Stop trusting this device** |
| `android-trust-03-relogin-fold.png`       | After sign-out + re-login: unlocks straight to home (the DEVICE unlock folds into login) |
| `android-trust-04-floor-after-untrust.png`| After untrust + re-login: back to the Secret-Key floor |

## Passwordless levels (cr-0022, L2 biometric / L3 no-login)

The floor's chooser now offers all four levels (cr-0022). On the emulator (no fingerprint enrolled),
**Fingerprint / Face** correctly shows **"Not available"** (`BiometricGate.available` gate) and **Open
straight in** (L3) is selectable. Driven by `NoLoginAutoRestoreUiTest.noLoginAutoRestore`, which enrols L3
then **closes + relaunches the activity** (a fresh AppModel re-runs `bootstrap`):

| file | screen |
|------|--------|
| `android-trust-l3-01-floor-no-login.png` | Floor — all four levels; L2 "Not available" (no emulator biometric), L3 "Open straight in" selected |
| `android-trust-l3-02-home.png`           | Unlocked home after enrolling L3 |
| `android-trust-l3-03-autorestore.png`    | After an activity **relaunch**: opens straight to home — no connect, no floor (auto-restore) |

**L2 (fingerprint) is a manual smoke** — enrolling a fingerprint on the emulator (so `adb emu finger touch`
works) isn't cleanly automatable; the L2 *data* path is unit-tested and the chooser gate is shown above.

## Settings — theme + language (cr-0043)

Driven by `RoundTripUiTest.settingsThemeAndLanguage`: reach the Memory shell, open Settings from the rail
gear, flip the **theme** to dark (the whole palette flips live) and the **language** to German (every string
re-renders **in place** — the sheet title becomes "Einstellungen" *and* the shell behind it becomes
"Erinnerungen", i.e. no navigation reload). Captured on a phone (compact) **and** an expanded (~900dp) window
(`-wide`, via `adb shell wm density 192`).

| file | screen |
|------|--------|
| `android-settings-01-light.png` / `-wide` | Settings sheet — Appearance + Language (System), Account, Instance |
| `android-settings-02-dark.png` / `-wide`  | Theme → **Dark**: the palette flips live (sheet + shell behind) |
| `android-settings-03-de.png` / `-wide`    | Language → **Deutsch**: sheet **and** shell re-render in German, no reload |

> The emulator's Host resolves to `10.0.2.2`, which the tenant-domain validator rejects as a bare IP. Seed a
> tenant with a valid hostname (`seed-tenant.mts`, e.g. `VEYQ_DOMAIN=android.veyq.test`) then rewrite its
> `veyq_tenant.domain` to `10.0.2.2` directly in the DB so the shell's Host-scoped personal space resolves.

Captured on the **API-37 emulator** (`Pixel_10` AVD). Unlike macOS UI-test runners, the Android emulator
has no TCC/Accessibility wall, so these are produced headlessly from the CLI.

## How they're generated (reproducible)

Captured by the `RoundTripUiTest` instrumented test (`android/app/src/androidTest/`), which drives the
**real app** against a **real backend** and saves each screen through the AndroidX `TestStorage` service
(AGP copies the files back to the host). To regenerate:

1. Backend up on a fresh DB: `docker compose -f infra/docker-compose.yml up -d` then
   `cd backend && ./gradlew quarkusDev`.
2. Seed an owner with a known Secret Key: `cd web && npx tsx scripts/seed-owner.mts` (prints
   email / password / secretKey).
   - **For the Memory-shell captures (cr-0039)** the owner also needs a **tenant whose domain matches the
     emulator's Host** — the personal scope is tenant-scoped by Host (resolved as `10.0.2.2`, port-stripped).
     `seed-owner` mints only the owner, so create the tenant + the owner's membership first (the web admin
     create-tenant + accept flow for domain `10.0.2.2`). Without it the shell correctly shows
     "Couldn't open your space" (no tenant → no scope).
3. Boot the emulator and run the UI round-trip (the emulator reaches the host instance at `10.0.2.2`):
   ```sh
   ~/Library/Android/sdk/emulator/emulator -avd Pixel_10 -no-snapshot &
   cd android && ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.veyqBase=http://10.0.2.2:8080 \
     -Pandroid.testInstrumentationRunnerArguments.veyqEmail=kevin@veyq.test \
     "-Pandroid.testInstrumentationRunnerArguments.veyqPassword=correct horse battery staple" \
     "-Pandroid.testInstrumentationRunnerArguments.veyqSecret=<secretKey from step 2>"
   ```
4. Copy the pulled PNGs here:
   ```sh
   cp "android/app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/"*/android-*.png \
     docs/design/screenshots/android/
   ```

The same instrumented test is the env-gated UI round-trip proof (skipped without `veyqBase`); the data-layer
round-trip is `android/client/.../LiveRoundTripTest.kt` (`VEYQ_LIVE_BASE=… ./gradlew :client:test`).
