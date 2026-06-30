---
id: cr-0051
title: Web dev proxy forwards the real Host — many host-based tenants from one Vite instance (web, dev tooling)
status: done
satisfies: []
adrs: ["0002"]
invariants: [operator-blind-e2ee]
---

## Goal

Let a **single `npm run dev` instance serve multiple host-based tenants** locally, so the operator can work on
several workspaces in parallel by browsing different hostnames (`workspace-a.localhost`, `workspace-b.localhost`,
…) instead of juggling `VEYQ_SMOKE_HOST` per instance. The backend already resolves the active tenant from the
request `Host` (ADR 0002, [ScopesResource.kt](../backend/src/main/kotlin/com/veyq/app/features/spaces/scope/ScopesResource.kt)
— strips port, lowercases). The only thing in the way is the **dev proxy**: `vite.config.ts` runs `/api` with
`changeOrigin: true` (unless `VEYQ_SMOKE_HOST` is set), which **rewrites the upstream `Host` to the proxy target
`127.0.0.1:8080`** — so the backend sees `127.0.0.1` no matter which subdomain the browser used, collapsing all
tenants into one.

This CR flips the dev proxy to **forward the real browser `Host`** (`changeOrigin: false`), and allows
`*.localhost` (+ extra suffixes) through Vite's `allowedHosts` host-check. `VEYQ_SMOKE_HOST` stays as an explicit
override (force one fixed host, e.g. for a headless live-smoke).

**Web dev tooling only.** No contracts, backend, crypto, or product code — purely `web/vite.config.ts` + a
`web/CLAUDE.md` doc note. Dotted hostnames like `workspace-a.localhost` are **already valid tenant domains**
(the contract `domain` pattern accepts them — `localhost` is a ≥2-letter TLD), so **no validation change is
needed** for this; the earlier "relax the host validator" idea is dropped (bare `localhost`/IP/ports were only a
convenience, not required — `*.localhost` subdomains cover the parallel-workspace goal).

## Context & constraints

- **One tenant per host (decided).** The port is **not** part of tenant identity; the resolver strips it. Parallel
  local workspaces are distinguished by **hostname** (`*.localhost` subdomains, or `/etc/hosts` entries pointing
  at `127.0.0.1`), each registered as its own tenant domain — exactly the production model, just locally.
- **`changeOrigin: false` forwards the incoming `Host`.** Browsing `workspace-a.localhost:5173` → Vite forwards
  `/api` to `127.0.0.1:8080` carrying `Host: workspace-a.localhost:5173` → the backend strips the port → resolves
  tenant `workspace-a.localhost`. The canonical `127.0.0.1:5173` flow is unchanged (Host `127.0.0.1` → the seed
  tenant). The proxy never adds `X-Forwarded-Host`, so the backend uses the real `Host`.
- **`allowedHosts`.** Vite's DNS-rebinding host-check would 403 a non-default Host. Allow `*.localhost`
  (`allowedHosts: ['.localhost']` — the leading dot covers `localhost` + all subdomains); another suffix is a
  one-line edit to that array. Dev server only, bound to `127.0.0.1`.
- **macOS note:** use `*.localhost` (RFC-6761 loopback; browsers resolve it without `/etc/hosts`) **not** `*.local`
  (reserved for mDNS/Bonjour — slow/flaky on macOS).
- **Footgun to document:** with the real Host forwarded, `localhost:5173` and `127.0.0.1:5173` now resolve to
  *different* hosts (only `127.0.0.1` has the seed tenant). The canonical dev URL stays `127.0.0.1:5173`.
- **Verify:** `cd web && npm run check` (typecheck + test + build) stays green; a Vite smoke confirming the
  host-check accepts `*.localhost` and the proxy forwards the real Host (curl with a spoofed `Host` header).

## Phases

- [x] 1 dev proxy host-passthrough — in [`vite.config.ts`](../web/vite.config.ts): set the `/api` proxy to
  `changeOrigin: false` (forward the real browser Host), keep the `VEYQ_SMOKE_HOST` force-header override, and add
  `server.allowedHosts: ['.localhost']`. Document the pattern in
  `web/CLAUDE.md` (run multiple workspaces via `*.localhost`; the `127.0.0.1:5173` footgun). · Done when:
  `npm run check` green; a Vite run shows the dev server answers `Host: workspace-a.localhost` (200, not 403) and
  the `/api` proxy carries that Host upstream (the seed `127.0.0.1` flow still works).

## Definition of done

`npm run dev` serves multiple host-based tenants in parallel: browsing `workspace-a.localhost:5173` vs
`workspace-b.localhost:5173` resolves the two tenants without per-instance `VEYQ_SMOKE_HOST`; the canonical
`127.0.0.1:5173` seed flow is unchanged. `npm run check` green; the change is confined to `web/vite.config.ts` +
`web/CLAUDE.md`.

## Retrospective

Done — a 3-line `vite.config.ts` change (`changeOrigin: false` + `server.allowedHosts`) plus a `web/CLAUDE.md`
note. All three behaviours empirically verified against a running Vite + a throwaway Host-echo upstream: the
host-check accepts `*.localhost` (200) and rejects others (403); the proxy forwards the real browser Host
upstream per subdomain; `VEYQ_SMOKE_HOST` still pins a fixed host. `npm run check` green; trust-chain guards green.

**Process miss — the detour before the answer.** We spent four rounds circling "relax the host validator" (port
identity, IPv6, an ADR) before establishing the two facts that made almost all of it moot: `*.localhost` already
passes the contract regex, and the *only* real blocker was the dev proxy rewriting the Host. The user's own
"could I just use /etc/hosts hosts instead of ports?" cut through it. **Root cause:** I took the first framing
("relax validation") at face value and scoped a contract-wide CR around it, instead of first asking "what
actually stops parallel local tenants today?" — which a 2-minute check of the regex + the proxy would have
answered. **Next time:** when a fix is proposed, verify the *actual* blocker empirically (run the regex, trace
the request path) before scoping the CR around the proposed remedy — the cheapest diagnostic first, per the
working rules.

**Simplification pass (post-merge request).** The user — not a frontend dev — found the config hard to read, so
a follow-up trimmed it: the unused `VEYQ_DEV_HOSTS` env-extension (a `.split().map().filter()` spread) dropped to
a plain `allowedHosts: ['.localhost']`, and the multi-line essay comments cut to one concise line per knob. No
behaviour change for any used path (`VEYQ_SMOKE_HOST` is still honoured — the IntelliJ media-smoke run config
depends on it). **Lesson:** match the config's complexity to its audience; an unused "flexibility" knob is just
cognitive cost. Verified again after the trim (`*.localhost` 200 / others 403; `npm run check` green).

**Dropped scope, on purpose.** The host-validation relax (bare `localhost`/IP/ports) is **not** done and was
deliberately dropped: it's pure convenience now that `*.localhost` covers the goal. If it ever resurfaces, the
`legacy/backendnoe2ee` `HostValidator` is the reference. `allowedHosts: true` was rejected in favour of the
narrower `['.localhost']`-plus-`VEYQ_DEV_HOSTS` so the dev server keeps its DNS-rebinding check.
