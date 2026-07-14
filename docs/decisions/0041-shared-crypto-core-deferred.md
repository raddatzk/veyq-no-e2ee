# ADR 0041 — Shared crypto core across surfaces (deferred; trigger = native un-park)
- Status: Proposed
- Date: 2026-07-14

## Context

veyq is operator-blind E2EE with **client surfaces kept at parity** (`web` · `apple` · `android`, plus a
possible future desktop `windows`/`linux`). The **crypto engine** — key derivation, wrap/unwrap, sealing,
epoch/rotation math — is the single highest-risk thing to reimplement per language: cross-stack drift is a
*correctness + security* defect, not a style nit (audit findings **P2g**/**P3f** were literally crypto-vector
drift between stacks). One shared core in a single language, consumed by every surface, is the textbook answer —
the pattern Signal, `matrix-rust-sdk`, 1Password and Firefox `application-services` all use. **Rust reaches every
surface:** **WASM** (`wasm-bindgen`) for web, **uniffi**-generated Swift/Kotlin bindings for apple/android, native
for desktop.

The open question is not *whether* the pattern works but **when** to adopt it. The current state settles that:
**web-first V1, native deferred** (CLAUDE.md; project priority). Exactly **one** client surface is live today
(web); apple/android are parked and no desktop surface exists yet.

## Decision

Adopt a shared **Rust crypto core** — but **not now**. Defer it against an explicit trigger.

- **Trigger = the native un-park moment** — *before* apple/android crypto is (re-)implemented in Swift/Kotlin.
  That is when the second and third *live* consumers appear and the dedup math turns positive: adopting then
  means the native crypto is never hand-written twice, rather than deduplicating a 3× copy after the fact. Now =
  too early (only web is live); after native is rebuilt by hand = too late.
- **Scope, when adopted: the crypto engine (+ later CRDT/merge logic) only** — *not* UI, *not* networking /
  transport (that stays per-surface + the `contracts/` codegen), *not* platform storage (Keychain / Keystore /
  Secure-Enclave stay native). The disciplined shape is **"shared crypto core, thin native shells."**
- **It complements, not replaces, `contracts/`.** API-first codegen already gives a language-neutral shared
  *wire/DTO* layer ([ADR 0038](0038-contracts-shared-components-bundling.md)); the Rust core would cover the
  *crypto/domain* layer on top of it.
- **Until the trigger, the crypto-vectors under `contracts/` are the standing insurance** — they are the contract
  any future core (Rust or not) must satisfy, so this path stays open at **zero present cost**.

## Consequences

- **Now:** nothing to build. Keep the crypto-vectors authoritative and asserted on every live stack. Do **not**
  start a Rust core while only web is live — that pays an abstraction cost *before* the duplication exists (the
  same premature-abstraction trap avoided at code scale, here at ~100× the cost), and would make web *more*
  complex (via WASM) for **zero** dedup benefit until native returns.
- **At the trigger:** revisit; if adopted, move this ADR to `Accepted` with the concrete engine boundary +
  toolchain (`wasm-bindgen` + `uniffi`) and fold that toolchain into CI — noting that CI does **not** build the
  native surfaces at all yet ([inbox #80](../inbox.md)), which is a prerequisite.
- **Costs acknowledged:** Rust + WASM + uniffi adds build complexity to every surface and raises the contributor
  bar. Against *self-hostable by default / vendor-neutral* it is fine (Rust is OSS, no vendor lock). It is a
  deliberate foundation bet — which is exactly why it is an ADR with a trigger, not a silent slide-in.
- **Rejected — adopt now:** maximal drift-safety at the earliest date, but with only web live the core would wire
  to web alone (via WASM) at a *net complexity increase* and zero dedup gain until native returns.
- **Rejected — never (per-surface crypto forever):** guarantees recurring cross-stack drift on the
  highest-risk surface; the vectors *catch* drift but every stack still hand-maintains its own crypto.
