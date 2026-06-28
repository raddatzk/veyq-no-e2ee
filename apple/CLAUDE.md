# apple (Swift / SwiftUI)

The Apple universal client. Loaded in addition to the root `CLAUDE.md` when working here. All content crypto
runs on-device; the server is blind.

## Prove your work
- `swift test` from `apple/` — builds the SwiftPM package and runs the crypto **conformance vectors**
  (`Tests/VeyqCryptoTests`, which load `../../contracts/crypto/vectors/v1.json`) plus the `VeyqKit` data-layer
  tests. A green run is the parity guarantee with the web + Android suites (ADR 0009).
- **The universal app** is generated from `project.yml` (xcodegen) — never a hand-edited pbxproj. The
  `.xcodeproj` and the plugin's `Sources/*/GeneratedSources/` are gitignored. Regenerate + build:
  ```sh
  cd apple && xcodegen generate
  xcodebuild -project Veyq.xcodeproj -scheme Veyq -destination 'platform=macOS' -skipPackagePluginValidation build
  xcodebuild -project Veyq.xcodeproj -scheme Veyq -destination 'generic/platform=iOS Simulator' -skipPackagePluginValidation build
  ```
  `-skipPackagePluginValidation` is required (headless xcodebuild won't otherwise trust the build plugin).
  The app + UI-test targets are **ad-hoc signed** (`CODE_SIGN_IDENTITY: "-"`) so the UI-test runner can
  inject into the host; an unsigned runner is killed at launch.
- **UI round-trip + screenshots** — `RoundTripUITests` (`UITests/`) drives **connect → sign-in → unlock**
  against a **live instance** and writes the design-reference screenshots. Env-gated (no-ops in a plain
  run); creds reach the in-simulator runner via `TEST_RUNNER_*` and the app via `launchEnvironment`. The
  sign-in fields are prefilled through a `#if DEBUG` seam in `SignInView` because SwiftUI `SecureField`
  drops characters under XCUITest `typeText`. Full how-to + the macOS-TCC caveat:
  [`../docs/design/screenshots/apple/README.md`](../docs/design/screenshots/apple/README.md).
- **Live data-layer round-trip** — `LiveRoundTripTests` (`Tests/VeyqKitTests/`) does login+unlock against a
  real instance over URLSession; env-gated (`VEYQ_LIVE_BASE`/`EMAIL`/`PASSWORD`/`SECRET`), skipped by default.
- **tvOS is libs-only** (no UI head this slice — TV sign-in is passkey/QR handoff, cr-0015 / ADR 0010). Keep
  the shared libraries building for it:
  ```sh
  xcodebuild -project Veyq.xcodeproj -scheme VeyqKit -destination 'generic/platform=tvOS Simulator' -skipPackagePluginValidation build
  ```

## API client
- DTOs are **generated**, never hand-written (API-first). `SetupAPI` / `AuthAPI` / `AccountAPI` are one module
  per `contracts/api/*.openapi.yaml` document (the specs are independent docs with colliding component names,
  so they can't share a module). Each target's `openapi.yaml` is a **symlink** into `contracts/` — edit the
  contract, not the symlink. swift-openapi-generator runs as a **build plugin** (regenerates each build);
  `VeyqApi` re-exports the three so the app imports a single `VeyqApi`. Plugin-only targets carry a tiny
  `Exports.swift` because SPM drops a target with no `.swift` source.

## Conventions
- **`v1` crypto is implemented natively, gated by the shared vectors** ([ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)).
  `Sources/VeyqCrypto/V1.swift` mirrors `web/src/shared/crypto/v1.ts` byte-for-byte; pin a new primitive by
  adding a vector to `v1.json`, then making this target green. Libs: **swift-crypto** (Ed25519 / HKDF /
  AES-GCM / SHA-256, and **`MLKEM768`** for the hybrid KEM) + **swift-sodium** (Argon2id, as in
  `legacy/apple`). The hybrid KEM (X25519 + ML-KEM-768), `asymWrap` (scope grants) and the identity bundle
  are ported (cr-0025); the remaining content-side primitives land with the content slice.
- **Reuse legacy UI/UX — never invent new UI.** Orient on `legacy/apple` (`VeyqCrypto`/`VeyqApiModels`
  structure) and `legacy/iOS/Veyq` (the SwiftUI app), read-only. Port screens/flows; swap the data layer to
  the contracts-generated client + this crypto. Note: `legacy/apple`'s `VeyqCrypto` predates the PQ pivot
  (classic X25519, no ML-KEM) — its Argon/AEAD/auth patterns port directly, its KEM does not.
- **Operator-blind:** seal/derive everything on-device; never send the password, plaintext or raw keys. The
  data layer connects to an **already-bootstrapped** instance (first-run setup is browser-only).

Stack: Swift 6, SwiftUI, SwiftPM. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
