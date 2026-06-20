# UC: Negotiate client/server capability compatibility

- **ID:** uc.platform.capability-negotiation
- **Primary actor:** Memory Curator
- **Goal:** On connecting to an instance, discover what the server can do and which `major` it is on,
  **enable only the features the server supports**, and — when client and server are on different majors —
  surface a clear, directed "update" path instead of a silent break. Adding abilities must never break older
  peers; removing one must never break abruptly.
- **Preconditions:** A client connects to an instance. Self-host means versions vary: the client may be
  newer than the server (apps auto-update via the stores ahead of operator upgrades) or older. A mobile
  client may point at several instances.
- **Postconditions (success):** The client has resolved the instance's `major` + capability manifest;
  supported features are active, unsupported ones are hidden (graceful degradation); a `major` mismatch
  produces a clear, directed "update" message — never a blank screen or a failed action.

## Main flow
1. On connect (during boot / discovery), the client fetches the instance's **manifest** — the server's
   **`major`**, its `active` + `deprecated` **capabilities** (`{ id, state }`), and the cosmetic
   **`major.build`** version. Metadata only (no content), so it works under E2E.
2. **Hard gate (`major`).** The client compares its built-in `major` to the server's. **Equal** ⇒ continue.
   **Different** ⇒ a directed **"update"** stop (E1/E2) — one integer, no min/range. The **`build` number is
   never used here**.
3. **Soft layer (capabilities).** For each `active` capability the client also knows, it **enables** that
   feature; capabilities the server does **not** advertise are simply **hidden** (graceful degradation).
   This **never blocks** — adding is additive (a `build` bump); a missing capability just means one fewer feature.
4. The client proceeds into the normal account/space flows with the **negotiated feature set**.

## Alternate / exception flows
- A1: **Capability deprecated (soft, no `major` change).** A capability the client still uses carries a
  `sunsetAt`. The client first **auto-updates quietly**; only in the final window before the sunset, if
  still behind, does it **actively, non-dismissably warn** ("update before <date>"). Clients already on the
  successor are **not** nagged.
- A2: **Replacement (X → Y) — the shape of *every* breaking change.** A capability is immutable (never
  changed in place), so a behavior change is "remove `X` + add `Y`". The server advertises **both** during
  the overlap (client prefers `Y`); removing `X` at its sunset is the event that **bumps `major`**.
- E1: **Client behind.** `client.major < server.major` → "update the app" (web: **reload**; mobile: **store update**).
- E2: **Server behind.** `client.major > server.major` → "update the server" (mobile: that *instance*).

## Quality notes
- *Engineering-trust / cross-cutting (`capabilities: []`):* the negotiation **every** feature's
  graceful-degradation rule depends on; it has no Memory-level user goal of its own.
- *`major.build`:* `major` is the **hard gate**, bumped **only by a capability removal**; `build` is
  cosmetic (additive features + fixes). Adding never bumps `major`, so a newer client stays compatible with
  an older same-major server and just degrades the missing features.
- *Soft layer:* capabilities **never block** — add = safe, missing = hidden, deprecate = warn-then-hide. The
  manifest is an append-only `capabilities.json` (codegen → backend + clients); the wire manifest sends
  `active` + `deprecated` + `major` (removed caps need not be sent).
- *Web vs mobile:* web is same-origin → `major` matches by construction, a mismatch = stale cache →
  **reload**, one instance. Mobile is multi-instance → gate **and** capability set are **per instance**,
  remediation is server-specific, and an incompatible instance never locks the whole app.
- *Operator-blind:* the manifest carries only IDs / states / version — never content.

## Related
- Feature: [Platform](../_index.md) · Actors: [Memory Curator](../../../actors.md)
- Concepts: [Platform Concepts](../../../crosscutting/platform-concepts.md) — *Capability discovery* +
  *Versioning & compatibility*. Walkthroughs: [Capability scenarios](../../../crosscutting/capability-scenarios.md).
- Decision: [AD-23](../../../decisions/README.md)
- Related use-cases: `uc.platform.app-shell`, `uc.admin.setup-server`
