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
   version: { major, build }, capabilities: [{ id }] }`.
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

## Seed consumers

The catalog is seeded with four **operator-policy** capabilities; **sharing is the worked end-to-end proof**,
the other three ride the same machinery (advertised + `hasCapability` soft-gate + a one-line `422` at their
create/enable path), advertised active as each feature is built:

- **`sharing.public-links` / `sharing.uploader-links`** — the Share dialog gates its role options; with
  `sharing.uploader-links` disabled the Uploader option is **hidden** and `POST /api/shares { role: UPLOADER }`
  is **rejected** `422 capability_disabled`; with `sharing.public-links` disabled the Share affordance is hidden
  entirely and any `createShare` is rejected.
- **`spaces.groups`** — group-space creation/UI; disabled → the group affordance is hidden and the group-create
  endpoint rejects `422`. (An operator who wants a personal-only instance.)
- **`account.device-trust`** — the "remember this device" / passwordless-unlock levels; disabled → the toggle is
  hidden and enrolling a device wrap rejects `422`. (An operator who mandates a full password every time.)

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
- **catalog-serves-usecases** — a capability is a **coarser projection** over use-cases, not a 1:1 rename of one
  (ADR 0032 §6): each catalog entry declares a `serves: [uc.…]` back-ref (catalog-only, not on the wire), and a
  validator enforces that every `serves` resolves to a **real** use-case — so the catalog can't be a typo or drift
  from the graph. The catalog is **append-only**; an id may be reserved ahead of its first advertisement, and is
  **advertised active** only once its use-case(s) are built. (Seed: `sharing.public-links` serves viewer +
  contributor, `sharing.uploader-links` serves uploader, `spaces.groups` serves the five group use-cases,
  `account.device-trust` serves the four device-unlock use-cases — 4 capabilities over 12 use-cases.)

## Out of scope (deferred — see the feature index)

The deprecation lifecycle (`state`, `sunsetAt`, `supersededBy`), per-user "new feature" notices/acks, audience
filtering, folding `suites` into the unified manifest, client device self-capabilities, and native parity.
(`directTransport` was **removed** rather than folded in — a byte-path store is now a hard requirement, cr-0105.)
