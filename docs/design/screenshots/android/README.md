# Android client screenshots — login + unlock (cr-0016)

The veyq Android app (Jetpack Compose, phone/tablet) driven through the **connect → sign-in → unlock**
flow against a real local instance. Same design system, copy, and flow as the Apple captures
([../apple](../apple)) — one veyq look across heads (ADR 0010: shared contracts, native everywhere).

| file | screen |
|------|--------|
| `android-01-connect.png` | Connect — enter the instance URL |
| `android-02-signin.png`  | Sign in (step 1) — email + password, with **Upload kit / Scan QR** (fills email + parks Secret Key) |
| `android-03-unlock.png`  | Unlock floor (step 2) — Secret Key, with **Upload kit / Scan QR** (fills Secret Key) |
| `android-04-home.png`    | Unlocked — session held, vault open on-device |

The two-step flow + Emergency-Kit import (upload + live QR scan, on both steps) landed in cr-0018.

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
