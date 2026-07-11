---
id: cr-0133
title: Web Content-Security-Policy baseline (audit P2e)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Give the web client a Content-Security-Policy backstop. The app holds decryption keys in memory and renders
decrypted HTML (`v-html`) with no CSP, so a single content-injection slip could exfiltrate keys/plaintext to a
foreign host. This adds a generated baseline CSP — shipped as a `<meta http-equiv>` in the built `index.html`
(no server needed) and mirrored as a `Content-Security-Policy` header on the dev server for parity. The allowed
map-provider origins are derived from the **same** config the app fetches from, so the consented maps feature
keeps working and a self-hosted deployment stays covered without hand-editing the policy. See the durable
concern doc [cc.security.csp](../docs/crosscutting/cc.security.csp.md).

## Context & constraints

- **Refined via a sparring pass** (recorded in cc.security.csp): the naive finding CSP (`default-src 'self';
  … connect-src …`) would have broken the app and half-shipped. Three realities the finding missed, verified
  against source:
  - **No prod web-serving component exists** (infra routes only the store; web is "added in a fuller
    deployment"). So a *served* header has no home yet, and `frame-ancestors` (header-only, ignored in `<meta>`)
    plus HSTS are **deferred** to that component. The `<meta>` baseline is what ships now.
  - **The object store is a deployment value**, not a frontend one — presigned URLs come from the backend and
    are same-origin (`/veyq`) in the reference deploy (ADR 0013 §4), so `connect-src 'self'` covers it; a
    split-origin store extends the policy at the serving layer.
  - **Maps are a consented third-party boundary, not a bug** ([ADR 0016](../docs/decisions/0016-geocoding-provider-neutrality.md)
    / security-model: "the operator we blind is the *veyq* operator"). The CSP must *allow* the configured
    tile/geocoder, not fight the feature — so the policy is generated from the same `VITE_VEYQ_MAP_*` /
    `mapDefaults` config the client reads.
- **What the app actually needs** (verified by a surface scan): `script-src 'self'` with no `unsafe-*` (no
  inline/eval/WASM — Argon2id is pure-JS `@noble`); `style-src 'unsafe-inline'` (PrimeVue/@primeuix runtime
  `<style>` + `:style`); `img-src data: blob:` + `media-src blob:` (favicon/QR data URLs, decrypted-media object
  URLs); `worker-src 'self'` (module worker); `object-src`/`frame-src 'none'` (no embeds); `connect-src`
  self + `api.pwnedpasswords.com` (HIBP k-anonymity) + the geocoder origin.
- **One source of truth for map origins:** extracted the reference-provider defaults into
  `web/src/shared/state/mapDefaults.ts`, imported by both `useMapConfig` (runtime) and `vite.config` (policy) —
  so the CSP can't drift from the provider it is meant to allow.
- **Known limitation (documented):** the per-device localStorage tile/geocoder override to an *arbitrary* host
  is constrained to origins the operator's policy allows — a static policy can't anticipate a runtime host.
  Default + env-configured providers work fully.
- **Invariant at risk — operator-blind:** unchanged. The CSP is browser-side and orthogonal; it does not send
  anything new to the operator and keeps the map third-party boundary exactly as ADR 0016 defined it.
- **Verify:** `cd web && npm run check` + a live boot under the policy.

## Phases

- [x] 1 web — `mapDefaults.ts` extraction; `csp.ts` (`buildCsp`/`providerOrigin`) + `csp.test.ts`; `vite.config`
  generates the policy and injects `<meta>` + dev header. · **Done:** `npm run check` green (lint + vue-tsc +
  574 tests incl. 8 new CSP pins + build); the built `dist/index.html` carries the strict `<meta>` CSP (no
  `ws:`, no `frame-ancestors`); live boot on `veyq.localhost:8089` renders fully with **zero CSP violations**,
  HMR connected, styles/primeicons intact.

## Definition of done

`npm run check` green; the built `index.html` carries the generated `<meta>` CSP; the app boots under it with no
CSP violations; the concern is captured in `docs/crosscutting/cc.security.csp.md`; trust-chain guards green. No
`satisfies:` (a horizontal mechanism, not a use-case) → no coverage delta.

## Retrospective

- **Planning: I over-rotated twice before the source corrected me.** First I framed the CSP as needing a
  "fail-closed, maps break" trade-off and flagged the OSM default as a fresh privacy finding — both wrong: the
  map third-party boundary is a *decided, consented* posture (ADR 0016/0018, security-model). I only caught it
  by grepping the security model when the user pushed back that "maps should just work." Root cause: I reasoned
  about the privacy model from first principles instead of reading the existing decision first. **Next time:**
  before proposing a security posture, grep `docs/crosscutting/security-model.md` + `docs/decisions/` for an
  existing stance — the sparring value is in *surfacing* the decided boundary, not re-litigating it.
- **Good:** generating the policy from the app's own provider config (not a hand-kept string) is the right
  shape — it directly honours the CR-README "don't duplicate a canonical source, point at it," and made the
  self-hosted-maps case work for free. Pinning the policy with a test and live-booting under it (not just
  trusting the build) caught nothing broken but is the proof the harden actually holds.
- **Scope discipline:** shipped the `<meta>` baseline that closes the stated gap now and deferred the
  header-only bits honestly to the (not-yet-existent) prod web server, rather than dragging an infra component
  into a web CR.
