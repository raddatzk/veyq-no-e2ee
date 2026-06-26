# android (Kotlin / Jetpack Compose)

The Android universal client, built fresh in parallel to Apple. Loaded in addition to the root `CLAUDE.md`
when working here. All content crypto runs on-device; the server is blind.

## Prove your work
- `./gradlew :crypto:test` from `android/` — runs the crypto **conformance vectors**
  (`crypto/src/test`, which load `contracts/crypto/vectors/v1.json` by walking up to the repo root). A green
  run is the parity guarantee with the web + Apple suites (ADR 0009).
- `:crypto` is a **pure Kotlin/JVM module** (no Android SDK needed to test it). App/UI modules (Compose,
  `com.android.application`, requiring the Android SDK in `~/Library/Android/sdk`) arrive with the
  login/unlock + UI slices.
- **Toolchain:** pin Kotlin to the backend's version (currently **2.3.21**) — older Kotlin crashes its
  compiler on JDK 25. The repo Gradle is 9.5.1.

## Conventions
- **`v1` crypto is implemented natively, gated by the shared vectors** ([ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)).
  `crypto/src/main/kotlin/com/veyq/crypto/V1.kt` mirrors `web/src/shared/crypto/v1.ts` byte-for-byte;
  pin a new primitive by adding a vector, then making this module green. Libs: **BouncyCastle** (Argon2id /
  HKDF / Ed25519; version constant `ARGON2_VERSION_13`) + the **JDK JCE** for AES-256-GCM. **ML-KEM-768** is
  deferred to the first content/scope slice — login + unlock is classic crypto only.
- **Reuse legacy UI/UX — never invent new UI.** Orient on `legacy/android` (read-only) and the Apple/iOS UI;
  port flows, swap the data layer to the contracts-generated client + this crypto.
- **Operator-blind:** seal/derive everything on-device; never send the password, plaintext or raw keys. The
  data layer connects to an **already-bootstrapped** instance (first-run setup is browser-only).

Stack: Kotlin, Jetpack Compose, Gradle. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
