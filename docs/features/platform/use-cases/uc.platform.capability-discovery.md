# uc.platform.capability-discovery — a client discovers what an instance can do, and refuses to break silently

- **Feature:** platform
- **Actor:** any client (web, later native), **before and after login** — the discovery surface is pre-auth.
- **Decision:** [ADR 0032](../../../decisions/0032-capability-discovery-version-gating.md).
- **Status:** refined (cr-0103 planned). Web vertical first; native is later parity.

## Goal

A self-hosted instance and its (version-drifting) clients stay **honest** with each other: a client learns the
instance's **version** and **active capability set** from the pre-auth `GET /api/setup`, **hard-stops** on an
incompatible `major`, **hides** features the instance doesn't serve, and an operator can **turn a capability off
instance-wide** — with the server enforcing it, not just the UI hiding it. No silent mid-flow breakage.

## Main flow

1. On startup (and before login), the client fetches `GET /api/setup` → `{ setupRequired, suites, instanceSalt,
   directTransport, version: { major, build }, capabilities: [{ id }] }`.
2. The client compares `server.version.major` to the **`major` it was compiled against**:
   - equal → continue;
   - `server > client` → a **blocking** "update your app" screen;
   - `server < client` → a **blocking** "update this server" screen.
   A mismatch stops **before** login — never a partial, mysterious failure later.
3. On a compatible `major`, the client keeps the `capabilities` set and exposes `hasCapability(id)`.
4. Each feature that is **optional per instance** gates its entry points on its capability: absent → the feature
   is **hidden** (graceful degradation), never invoked, never crashing.
5. When an operator has **disabled** a capability, the server both **omits** it from the manifest (so step 4
   hides it) **and rejects** the corresponding endpoint (`422 capability_disabled`) — so a stale/ignoring client
   still cannot use it.

## First consumer — the sharing roles

- The Share dialog gates its role options: `sharing.public-links` present → the viewer/contributor share is
  offered; `sharing.uploader-links` present → the **Uploader** role option is shown.
- With `sharing.uploader-links` disabled: the Uploader option is **hidden**, and `POST /api/shares` with
  `role: UPLOADER` is **rejected** `422 capability_disabled`. With `sharing.public-links` disabled: the Share
  affordance is hidden entirely and any `createShare` is rejected.

## Acceptance criteria

- **discover-manifest** — `GET /api/setup` (pre-auth, no identity) returns `version: { major, build }` and
  `capabilities: [{ id }]` (the instance's **active** set, from the append-only catalog
  `contracts/platform/capabilities.json`). The response is identical for every caller (operator-blind-safe).
- **major-hard-gate** — a client whose compiled `major` differs from `server.version.major` shows a **blocking**
  incompatibility state (direction-specific: update-app vs update-server) **before** login; an equal `major`
  proceeds. `build` never gates.
- **capability-soft-gate** — a client hides/omits a feature whose capability id is **absent** from the manifest,
  and renders normally otherwise. A missing capability degrades gracefully, never throws.
- **operator-disable-enforced** — an operator disables a capability instance-wide (config); the server then
  **both** drops it from the manifest **and** rejects the corresponding endpoint with `422 capability_disabled`.
  The manifest is a UX hint; the server is the authority.
- **capability-not-permission** — a capability is instance-wide + pre-auth; it is **not** a per-scope/role
  permission. A feature is offered only when **(capability present) AND (policy permits here)** — e.g.
  `sharing.public-links` present **and** the node is a personal-scope Memory the caller owns.
- **sharing-first-consumer** — the Share dialog gates its role options on `sharing.public-links` /
  `sharing.uploader-links`; disabling `sharing.uploader-links` hides the Uploader option **and** makes
  `createShare(role = UPLOADER)` return `422 capability_disabled`.

## Out of scope (deferred — see the feature index)

The deprecation lifecycle (`state`, `sunsetAt`, `supersededBy`), per-user "new feature" notices/acks, audience
filtering, folding `suites`/`directTransport` into the unified manifest, client device self-capabilities, and
native parity.
