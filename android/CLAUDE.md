# android (Kotlin / Jetpack Compose)

The Android universal client, built fresh in parallel to Apple. Loaded in addition to the root `CLAUDE.md`
when working here. All content crypto runs on-device; the server is blind.

## Modules
- `:crypto` — pure Kotlin/JVM `v1` suite (cr-0014); the parity guarantee with web/Apple (ADR 0009).
- `:client` — **pure Kotlin/JVM** data layer (cr-0016): the contracts-generated OpenAPI clients
  (`setup/auth/account`, each in its own `com.veyq.api.*` package) + `Session`/`Vault`/`VeyqClient`
  orchestration. No Android SDK → testable headless, reusable by a future JVM agent (ADR 0010).
- `:app` — thin Android (Jetpack Compose) UI head over `:client`; requires the Android SDK.

## Prove your work
- `./gradlew :crypto:test` — crypto **conformance vectors** (`contracts/crypto/vectors/v1.json`); green = parity (ADR 0009).
- `./gradlew :client:test` — login + unlock **data-layer** tests (`LoginTest`/`UnlockTest`, MockWebServer,
  hermetic). The env-gated `LiveRoundTripTest` runs only with `VEYQ_LIVE_BASE` set.
- `./gradlew :app:testDebugUnitTest` — `:app` JVM unit tests (no SDK), incl. the i18n `LocalizationTest`
  (resolution + English fallback + en/de key parity; cr-0043).
- `./gradlew :app:assembleDebug` — builds the Compose UI head (AGP + SDK).
- `./gradlew :app:connectedDebugAndroidTest` — the env-gated **live UI round-trip** + screenshot capture on
  an emulator (pass `-Pandroid.testInstrumentationRunnerArguments.veyqBase=…` etc.; see
  `docs/design/screenshots/android/README.md`).
  - **Shell/settings round-trips need a tenant whose domain = the emulator's Host (`10.0.2.2`)** — the
    personal scope is Host-scoped, else "Couldn't open your space". Two gotchas, both real, both already-bitten:
    (a) the create-tenant API rejects bare IPs, so `seed-tenant.mts` with a valid hostname (e.g.
    `VEYQ_DOMAIN=android.veyq.test`) then `UPDATE veyq_tenant SET domain='10.0.2.2'` directly in the DB; (b)
    `seed-tenant.mts` reads the invite secret from the **backend log file**, so the dev backend must be
    started with its stdout redirected to a file (`quarkusDev > /tmp/veyq.log 2>&1`) and `VEYQ_LOG` pointed at
    it. **Smoke this seeding before relying on a live round-trip as a done-gate.**

## Toolchain (bleeding-edge, pinned together)
- Repo Gradle is **9.5.1** (JDK 25). That forces **AGP 9.2.1**, whose **built-in Kotlin is KGP 2.2.10** —
  so **all modules pin Kotlin 2.2.10** (KGP 2.3.21 is incompatible with AGP 9: its legacy
  `KotlinAndroidTarget` references the removed `BaseVariant`). `:crypto:test` is green on 2.2.10 / JDK 25.
- **All plugins are declared once in the root `build.gradle.kts` (`apply false`)** so AGP + KGP share one
  classloader; modules apply them by id without a version. Skipping this re-triggers the `BaseVariant`
  NoClassDefFound. `:app` uses AGP built-in Kotlin (no `kotlin.android` plugin) + the Compose compiler
  plugin; configure jvmTarget via the top-level `kotlin { compilerOptions { … } }`.
- **compileSdk 36** + the late-2025 (36-wave) AndroidX: the 2026 wave needs an android-37 platform that is
  not yet on the CLI SDK channel (only the API-37 emulator image is). Bump both when 37 ships.
- The `:client` OpenAPI generator strips the backend-only `x-field-extra-annotation` from a sanitized spec
  copy (no `jakarta` in the client) and produces a **blocking** API (suspend internally; the orchestration
  and `:app` call it on `Dispatchers.IO`).

## Conventions
- **`v1` crypto is implemented natively, gated by the shared vectors** ([ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)).
  `crypto/src/main/kotlin/com/veyq/crypto/V1.kt` mirrors `web/src/shared/crypto/v1.ts` byte-for-byte;
  pin a new primitive by adding a vector, then making this module green. Libs: **BouncyCastle** (Argon2id /
  HKDF / Ed25519 / X25519 / **ML-KEM-768**; version constant `ARGON2_VERSION_13`; **bcprov ≥ 1.79** for
  FIPS-203 ML-KEM — 1.78.1 only had the older round-3 Kyber) + the **JDK JCE** for AES-256-GCM. The hybrid
  KEM, `asymWrap` (scope grants) and the identity bundle are ported (cr-0025); remaining content-side
  primitives land with the content slice.
- **Reuse the UI/UX — never invent new UI.** Built **fresh in parallel to Apple** (there is no legacy Compose
  UI to copy); match the **same screens** as the Apple iOS port (`legacy/iOS/Veyq`) + web — swap only the data
  layer to the contracts-generated client + this crypto. `legacy/android` is read-only structure reference.
- **Responsive (phone / tablet).** Lay out by **`WindowSizeClass`**, not fixed widths: a list/detail that is a
  single pane on **Compact** width (phone) and a two-pane split (`ListDetailPaneScaffold`) on **Medium/Expanded**
  (tablet, foldable, landscape). Verify a change on both a phone and a tablet/expanded window before done.
- **Operator-blind:** seal/derive everything on-device; never send the password, plaintext or raw keys. The
  data layer connects to an **already-bootstrapped** instance (first-run setup is browser-only).

Stack: Kotlin, Jetpack Compose, Gradle. Working rules: [`../docs/ai/README.md`](../docs/ai/README.md).
