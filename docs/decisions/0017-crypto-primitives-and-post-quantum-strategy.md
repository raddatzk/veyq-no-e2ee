# ADR 0017 — Cryptographic primitives & post-quantum strategy

- Status: Accepted
- Date: 2026-07-01

## Context

veyq stores memories meant to live for **decades**, end-to-end encrypted, under an operator-blind server. The
choice of **which cryptographic primitives** to use and **what posture to take toward quantum computers** is a
durable, foundational decision that outlives any single feature — and it had been stated only inside the
[Security Model](../crosscutting/security-model.md), a descriptive concern doc, not recorded as a decision.

This ADR records the **strategy**. It is distinct from [ADR 0009](0009-native-crypto-per-platform-vectors.md),
which governs the **per-platform *implementation*** of an already-chosen suite via shared test vectors — the
vectors *realize* this strategy; they do not choose it. The **concrete byte contents** of a suite (e.g. `v1`)
are settled in the first crypto change-request + pinned as vectors, not here.

## Decision

1. **Standard, well-reviewed primitives only — no home-grown crypto.** Symmetric content encryption is an
   AES-256-GCM-class AEAD (already quantum-resistant at 256-bit); key wrapping is per-recipient, HPKE-style
   multi-recipient.
2. **Post-quantum from the start, by intent** — because decade-lived memories make **harvest-now-decrypt-later
   (HNDL)** a real threat. Key wrapping targets a **hybrid KEM (X25519 + ML-KEM-768)** — secure if *either*
   component holds. **Signatures** (login auth, key-directory, derivative-signing) are **not** HNDL-urgent —
   there is no harvest-now-decrypt-later for a one-time verification — so PQ urgency is deliberately asymmetric:
   **high for encryption (now)**, **low for signatures (Ed25519 now → hybrid ML-DSA later)**.
3. **Crypto-agility is non-negotiable.** Every wrapped key and encrypted artifact carries a **versioned suite
   id**. New artifacts use the current suite; old artifacts stay readable under their stored suite id — this is
   what makes adopting, and later upgrading, post-quantum crypto safe without breaking the model or forcing a
   re-encryption migration.

## Consequences

- **HNDL is mitigated for *content* from day one** — a hybrid KEM means an eventual quantum adversary who
  harvested today's ciphertext still faces X25519 *and* ML-KEM-768.
- **Signatures may stay classical now and go hybrid later** without HNDL risk — a deliberate,
  documented asymmetry that avoids paying the PQ-signature size/perf cost before it buys anything.
- **The versioned suite id decouples the format from the algorithm** — upgrades are additive, old artifacts
  never break, and no flag-day re-encryption is needed.
- **Rejected — home-grown crypto:** unreviewed constructions are a foot-gun; rejected outright.
- **Rejected — classical-only / defer PQ entirely:** rejected for *encryption*, because decade-lived memories
  make HNDL a real present threat; accepted (for now) only for *signatures*, per the asymmetry above.
- **Realized by [ADR 0009](0009-native-crypto-per-platform-vectors.md)** (vectors = the cross-platform byte
  contract) and applied by [ADR 0012](0012-content-key-hierarchy.md) (the key hierarchy this crypto protects);
  the low-urgency-PQ signature case is the auth keypair of
  [ADR 0003](0003-operator-blind-password-auth.md).

## Related

- [Security Model](../crosscutting/security-model.md) (the concern doc; now points here for the stance),
  [Account & Key Model](../features/account/enablers/en.account.key-model.md).
- [ADR 0009](0009-native-crypto-per-platform-vectors.md), [ADR 0003](0003-operator-blind-password-auth.md),
  [ADR 0012](0012-content-key-hierarchy.md).
