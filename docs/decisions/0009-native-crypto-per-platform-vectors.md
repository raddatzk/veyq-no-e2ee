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

- **Libraries:** Swift → `swift-crypto` (Ed25519, HKDF, AES-GCM, and **`MLKEM768`** for the hybrid KEM) +
  `swift-sodium` (Argon2id); Kotlin → **BouncyCastle** 1.81 (Argon2id, Ed25519, HKDF, AES-GCM, ML-KEM all in one).
- **Slice order mirrored the dependency chain.** The **auth/unlock subset** landed first — `primitives`
  (HKDF, AES-GCM), `kek` (Argon2id KEKs), `kdf_params` (the fixed `u32be t‖m‖p‖dkLen‖salt` layout),
  `wrap` (symmetric), the auth keypair derivation, `recovery_auth_tag`. **ML-KEM-768** (`kem_v1`, the
  asymmetric wrap / scope grants) followed in **cr-0025** (now shipped on both native platforms) — native
  first connected to an already-bootstrapped instance doing login + unlock, neither of which touches the KEM.
- The vector file stays the **single source of truth**; pinning a new primitive = adding a vector, then
  making every platform green.

## Consequences

- **Three impls (TS, Swift, Kotlin), one contract.** New crypto is pinned by a vector first; each platform
  follows. The `kdf_params` vector already calls this out ("web encodes at setup, mobile decodes at login").
- **Native started without the hardest part.** Login + unlock needed only classic crypto, all present in
  `swift-crypto` / BouncyCastle, so the Apple + Android foundations landed before the ML-KEM port (cr-0025).
- **Rejected — a shared Rust/UniFFI crypto core:** it would guarantee parity from one source, but adds a new
  toolchain (Rust + FFI build) to a Quarkus/Kotlin + Swift + TS stack. The vectors already provide the
  parity contract, so per-platform is lighter and on-brand. Revisit only if the hybrid-KEM combiner proves
  too error-prone to re-implement safely across platforms.
  **This rejection is under live challenge — [ADR 0041](0041-shared-crypto-core-deferred.md)** (`Proposed`)
  re-argues the same core with a narrower scope (the crypto *engine* only, thin native shells) against a
  **broader trigger** than the revisit clause above: the native un-park
  ([ADR 0043](0043-web-first-v1-native-parked.md)) — i.e. *before* apple/android crypto would be hand-written a
  second and third time, whether or not the combiner has proven error-prone. That narrow trigger is **not**
  withdrawn; it stands as a second, earlier one. 0041 does not supersede this ADR — it is a proposal, and a
  proposal cannot supersede an accepted decision. [ADR 0010](0010-native-clients-shared-contracts-not-code.md)
  generalises this rejection to the whole client and carries the same pointer.
- **`legacy/apple` (`VeyqCrypto`, swift-sodium) is a structural reference only** — it predates the PQ pivot
  (classic X25519, no ML-KEM), so its auth/AEAD/Argon patterns port directly but its KEM does not.
