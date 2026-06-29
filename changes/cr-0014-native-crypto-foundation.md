---
id: cr-0014
title: Native v1 crypto foundation (auth/unlock subset) — apple + android, conformance-vector-tested
status: done             # planned → in-progress → done (then keep as a record)
satisfies: []            # crypto foundation — enables native uc.account.login + unlock (next CR), no use-case fully delivered here
adrs: ["0009", "0003"]
invariants: [operator-blind]
---

## Goal

Stand up the **`apple/`** and **`android/`** native projects (both empty today) and give each a `VeyqCrypto`
module implementing the **auth/unlock subset of the `v1` suite**, each verified against
`contracts/crypto/vectors/v1.json` ([ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)).
This is the bedrock for native **login + unlock** (the next CR): native connects to an already-bootstrapped
instance, so the first crypto it needs is classic only — **no ML-KEM**. The vectors are the cross-impl
contract; parity with the web TS suite is proven by a green conformance test, not by review.

## Scope — the auth/unlock subset (mirrors `web/src/shared/crypto/v1.ts`)

In: HKDF-SHA256; AES-256-GCM `aeadSeal`/`aeadOpen`; Argon2id; `encodeKdfParams`/`decodeKdfParams` (the fixed
`u32be t‖m‖p‖dkLen‖salt` layout); `kekPasswordSecret` / `kekRecovery` / `kekDevicePassword`;
`symWrap`/`symUnwrap`; `deriveAuthSalt` / `deriveAuthKeypair` (Ed25519) / `authSign` / `authVerify`;
`recoveryAuthTag`; the `lp` / `concat` / `u32be` + base64-std helpers.

**Deferred (the next, content/scope slice):** the hybrid KEM (`kem_v1`), `asymWrap`/`asymUnwrap` (scope
grants), `fingerprint`, slug/dedup tokens, journal/chunk frames, CRDT.

## Context & constraints

- **The vectors are purpose-built for this** — `contracts/crypto/vectors/v1.json` has `primitives` (HKDF,
  AES-GCM), `kek` (the Argon2id KEKs), `kdf_params` (the one fixed cross-platform serialization — its note
  literally says "web encodes at setup, mobile decodes at login"), `wrap.sym_master_key`, `recovery_auth_tag`.
  Each native impl must reproduce the exact `kek` / `blob` / `encoded` outputs — the test asserts byte-equality.
- **Input construction must match the TS suite exactly** (e.g. `kekPasswordSecret = Argon2id(lp(secretKey) ‖
  lp(utf8(password)), salt, params)`); the pinned vector catches any divergence.
- **`legacy/apple/Sources/VeyqCrypto`** (swift-sodium) is a **structural reference** — its Argon/AEAD/auth
  patterns port directly; it has no ML-KEM (classic, pre-PQ), which is fine (deferred here).
- **Libs:** Swift → `swift-crypto` (Ed25519/HKDF/AES-GCM) + an Argon2id dependency (libsodium via swift-sodium
  is the proven option from legacy); Kotlin → **BouncyCastle** (Argon2id/Ed25519/HKDF/AES-GCM).
- Toolchains confirmed present: Swift 6.3 / Xcode 26.6; Java 25 + Android SDK (`~/Library/Android/sdk`).

## Phases

- [x] **1 — apple.** SwiftPM package under `apple/` (`Package.swift` → swift-crypto + swift-sodium):
  `Sources/VeyqCrypto/V1.swift` implements the subset (HKDF/AES-GCM/Ed25519/SHA-256 via swift-crypto; Argon2id
  via swift-sodium/libsodium), `Tests/VeyqCryptoTests/V1ConformanceTests.swift` loads the shared
  `contracts/crypto/vectors/v1.json` (via `#filePath`) and asserts each subset vector. **Done:** `swift test`
  **9/9** green — HKDF, AES-GCM (blob byte-match + open), **Argon2id KEKs ×4 byte-identical** (password_secret,
  device_password, recovery, recovery_m32768 → libsodium-Argon2id == noble-Argon2id, the key parity proof),
  kdf_params encode+decode round-trip, sym-wrap blob byte-match + unwrap, recovery_auth_tag, auth keypair
  sign/verify. First build ~49s (deps resolve + libsodium build).
- [x] **2 — android.** Gradle project under `android/` (own 9.5.1 wrapper, `settings.gradle.kts`, **`:crypto`
  as a pure Kotlin/JVM module** so its test needs no Android SDK). `V1.kt` mirrors the subset — BouncyCastle
  for Argon2id/HKDF/Ed25519, JDK JCE for AES-256-GCM — and `V1ConformanceTest.kt` (kotlin.test + org.json)
  loads the **same** `contracts/crypto/vectors/v1.json` (walks up to find it). **Done:** `./gradlew :crypto:test`
  **9/9** green — same assertions as Apple, now `BouncyCastle-Argon2id == noble == libsodium`. (Pinned
  Kotlin **2.3.21** to match the backend — 2.1.0 crashed the compiler on JDK 25; BC's version constant is
  `ARGON2_VERSION_13`.)
- [x] **3 — docs.** **Done:** [ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md);
  `apple/CLAUDE.md` + `android/CLAUDE.md` (stack, the `swift test` / `./gradlew :crypto:test` verify command,
  the "reuse legacy UI / swap data layer" rule, the Kotlin-2.3.21-on-JDK-25 + ML-KEM-deferral notes);
  `apple/.gitignore` + `android/.gitignore` (exclude `.build`/`build`/`.gradle`, keep `Package.resolved` +
  the wrapper); inbox **#22** advanced to "starting". Both verify commands ran green in phases 1–2.

## Definition of done

`apple/` and `android/` exist as buildable native projects, each with a `VeyqCrypto` module whose
auth/unlock-subset of `v1` is **byte-identical to the conformance vectors** (`swift test` / `./gradlew
:crypto:test` green). ML-KEM and the content-side primitives are explicitly deferred. The per-platform
CLAUDE.md records the verify command. Bedrock laid for the native login + unlock CR. Keep this file as a
record after merge.
