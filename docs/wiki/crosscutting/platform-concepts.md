# Platform Concepts

> Smaller cross-cutting concepts that span features. Deep security/processing/search/collab concepts
> have their own pages in this folder.

## Authentication & sessions

- Web: `HttpOnly` access/refresh token cookies; refresh **rotates** tokens; logout invalidates.
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
   Agent-Mode queue, passkeys, upload-ETA, push, key-transparency …) **plus its version / protocol & E2E
   levels**. Clients adapt their UI and negotiate compatibility — important for **self-host**, where
   server versions vary.
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

### Versioning & compatibility

Capabilities are a **versioned contract** — a shared **manifest** with one entry per capability
(`addedIn`, `deprecatedIn`, `sunsetAt`, `removedIn`, `supersededBy`, + dates). SemVer applies:

- **Adding** a capability = **minor** (old clients ignore unknown capabilities).
- **Removing or changing** one = **major** (the server bumps its major version).
- **Replacing** (`X → Y`): advertise **both** during the overlap; clients prefer `Y`, fall back to `X`.

**Server-side deprecation — no abrupt drops.** A capability is never simply removed: it is first
**marked for removal** (`sunsetAt`, default **~4 months** ahead) and keeps being **advertised and
working**. Clients see the flag and migrate to `supersededBy` — but the user is **not** nagged
immediately: for the first ~2 months the client **auto-updates quietly in the background**, and only in
the **last ~2 months** before the sunset, if still behind, does it **actively warn the user** ("update
before <date>"). At the sunset it is removed (major). Lifecycle:
**active → deprecated (silent auto-update → active warning) → removed**.

**Client lifecycle (auto-updating, no downgrade).** A client keeps a removed capability's code for a
**grace window — default 12 months from the removal release** — then drops it in a **client major**.
**CI reads the manifest**, computes each capability's removal age, and **gates/forces** the client drop —
no human guesswork.

**Enforcement (on connect, via discovery).** The mismatch is detected *before* acting, never a silent
break: a client that dropped a capability the server still requires → graceful **"server too old —
please update it"**; the reverse (server needs a newer client) → **"please update the app"**.

**Self-host SLA:** keep the server within **~12 months** of current, or current (auto-updated) clients
will refuse it. SaaS skew is short (you control the server). Decision: [AD-23](../decisions/README.md).
