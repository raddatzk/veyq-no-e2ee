---
id: cr-0025
title: Port the hybrid KEM (X25519 + ML-KEM-768) + asymWrap/identity bundle to the native crypto suites
status: planned          # planned → in-progress → done (the file is kept as a record)
satisfies: []            # enabler — unblocks the scope/spaces use-cases (#14, to be refined); no UC yet
adrs: [0009, 0010]
invariants: [operator-blind]
---

## Goal

Bring the **hybrid post-quantum KEM** and the **asymmetric wrap** (the scope-grant primitive) to the
**Apple** and **Android** crypto suites, so native clients can open and seal **scope grants** — the
prerequisite for any scope/content work on native. This is the deferred half of
[cr-0014](cr-0014-native-crypto-foundation.md) (login/unlock shipped classic crypto only; ML-KEM was
"deferred to the first content/scope slice"). The wire format is **already frozen** in the conformance
vectors and **already implemented on web** (`web/src/shared/crypto/v1.ts`) — this CR makes the two native
suites **byte-identical** to those vectors. No contract, backend, or vector change: the vectors
(`kem_v1`, `wrap.asym_scope_grant`, `fingerprint`) already exist.

## Context & constraints

- **Reference (do not deviate):** `web/src/shared/crypto/v1.ts` — `combiner` / `kemEncap` / `kemDecap` /
  `asymWrap` / `asymUnwrap` / `identityKeygen` / identity (de)serialization / `fingerprint`.
- **Vectors to pin (already present):** `contracts/crypto/vectors/v1.json` → `kem_v1.decap_kat`,
  `wrap.asym_scope_grant`, `fingerprint`. Gated by [ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)
  (native crypto, vectors are the contract); per-platform native, no shared code
  ([ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)).
- **Why it's needed:** a grant is `asymWrap(memberIdentityPublicKey, scopeRootKey, "scope-grant")`
  (see [cr-0010](cr-0010-tenant-activation.md), [scopes-and-tenancy](../docs/crosscutting/scopes-and-tenancy.md));
  opening a scope on native means parsing the stored identity **private bundle** into a `HybridSecret` and
  `asymUnwrap`-ing the grant. Today native holds the unwrapped identity private key as **raw bytes**
  (unparsed) and has **no** KEM — so it cannot open a grant.
- **Invariant:** operator-blind unchanged — KEM/asymWrap run on-device; the server stores only the
  ciphertext grant. This CR adds **no** wire surface.
- **Libraries (available, verified):** Apple **swift-crypto 3.15.1** (`MLKEM768`, `Curve25519.KeyAgreement`);
  Android **BouncyCastle 1.78.1** (`org.bouncycastle.pqc.crypto.mlkem.*`, X25519). The `:crypto` module
  stays pure-JVM (no Android SDK).

## The wire format to match (from the reference, for the work order)

- **Hybrid keys:** `HybridPublic = { x: X25519 pub (32), m: ML-KEM-768 pub (1184) }`;
  `HybridSecret = { x: X25519 secret (32), m: ML-KEM-768 secret }`. The ML-KEM secret is **reconstructed
  from a 64-byte seed** (`ml_kem768.keygen(seed)`), which is what the identity private bundle stores.
- **Combiner:** `ikm = lp(ssM) ‖ lp(ssX)`; `salt = SHA256(lp(ctM) ‖ lp(epkX) ‖ lp(pkM) ‖ lp(pkX))`;
  `HKDF-SHA256(ikm, salt, info="veyq/v1/kem", 32)` (`lp` = the existing 4-byte length-prefix helper).
- **kemEncap:** `epkX = X25519.pub(esk)`; `ssX = X25519(esk, pk.x)`; `enc = ML-KEM.encap(pk.m)`;
  `ss = combiner(enc.ss, ssX, enc.ct, epkX, pk.m, pk.x)`; `ct = epkX(32) ‖ enc.ct(1088)` = **1120**.
- **kemDecap:** split `ct` → `epkX(32)`, `ctM(1088)`; `ssX = X25519(sk.x, epkX)`;
  `ssM = ML-KEM.decap(ctM, sk.m)`; `combiner(ssM, ssX, ctM, epkX, pk.m, pk.x)`.
- **asymWrap:** `0x01 ‖ ct(1120) ‖ nonce(12) ‖ aead`, with
  `kek = HKDF-SHA256(ss, salt="", info="veyq/v1/wrap"+context, 32)` and
  `aad = 0x01 ‖ utf8("v1"+context)` (the existing `wrapAad`, ASYM type `0x01`).
- **Identity bundle:** private `{ kemX(32), mlkemSeed(64), sign(32) }`; public
  `{ kemX-pub, kemM-pub(1184), sign-pub }`; canonical public serialization `lp(kemX)‖lp(kemM)‖lp(sign)`;
  `fingerprint` per the `fingerprint` vector.

## Phases

- [ ] **1 — Apple (`VeyqCrypto`).** Add the hybrid KEM (`kemEncap`/`kemDecap` via swift-crypto `MLKEM768`
  + `Curve25519.KeyAgreement`, reconstructing the ML-KEM key from the 64-byte seed), `asymWrap`/`asymUnwrap`,
  the identity bundle (keygen / serialize public / parse private → `HybridSecret` / derive `HybridPublic`)
  and `fingerprint`. Extend `V1ConformanceTests` with: the **`kem_v1` decap KAT** (deterministic), an
  **asym round-trip** (encap→decap), the **fixed `wrap.asym_scope_grant` unwrap** (decap is deterministic
  given the vector `ct`, using the `kem_v1` secret), identity-serialize, and the `fingerprint` vector.
  **Done when:** `swift test` green incl. all new vectors; macOS + iOS `xcodebuild BUILD SUCCEEDED`.
- [ ] **2 — Android (`:crypto`).** The same in `V1.kt` (BouncyCastle `pqc.crypto.mlkem` + X25519,
  ML-KEM key reconstructed from the 64-byte seed), with the matching `V1ConformanceTest` additions.
  **Done when:** `./gradlew :crypto:test` `BUILD SUCCESSFUL` incl. all new vectors.
- [ ] **3 — housekeeping.** Drop the "ML-KEM deferred / out of scope" notes in `apple/CLAUDE.md`,
  `android/CLAUDE.md`, and the two native conformance-test headers; correct the stale
  `[[fingerprint-tofu-deferred]]` memory (native fingerprint now exists). **Done when:** notes reflect the
  shipped state.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | KEM + asymWrap + identity bundle | Status |
|---|---|---|
| contracts (vectors) | `kem_v1` / `asym_scope_grant` / `fingerprint` | ✅ (pre-existing) |
| web | full | ✅ (pre-existing, the reference) |
| apple | full | ◻ (phase 1) |
| android | full | ◻ (phase 2) |
| windows / linux | full | ◻ (when those crypto ports land) |

## Risks / watch-items

- **Apple — `MLKEM768` deployment target.** swift-crypto ships a software ML-KEM; confirm it builds for the
  app's macOS 13 / iOS 16 target (it should, being pure-Swift, not the OS CryptoKit-gated symbol). Confirm
  the seed→key API (`MLKEM768.PrivateKey(seedRepresentation:)` or equiv.) and raw X25519 shared-secret
  extraction match noble byte-for-byte.
- **Android — BouncyCastle ML-KEM seed reconstruction.** Verify BC 1.78.1 reconstructs an `ml-kem-768`
  private key from the 64-byte `d‖z` seed and that decapsulation + the raw X25519 shared secret match the
  vector. (Main implementation risk of the CR.)
- **Determinism:** only **decap** is deterministic — the KAT + the fixed `asym_scope_grant` unwrap pin the
  bytes; encap is randomized, so it is covered only by an encap→decap round-trip (same as web).

## Definition of done

Apple + Android `V1` expose the hybrid KEM, `asymWrap`/`asymUnwrap`, the identity bundle and `fingerprint`,
**byte-identical to the vectors** (conformance suites green on both), operator-blindness untouched, and the
"ML-KEM deferred" notes removed. This unblocks the native scope/spaces slice (the personal-scope work).
