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

### Per-CR scope: every feature CR spans all surfaces
From now, a feature CR carries phases for **every** surface (contracts → backend → http → web → apple →
android → windows → linux), not just web. To keep that honest each CR includes a **parity ledger** — a small
table of what has landed per surface — so no surface silently lags.

**Dev-environment reality (macOS).** This workstation can build + verify web, Apple (`swift test`/Xcode) and
Android (Gradle/JVM), plus the **non-UI** layers of Windows and Linux (C#/.NET is cross-platform; Rust builds
here or in a Linux container) — i.e. **crypto + generated client + data-layer logic + vector/http tests**. It
**cannot** build/run the **UI heads**: WinUI is Windows-only, GTK4 does not run natively on macOS. So those UI
heads are produced on **Windows/Linux machines or CI**; the parity ledger marks them pending until then. The
crypto/client/logic for all five stacks stays buildable + vector-gated here.

## Consequences

- **The multiplication cost is per-platform UI + data-layer orchestration, NOT crypto.** v1 crypto is small,
  frozen (a new suite is `v2`, additive), and **vector-gated** — cheap and low-risk per language (Android
  proved this). Don't let crypto drive the platform count; let product reach + UI/logic maintenance drive it.
- **Behavioural-drift risk** in the re-implemented orchestration (login / unlock / accept flows — *not*
  vector-pinned like crypto) is caught by the shared **`http/` acceptance + use-cases**, not by shared code.
  This is what makes "no shared code" safe — keep `http/` the cross-platform behavioural contract.
- **Each new stack adds:** a generated OpenAPI client + a v1 crypto port (vector-gated) + native UI + an
  agent head — independent of the other stacks.
- **Rejected:** a shared .NET core for Windows+Linux, and a shared Rust/FFI crypto core (ADR 0009) — both
  break "shared contracts, not shared code". The runtime-bundling question (self-contained vs NativeAOT) is a
  per-platform packaging detail, not an architecture decision.
