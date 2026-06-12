# Risks & Technical Debt

> Stated honestly. Full E2EE everywhere is a bold, opinionated choice; these are the costs we
> consciously accept.

## Risks of the E2EE-everywhere architecture

| # | Risk | Mitigation / stance |
|---|---|---|
| R1 | **Private content is unscannable** (CSAM/illegal). | Deliberate. Trust & Safety works at the **share boundary** (decrypt-on-report). Before SaaS: legal counsel, shared-content T&S process, clear ToS, reporting integration. Self-host: operator owns it. |
| R2 | **Processing liveness** — derivatives can stay pending if no worker is online. | [Agent Mode](../crosscutting/agent-mode.md) is critical infra; synchronous **fallback** on the uploading client; pending states + nudges. |
| R3 | **Key/recovery loss = permanent data loss**, universal. | Three recovery paths (device / Emergency-Kit (Secret Key) + password / single-use recovery code); enforce backup at setup; clear disclosure. No escrow (by design). |
| R4 | **Active malicious operator** could key-substitute before Key Transparency ships. | TOFU/pinning now; Key Transparency core later. Honest disclosure; moot for self-host. |
| R5 | **Key-management complexity** grows with pervasive sharing. | Per-subtree keys; convergent key wrapped under rotatable scope key. Test heavily — E2E-with-sharing is historically hard. |
| R6 | **Browser is a weak processing client**. | Native apps as strong workers; deployed agents; WebCodecs where available; degrade gracefully. |
| R7 | **Storage cost** from reduced dedup in unshared scopes. | Source-dedup per user; convergent dedup in shared scopes. |

## Technical debt (inherited "vibe-coded" baseline)

| # | Debt | Plan |
|---|---|---|
| D1 | **Flat permission model** (legacy ADR 0005: anyone with access can delete). | Superseded by the deletion-privilege model (see [Security Model](../crosscutting/security-model.md)). |
| D2 | Docs split between legacy `docs/` and the new `docs/wiki/`. | Legacy is reference; migrate and retire section-by-section. |
| D3 | No real full-stack `e2e.browser` coverage yet. | Tracked in the feature catalog; address per use-case during hardening. |
| D4 | Current system is not yet E2EE. | Target-architecture migration; code follows the docs, use-case by use-case. |
| D5 | The current `collaboration/` **Hocuspocus** sidecar is content-aware (holds/merges plaintext) — incompatible with E2E. | Replace with a thin **blind encrypted-update relay** (AD-19); Yjs CRDT moves fully client-side. |
