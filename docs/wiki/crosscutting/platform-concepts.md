# Platform Concepts

> Smaller cross-cutting concepts that span features. Deep security/processing/search/collab concepts
> have their own pages in this folder.

## Authentication & sessions

- Web: short-lived opaque bearer access sessions plus an `HttpOnly` refresh-token cookie. Refresh
  **rotates** the cookie and returns a fresh access `sessionToken`; key unlock remains local.
  Logout invalidates the whole associated access/refresh token family, using either the current bearer
  token or the still-valid refresh cookie as proof of possession.
- Native: single-use **bearer** sessions.
- Rate limiting per scope (login, password reset, public endpoints, share login).
- `VeyqHttpSecurityPolicy` allows only explicitly public API paths without login; tenant/group routes
  enforce membership/role. (Baseline reference: legacy `docs/architecture.md`.)

## On-device ML — *(deferred for v1)*

Face recognition / clustering and semantic search run **on-device** (NPU), within the user's own
decryptable scope (the Apple-Photos model). Server-side ML is impossible under E2EE **and** breaks
self-hostability (GPU/NPU). Proposed v1 stance: **privacy-first, server-side ML/search out of scope**;
door open via non-E2E shared scopes. Decision: [AD-14](../decisions/README.md).

## Robustness principle

Be **liberal in what we accept, conservative in what we send** — with a security caveat: liberal
parsing is also an attack surface, so validation stays **strict at trust boundaries**.

## Internationalization

Translations live in the frontend bundle; the backend returns **stable error codes** and the client
localizes user-facing errors and labels (English / German).

## Capability discovery

Components negotiate what they can do — **three directions, one concept**. All of it is **metadata**
(not content), so it works under E2E.

1. **Server → clients.** The instance advertises which features it supports (collaboration relay,
   Agent-Mode queue, upload-ETA, push, key-transparency …) **plus its `major.build` version**. Clients
   adapt their UI and negotiate compatibility — important for **self-host**, where server versions vary.
2. **Device self-capabilities** (client-side). A client assesses its own hardware — **NPU, on-device
   LLM, WebCodecs, HW video encoders** — to decide what it can do locally.
3. **Worker → queue** (Agent Mode). A worker advertises `can-h265`, `has-NPU`, `has-LLM`, … so the
   **blind job queue matches jobs** to capable workers ([Agent Mode](agent-mode.md)).

**This is the mechanism behind every graceful-degradation rule in these docs:** no on-device LLM →
UI-filter [search](search.md) instead of natural language; no agent → synchronous processing fallback;
browser-weak → queue/sidecar; no NPU → ML deferred. Distinct vocabularies per direction (instance
features ≠ device hardware ≠ worker capabilities), one concept.

Owned server-side by the **Operations** context (the capability registry); workers declare to
**Processing**; devices self-assess client-side. (Baseline: legacy `docs/product/capabilities.md`.)

### Capability vs permission (policy)

A capability is **not** a permission — keep the two apart:

- **Capability** — *can this server build do X?* **Instance-wide**, **pre-auth** (`/api/setup`), the
  compatibility contract. Drives feature-detection + the `major` gate. An operator may disable one
  **instance-wide** (config / missing backend); clients then degrade.
- **Permission / policy** — *may **this** user do X **here**?* **Per tenant / scope / role**, **post-auth**,
  owned by the membership / role / tenant-settings layer (`ScopeAccessGuard`, roles).

Example: on the SaaS instance, tenant `veyq.cloud` should let users create sub-**groups** but **not**
tenant-wide Memories — while other tenants on the *same* instance may allow both. That is **per-tenant
authorization**, not a capability: modelling it as a capability would disable tenant-Memories for the
**whole instance**. So it lives in the policy layer; the client learns its **effective per-scope
permissions** post-auth and gates the UI on the **intersection** — a feature shows only when *(the
capability is present)* **and** *(policy permits it here)*. Slice capabilities finer only when a server
build genuinely *can't* do something — never to express who-may-do-what.

### Versioning & compatibility

The version is **`major.build`** (no SemVer minor/patch split):

- **`build`** — a running number that bumps for *everything additive*: new features (new capabilities) and
  fixes. Cosmetic / changelog only — **never** a compatibility gate.
- **`major`** — bumps **only when a capability is removed** (the one breaking event). This is the **hard gate**.

**The capability manifest — the soft feature layer.** A single **append-only** `capabilities.json` in the
repo (codegen → the backend registry + each client's constants) is the source of truth. Each entry:
`{ id, state }` with `state` ∈ `active` | `deprecated` (+ `sunsetAt`, `supersededBy`). A capability is an
**immutable token** — only ever **added** or **removed**, never changed in place; a feature "change" is
**remove old + add new** (`X → Y`). On the wire (`/api/setup`) the server advertises its `active` +
`deprecated` set **plus `major`** — removed capabilities need not be sent (the `major` already encodes that
removals happened); the canonical JSON keeps the full history (incl. removed) for humans + a CI monotonicity guard.

- *Add* (→ `build`++) → peers that don't know it ignore it; peers that miss it hide the feature. Always safe.
- *Deprecate* (no version change; `sunsetAt` default **~4 months** ahead, `supersededBy`) → still advertised
  and working; clients migrate to `Y`. Not nagged immediately: for the first ~2 months the client
  **auto-updates quietly**, only in the **last ~2 months**, if still behind, does it **actively,
  non-dismissably warn** ("update before <date>") — and only clients that *still use* it.
- *Remove* (at sunset → `major`++) → the breaking event: the feature is gone and the `major` moves.

Lifecycle: **active → deprecated (silent auto-update → active warning) → removed (`major`++).**

**The hard gate — `major`, on connect (client-side; the blind server need not know the client).** The client
compares its built-in `major` to the server's. One integer, direction from the inequality — **no min/range**
(a `major` gap means a breaking removal sits between the two builds; align rather than risk it):
- `client.major == server.major` → compatible → features then resolve by **capability presence** (soft),
- `client.major  < server.major` → **client behind** → "update the app",
- `client.major  > server.major` → **server behind** → "update the server".

Resolved *before* acting, never a silent break. Because adding never bumps `major`, a **newer** client stays
compatible with an **older** server (same `major`) and just **degrades** the features the server lacks — the
whole point for self-host, where servers lag clients.

**Same gate, but scope + remediation differ by platform:**
- **Web** is same-origin (the server serves the client bundle) → `major` matches **by construction**. A
  mismatch only means a **stale cached bundle** → remediation is **"reload"**. One instance.
- **Mobile** is store-distributed and points at **arbitrary / multiple** instances → the gate **and** the
  capability set are evaluated **per instance**. Remediation is server-specific (`<` → update the app; `>` →
  update *that* server); the block is **per instance** (an incompatible instance never locks the whole app);
  capability state is **not** cached across instances.

**Client lifecycle (auto-updating, no downgrade).** A client keeps a removed capability's code for a **grace
window — default 12 months from the removal release** — then drops it in a later client `build`. **CI reads
the manifest**, computes each capability's removal age, and **gates/forces** the drop — no human guesswork.

**Self-host SLA:** keep the server within **~12 months** of current, or current (auto-updated) clients will
refuse it (the `major` gate). SaaS skew is short (you control the server). Decision: [AD-23](../decisions/README.md).

→ Worked walkthroughs of every case (add / deprecate / remove, both gate directions, web vs mobile,
multi-instance): **[Capability scenarios](capability-scenarios.md)**.
