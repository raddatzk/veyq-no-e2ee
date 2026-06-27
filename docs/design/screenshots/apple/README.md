# Apple client screenshots — login + unlock (cr-0015)

The veyq universal SwiftUI app (macOS / iOS / iPadOS) driven through the **connect → sign-in → unlock**
flow against a real local instance. These are the **cross-platform design reference** for the Windows
(WinUI 3) and Linux (GTK4) heads — same design system, copy, and flow (ADR 0010: shared contracts, one
look).

| file | screen |
|------|--------|
| `ios-01-connect.png` | Connect — enter the instance URL |
| `ios-02-signin.png`  | Sign in (step 1) — email + password, with **Upload kit / Scan QR** (fills email + parks Secret Key) |
| `ios-03-unlock.png`  | Unlock floor (step 2) — Secret Key + the **device unlock-level chooser**, with **Upload kit / Scan QR** |
| `ios-04-home.png`    | Unlocked — session held, vault open on-device |

The two-step flow + Emergency-Kit import (upload + live QR scan, on both steps) landed in cr-0017. The
floor's **device unlock-level chooser** (`ios-03-unlock.png`) landed in cr-0019.

## Device trust (cr-0019, L1 — "only password")

Driven by `RoundTripUITests.testTrustDeviceLifecycle` through the **real Keychain in-app**, against a
fresh live instance: floor unlock at "Only my password" → trusted home → sign out → re-login **folds
straight to home** (no floor) → untrust → re-login back to the floor.

| file | screen |
|------|--------|
| `ios-trust-01-floor-chooser.png`      | Floor with the level chooser — no default (Unlock disabled until a level is picked); L2/L3 "Coming soon" |
| `ios-trust-02-floor-only-password.png`| "Only my password" selected (Unlock enabled) |
| `ios-trust-03-home-trusted.png`       | Unlocked home — trusted, with **Stop trusting this device** |
| `ios-trust-04-relogin-fold.png`       | After sign-out + re-login: unlocks straight to home (the DEVICE unlock folds into login) |
| `ios-trust-05-floor-after-untrust.png`| After untrust + re-login: back to the Secret-Key floor |

## How they're generated (reproducible)

Captured by the `RoundTripUITests` UI test (`apple/UITests/`), which drives the **real app** against a
**real backend** and writes each screen to the result bundle. To regenerate:

1. Backend up on a fresh DB: `docker compose -f infra/docker-compose.yml up -d` then
   `cd backend && ./gradlew quarkusDev`.
2. Seed an owner with a known Secret Key: `cd web && npx tsx scripts/seed-owner.mts` (prints
   email / password / secretKey).
3. Run the UI round-trip on an **iOS 26 simulator** (passes creds via `TEST_RUNNER_*`):
   ```sh
   cd apple && TEST_RUNNER_VEYQ_LIVE_BASE=http://localhost:8080 \
     TEST_RUNNER_VEYQ_EMAIL=… TEST_RUNNER_VEYQ_PASSWORD=… TEST_RUNNER_VEYQ_SECRET=… \
     TEST_RUNNER_VEYQ_SHOT_DIR=/tmp \
     xcodebuild test -project Veyq.xcodeproj -scheme Veyq \
       -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -skipPackagePluginValidation \
       -resultBundlePath /tmp/veyq_ui.xcresult
   xcrun xcresulttool export attachments --path /tmp/veyq_ui.xcresult --output-path /tmp/shots
   ```

## macOS desktop captures — pending

The same UI test targets macOS, but a CLI-launched **macOS UI-test runner is killed by TCC** unless the
controlling process is granted **Accessibility** in System Settings → Privacy & Security (a one-time user
action). Until that grant is in place, the desktop (wider, centred-card) captures aren't produced
headlessly — the iOS captures above stand in as the design reference (identical design system, only the
window width differs). Re-run the UI test with `-destination 'platform=macOS'` after granting Accessibility
to add `macos-*.png`.
