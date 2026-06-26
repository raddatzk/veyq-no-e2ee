# ADR 0009 — Native clients implement the v1 crypto suite per-platform, gated by the conformance vectors

- Status: Accepted
- Date: 2026-06-26

## Context

Operator-blind E2EE everywhere means every client must produce **byte-identical** crypto: the same Argon2id
KEKs, the same wrap blobs, the same hybrid-KEM ciphertexts. The web client has a TypeScript implementation
(`web/src/shared/crypto/v1.ts`) pinned to **`contracts/crypto/vectors/v1.json`** — the cross-platform
contract. We are now starting the **Apple (Swift)** and **Android (Kotlin)** clients in parallel; they need
the same `v1` suite (hybrid X25519 + ML-KEM-768, Ed25519, Argon2id, AES-256-GCM, HKDF). How do we guarantee
parity across three independent implementations?

## Decision

**Each platform implements `v1` natively; the conformance vectors are the cross-implementation contract.**
There is no shared FFI/Rust core — instead, every implementation ships a **conformance test that reads
`contracts/crypto/vectors/v1.json`** and must pass it. The vectors *are* the parity guarantee: drift is
caught by a red test, not by code review.

- **Libraries:** Swift → `swift-crypto` (Ed25519, HKDF, AES-GCM; ML-KEM via its MLKEM module later) + an
  Argon2id dependency; Kotlin → **BouncyCastle** (Argon2id, Ed25519, HKDF, AES-GCM, ML-KEM all in one).
- **Slice order mirrors the dependency chain.** The **auth/unlock subset** lands first — `primitives`
  (HKDF, AES-GCM), `kek` (Argon2id KEKs), `kdf_params` (the fixed `u32be t‖m‖p‖dkLen‖salt` layout),
  `wrap` (symmetric), the auth keypair derivation, `recovery_auth_tag`. **ML-KEM-768** (`kem_v1`, the
  asymmetric wrap / scope grants) is **deferred to the first content/scope slice** — native first connects
  to an already-bootstrapped instance and does login + unlock, neither of which touches the KEM.
- The vector file stays the **single source of truth**; pinning a new primitive = adding a vector, then
  making every platform green.

## Consequences

- **Three impls (TS, Swift, Kotlin), one contract.** New crypto is pinned by a vector first; each platform
  follows. The `kdf_params` vector already calls this out ("web encodes at setup, mobile decodes at login").
- **Native starts without the hardest part.** Login + unlock need only classic crypto, all present in
  `swift-crypto` / BouncyCastle, so the Apple + Android foundations can land before the ML-KEM port.
- **Rejected — a shared Rust/UniFFI crypto core:** it would guarantee parity from one source, but adds a new
  toolchain (Rust + FFI build) to a Quarkus/Kotlin + Swift + TS stack. The vectors already provide the
  parity contract, so per-platform is lighter and on-brand. Revisit only if the hybrid-KEM combiner proves
  too error-prone to re-implement safely across platforms.
- **`legacy/apple` (`VeyqCrypto`, swift-sodium) is a structural reference only** — it predates the PQ pivot
  (classic X25519, no ML-KEM), so its auth/AEAD/Argon patterns port directly but its KEM does not.
