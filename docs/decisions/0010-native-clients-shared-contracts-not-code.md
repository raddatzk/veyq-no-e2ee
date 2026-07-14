# ADR 0010 — Native platform clients: shared contracts, not shared code

- Status: Accepted
- Date: 2026-06-26

## Context

veyq targets many client surfaces — web, Apple (macOS/iOS/iPadOS), Android, Windows, Linux — plus headless
**agents** per OS. The recurring temptation is a shared cross-platform code core (a .NET core spanning
Windows+Linux, or a shared Rust/FFI crypto core). We need a governing rule for *how* the surfaces relate, and
*which* platforms/architectures we commit to.

## Decision

**No shared client *code* across platforms. Parity is guaranteed by shared *contracts*.** Each surface is
implemented in its platform-idiomatic stack, independently. This generalises [ADR 0009](0009-native-crypto-per-platform-vectors.md)
(crypto per-platform, vectors as the contract) to the whole client. The shared contracts are:

- the **OpenAPI specs** (`contracts/`) → a generated client per language (never hand-written DTOs),
- the **crypto conformance vectors** (`contracts/crypto/vectors/`, ADR 0009),
- the **`http/` acceptance suites + use-cases** as the cross-platform *behavioural* contract.

### Platform / language matrix

| Surface | Stack | Notes |
|---|---|---|
| Apple — macOS = iOS = iPadOS | **Swift / SwiftUI** | one universal app |
| Apple — tvOS | **Swift / SwiftUI** | shared SPM libs (crypto/client/logic) build for tvOS from day one; the tvOS UI head is a later increment — TV sign-in is **passkey / QR device-handoff**, not password + Secret Key on a remote |
| Android | **Kotlin / Jetpack Compose** | `:crypto` is plain JVM (also the JVM agent) |
| Windows | **C#/.NET + WinUI 3** | BouncyCastle.NET crypto; runtime self-contained (or NativeAOT later) |
| Linux | **Rust + gtk4-rs/relm4 + libadwaita** | pure-Rust crypto crates; lean static binary, no runtime |
| Web | Vue / TS | existing |

### Headless agents
Each OS agent is the **same native stack with the UI head removed** — reusing that stack's crypto + data
layer — integrated where the OS expects: macOS `NSStatusItem`, Windows tray, Linux `StatusNotifierItem`. The
Linux agent is additionally containerisable (Docker).

### Cross-desktop on Linux
One toolkit (GTK4/libadwaita — GNOME-first, runs on every DE), with integration via **XDG Desktop Portals /
freedesktop standards** (file chooser, notifications, autostart, **Secret Service** for the device-trust key,
StatusNotifierItem, MPRIS) so behaviour is native per desktop environment; **Flatpak** is the primary,
cross-distro, sandboxed distribution. **Caveat:** GNOME removed the legacy system tray — `StatusNotifierItem`
needs the AppIndicator extension there, so the agent's status indicator must plan for that (extension, or a
notifications/quick-settings fallback).

### Architectures
Windows and Linux must support **arm64 and amd64**. **32-bit (x86 / armhf) is excluded.** (Apple = arm64;
Android = arm64-v8a primary.)

### Per-CR scope: the commitment is here, the schedule is ADR 0043's
Every surface in the matrix above is a **commitment**: it is built against the same contracts, and none may
silently diverge in *behaviour*. That fixes *how* the surfaces relate — it does **not** fix the order in which
they are built, or which of them a given feature CR carries phases for. **That schedule is
[ADR 0043](0043-web-first-v1-native-parked.md)'s:** web-first, `apple`/`android` parked until an explicit
un-park, so a feature CR's phases are **contracts → backend → web** and a parked surface's absence is not a gap
in the CR.

**The parity ledger** — a per-CR table of what has landed per surface — **stands down while surfaces are
parked**: rows permanently pending for two of four surfaces measure nothing. It returns when they do. Until
then the standing record of what is built where is
[`platform-parity.md`](../crosscutting/platform-parity.md) plus the per-use-case `gaps:` in
[`docs/coverage/`](../coverage/README.md).

**Dev-environment reality (macOS).** This workstation can build + verify web, Apple (`swift test`/Xcode) and
Android (Gradle/JVM), plus the **non-UI** layers of Windows and Linux (C#/.NET is cross-platform; Rust builds
here or in a Linux container) — i.e. **crypto + generated client + data-layer logic + vector/http tests**. It
**cannot** build/run the **UI heads**: WinUI is Windows-only, GTK4 does not run natively on macOS. So those UI
heads are produced on **Windows/Linux machines or CI** when those surfaces arrive. The crypto/client/logic for
all five stacks stays buildable + vector-gated here.

## Consequences

- **The multiplication cost is per-platform UI + data-layer orchestration, NOT crypto.** v1 crypto is small,
  frozen (a new suite is `v2`, additive), and **vector-gated** — cheap and low-risk per language (Android
  proved this). Don't let crypto drive the platform count; let product reach + UI/logic maintenance drive it.
- **Behavioural-drift risk** in the re-implemented orchestration (login / unlock / accept flows — *not*
  vector-pinned like crypto) is caught by the shared **`http/` acceptance + use-cases**, not by shared code.
  This is what makes "no shared code" safe — keep `http/` the cross-platform behavioural contract.
- **Each new stack adds:** a generated OpenAPI client + a v1 crypto port (vector-gated) + native UI + an
  agent head — independent of the other stacks.
- **Rejected:** a shared .NET core for Windows+Linux, and a shared Rust/FFI crypto core
  ([ADR 0009](0009-native-crypto-per-platform-vectors.md)) — both break "shared contracts, not shared code".
  The runtime-bundling question (self-contained vs NativeAOT) is a per-platform packaging detail, not an
  architecture decision.
  **The Rust half of this rejection is under live challenge:** [ADR 0041](0041-shared-crypto-core-deferred.md)
  (`Proposed`) argues for a shared Rust crypto core — the crypto *engine* only, thin native shells — deferred
  until the native un-park ([ADR 0043](0043-web-first-v1-native-parked.md)). It does **not** supersede this
  clause and nothing here is withdrawn: 0041 is a proposal, and a proposal cannot supersede an accepted
  decision. But whoever revisits 0041 revisits this clause, and the .NET half is untouched by it.
