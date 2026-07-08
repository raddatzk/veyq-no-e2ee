# ADR 0032 — Capability discovery & version gating (lean core)

- Status: Accepted
- Date: 2026-07-08

## Context

veyq is **self-hostable by default**: an operator runs their own backend, and users reach it with clients
(web served by that backend, plus native apps) whose **versions drift** from the server's. Two problems follow
that a REST API alone doesn't solve:

1. **Compatibility.** A client built against a newer/older server can call an endpoint that no longer exists, or
   miss one it relies on — a silent break. We want an **honest, explicit** gate: *"this app and this server are
   incompatible — update one"* — not a mystery 404 mid-flow.
2. **Feature discovery + operator control.** Not every instance runs every feature. An operator may **turn a
   feature off instance-wide** (policy, footprint, legal). A client must **discover** what the instance actually
   supports and **hide** what it doesn't — gracefully, never crashing on a missing feature.

The previous implementation had a **thin** server-advertised `CapabilityRegistry` (a static list at
`/api/client-capabilities`, used mostly for a settings display + "new feature" notices) and a **much richer,
never-built** target-picture design (a `major.build` hard gate + an append-only capability manifest + a
deprecation lifecycle). The fresh state already has the **seams**: the pre-auth `GET /api/setup` response
advertises `suites: ["v1"]` (crypto-suite negotiation the owner-registration picks from) and `directTransport`
(a runtime-detected capability the client degrades on) — but there is **no version field and no generic
capability manifest**. Worker↔queue capability tags (`CapabilityTags`, ADR 0021) already realize the
operator-blind "worker advertises what it can do" direction.

This ADR decides the **lean core** of a client-facing capability-negotiation layer. The full lifecycle
(deprecation windows, per-user "new feature" notices, audience filtering) is **explicitly deferred**.

## Decision

Make the existing pre-auth **`GET /api/setup`** the **capability-discovery surface**. Add two things to its
response and a client-side gate that reads them.

### 1. `version: { major, build }` — the hard compatibility gate

- **`major`** is the **only** hard gate. It is incremented **exclusively when a capability is removed** (a
  breaking removal of an ability a client might rely on). Everything additive — a new capability, a new field, a
  new endpoint — bumps **`build`**, never `major`.
- Each client is **compiled with the `major` it was built against** (`expectedMajor`). On reaching `/api/setup`
  it compares:
  - `server.major == client.expectedMajor` → **compatible**, proceed.
  - `server.major > client.expectedMajor` → the client is **too old** → a **blocking** "update your app" state.
  - `server.major < client.expectedMajor` → the **server** is too old → a **blocking** "update this server" state.
  - Any mismatch is a hard stop **before** login — no silent partial operation.
- `build` is informational (diagnostics, "what's new"); it is **never** a gate.

### 2. `capabilities: [{ id }]` — the active capability manifest

- The instance advertises the **active** capabilities it serves, as stable lowercase-dotted ids
  (`sharing.public-links`, `sharing.uploader-links`, …).
- **Source of truth** is an **append-only** catalog `contracts/platform/capabilities.json`, codegen'd into a
  backend registry **and** each client's constants — so ids can never silently diverge across surfaces.
- **Lean core = active set only.** The `state` field (`active | deprecated`), `sunsetAt`/`supersededBy`, and the
  deprecation lifecycle are **deferred** — the wire shape is `[{ id }]` now, extensible to `[{ id, state, … }]`
  later without a `major` bump (additive → `build`).

### 3. Capability ≠ permission (the load-bearing distinction)

- A **capability** is **instance-wide** and **pre-auth**: *"this server knows how to do X at all."* It is read
  from `/api/setup` before login.
- A **permission** is **per-tenant / per-scope / per-role** and **post-auth**: *"you may do X here."* (E.g. the
  personal-scope gate on creating a public share.)
- A feature is offered only when **(capability present) AND (policy permits here)**. The capability manifest
  never carries per-user data — it stays operator-blind-safe (pre-auth, no identity, same for everyone).

### 4. Turning a capability off — advertised **and** enforced

An operator disables a capability **instance-wide** via config (a `veyq.capabilities.disabled` list, or a
per-feature flag). A disabled capability is then:

- **Omitted from the manifest** → every client **hides** the feature (soft, graceful — the ShareDialog simply
  doesn't offer the role).
- **Enforced at its endpoint** → the manifest is a UX hint, but the **authority is the server**. The endpoint
  that would exercise the capability rejects with `422 capability_disabled`. A client that ignores the manifest
  (or is stale) still cannot use a disabled capability.

Both are required: the manifest alone is only UX; the endpoint gate alone gives a broken-looking UI. Together
they are honest and safe.

### 5. First consumer — the sharing roles

The lean core ships with the **sharing** feature as its first gated consumer, two catalog entries:

- **`sharing.public-links`** — the public **viewer + contributor** share ([ADR 0029](0029-public-viewer-share.md),
  [ADR 0030](0030-contributor-public-share.md)). Absent → the Share affordance is hidden entirely.
- **`sharing.uploader-links`** — the **uploader** drop-box ([ADR 0031](0031-uploader-public-share.md)). Absent →
  the **Uploader** role option is hidden **and** `createShare(role = UPLOADER)` is rejected `422
  capability_disabled`.

This proves the whole loop end-to-end (manifest → client gate → server enforcement) on a real feature an
operator has a plausible reason to disable.

## Consequences

- **Self-hosted version drift becomes explicit, not a silent break.** The `major` gate turns "why is this
  button doing nothing" into "your app and this server are incompatible — update one."
- **Operators get a real off-switch** for optional features, enforced server-side, hidden client-side — without
  per-feature bespoke plumbing (one registry, one gate).
- **`/api/setup` grows into the pre-auth manifest** it was already becoming (`suites` + `directTransport` were
  the precedent). Those two existing signals **stay as-is** for now; folding them into the unified `capabilities`
  list is an additive follow-up, not part of this lean core.
- **Deferred (each a later slice, none blocking this):** the **deprecation lifecycle** (`state`, `sunsetAt`,
  `supersededBy`, the ~4-month sunset + non-dismissable warning); **per-user "new feature" notices/acks**;
  **audience filtering** (`ALL_USERS` vs `SERVER_OWNER`); **client device self-capabilities** (NPU/WebCodecs,
  a separate direction); migrating `suites`/`directTransport` into the unified manifest; **native parity**.
- **Not a permission system.** This ADR deliberately does not touch per-scope/role authorization — it only adds
  the instance-wide discovery + version layer above it. Conflating the two is the main modeling risk, called out
  in §3.

## Alternatives considered

- **A min/max version range instead of a single `major`.** Rejected — ranges invite "works but degraded"
  ambiguity; a single removal-only `major` keeps the gate binary and cheap to reason about (a capability that
  still exists is `build`-additive; one that's gone is a `major` bump).
- **Semantic per-endpoint versioning (Accept-Version headers, /v2/).** Rejected for now — heavier, and it
  answers "which shape" not "does this instance do X at all"; the capability manifest answers the discovery
  question directly and the `major` gate covers breaking removals.
- **Client-only feature flags (build-time).** Rejected — can't reflect a *specific instance's* operator choices;
  discovery must be **server-advertised** to be per-instance.
