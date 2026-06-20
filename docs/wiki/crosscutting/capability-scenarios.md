# Capability scenarios

> Worked, plain-language walkthroughs of how a client behaves as capabilities are **added**, **deprecated**
> or **removed** on the server or the client. The model itself lives in
> [Platform Concepts → Capability discovery / Versioning & compatibility](platform-concepts.md); the
> behaviour is specified in [uc.platform.capability-negotiation](../features/platform/use-cases/uc.platform.capability-negotiation.md).

## The model in one breath
- **Version `major.build`.** `build` bumps for everything additive (new features + fixes); `major` bumps
  **only when a capability is removed**.
- **`major` is the hard gate** (one integer, compared on connect). **Capabilities are the soft layer** —
  feature-detection + deprecation warnings; they **never block**.
- A capability is **immutable**: only added or removed. A "change" is **remove old + add new** (`X → Y`).

## Cheat sheet — the `major` gate

| client vs server `major` | meaning | Web (same-origin) | Mobile (per instance) |
|---|---|---|---|
| **equal** | compatible → features resolve by capability presence | normal | normal |
| **client < server** | client behind | "New version — **reload**" (stale cache) | "**Update the app**" |
| **client > server** | server behind | (deploy race → reload) | "**Update this server**" |

`build` is **never** used for the gate. On mobile the gate **and** the capability set are evaluated **per
instance** — an outdated instance never locks the whole app.

## Deprecation timeline (soft — no `major` change until the very end)

```
 deprecate (sunsetAt set, ~4 months out)            sunset
     │                                                 │
     ├──────── ~first 2 months ────────┬─ ~last 2 months ─┤
     │   client auto-updates quietly   │  active warning  │  ── remove ⇒ major++
     │   (no nag)                      │  ("update before │
     │                                 │   <date>")       │
 active ─────────────────────────────────────────────────► removed
```
The active warning shows **only** to clients that *still use* the capability — never to those already moved
to the successor.

---

## Scenarios

*Running example:* apps + a self-hosted "home server" all start at **major 3**. Capabilities in play:
`memory.journal-live-sync`, `memory.playlists`, and `memory.public-uploader-links` (later replaced by
`memory.share-links-v2`).

### A. Adding — always safe (a `build` bump, never `major`)

**A1 — Old app, new server.**
- *Setup:* server (major 3, newer build) adds `memory.media-live-updates`; the app is an older build, still major 3, and doesn't know that capability.
- *What happens:* gate `3 == 3` ✓. The app ignores the unknown capability.
- *User sees:* nothing changes; the feature appears once the app updates.
- *Rule:* unknown capabilities are ignored; adding never bumps `major`.

**A2 — New app, old server *(the key self-host case)*.**
- *Setup:* the app auto-updated and can do live journals (`memory.journal-live-sync`); the home server is an older build without it. Both still **major 3** (adding it was a `build` bump).
- *What happens:* gate `3 == 3` ✓. The app feature-detects the capability as absent → hides "Live edit", falls back to snapshot-save.
- *User sees:* no live-edit button; everything else works — no error, no block.
- *Rule:* a missing optional capability **degrades**. Because adding never bumps `major`, the newer app stays compatible with the older server. *(If every capability were "required", the app would wrongly refuse the whole server here.)*

**A3 — Feature lights up after a server upgrade.**
- *Setup:* the operator upgrades the home server to the build that advertises `memory.journal-live-sync`.
- *What happens:* next connect, gate ✓, the capability is now `active`.
- *User sees:* the "Live edit" button appears — no app update needed.

### B. Deprecating — soft, grace, no `major` change

**B1 — Silent window.**
- *Setup:* server marks `memory.public-uploader-links` `deprecated`, `supersededBy: memory.share-links-v2`, sunset in ~4 months. Still **major 3**.
- *What happens:* a client still using uploader-links auto-updates quietly to the build that uses `share-links-v2`.
- *User sees:* nothing — no nag.
- *Rule:* deprecation never bumps `major` and never nags immediately.

**B2 — Active-warning window (only affected clients).**
- *Setup:* ~6 weeks before sunset. App **A** hasn't updated and still uses uploader-links. App **B** already moved to `share-links-v2`.
- *What happens:* A sees a **non-dismissable** "Update before <date>" (uploader-links still works for now); B sees nothing.
- *User sees:* A is warned, B isn't.
- *Rule:* warn **only** clients that still use the deprecated capability.

### C. Removing → `major`++ → the gate fires

**C1 — Removal bumps the major.**
- *Setup:* at the sunset the server removes `memory.public-uploader-links`.
- *What happens:* the server is now **major 4**. This removal is the one event that moves the `major`.

**C2 — Mobile, app behind (`client < server`).**
- *Setup:* a phone app still on **major 3** connects to the now-**major 4** server.
- *What happens:* `3 < 4` → this instance is gated.
- *User sees:* **"Update the app"** (store) — for *this* server only; other major-3 instances keep working.
- *Rule:* removal → `major` gap → directed, **per-instance** block on mobile.

**C3 — Web, same removal.**
- *Setup:* the web client is served by the server, so after the major-4 deploy the bundle is also major 4; the user has a **stale cached tab** (still major 3).
- *What happens:* `3 < 4` → mismatch = stale cache.
- *User sees:* **"New version available — reload"**; after reload it's major 4 and matches.
- *Rule:* on web a mismatch means a stale bundle → reload (not a store/server update).

**C4 — Mobile, server behind (`client > server`).**
- *Setup:* a phone app on **major 4** connects to a home server the operator hasn't upgraded yet (still **major 3**).
- *What happens:* `4 > 3` → this instance is gated.
- *User sees:* **"This server is outdated — please update it"** (directed at the operator); the same app works against a major-4 instance.
- *Rule:* client ahead → update the **server**, per instance.

### D. Multi-instance mobile — everything is per-server

**D1 — Two saved instances.**
- *Setup:* the phone app (**major 4**) has instance **A** (major 4) and **B** (major 3).
- *What happens:* A → fully usable, its capabilities (e.g. playlists) drive the UI; B → flagged "server outdated" and gated.
- *User sees:* A works normally; B shows the update-the-server prompt. The app is **not** locked.
- *Rule:* the gate **and** the capability set are evaluated **per instance**; capability state is never shared between instances.

---

## One-line recap
- **Add** (either side) → safe; `build`++; unknown ignored, missing degrades.
- **Deprecate** → soft grace; warn only still-using clients; no `major` change.
- **Remove** → `major`++; the gate fires → directed "update" (web = reload, mobile = per-instance app/server).
- **Same `major`** → always compatible; differences in features are handled softly by capability presence.

## Related
- Concept: [Platform Concepts — Capability discovery / Versioning & compatibility](platform-concepts.md)
- Use-case: [uc.platform.capability-negotiation](../features/platform/use-cases/uc.platform.capability-negotiation.md)
- Decision: [AD-23](../decisions/README.md)
