# Goals, Stakeholders & Quality

> Why veyq exists, who it is for, and the qualities that drive every architectural trade-off.

## What veyq is

A **self-hostable platform for curating memories** — photos, videos, a Markdown journal, maps,
tracks and music — organized as nested **Memories** and shared with clear, privacy-preserving
controls. Curated, modern, emotional: a private archive that can also be shared beautifully — **not**
a gallery, cloud drive or social network.

## Quality goals (the top drivers)

| Rank | Quality (ISO 25010) | Meaning here |
|---|---|---|
| ★ | **Security / Privacy** | The paramount invariant below; the architecture's main job is managing its tensions. |
| ★ | **Reliability / Durability** | Never silently lose irreplaceable memories; fault-tolerant. |
| ★ | **Maintainability / Evolvability** | A clean modular architecture a small team (and AI agents) can evolve. |
| (SaaS) | **Performance / Scalability** | Top-tier **only in the SaaS context**; must not complicate self-host. |

### The paramount invariant

> **A user's unshared personal space is confidential — its very private media reach no one else: not
> other users, and not the operator (incl. a self-hoster who hosts for others).**
>
> E2E is **on by default**. Only the owner may lower confidentiality through an **explicit, informed**
> action (sharing, or opt-out). The operator can **never** gain access unilaterally or silently;
> re-enabling does not undo prior exposure. If this breaks, the product loses its reason to exist —
> **trust is the product.**

veyq commits to **full E2EE everywhere**; see [Security Model](../crosscutting/security-model.md).

### Tie-breaker rule

1. **Functional correctness** is non-negotiable.
2. **Security/Privacy wins** when privacy is genuinely at stake.
3. **Maintainability / KISS moderates** — among options that satisfy 1–2, pick the simplest.
4. **Durability is sacred** where privacy is not in conflict; residual loss only as the price of confidentiality.

## Quality scenarios (seed)

| # | Quality | Scenario |
|---|---|---|
| QS-1 | Confidentiality | DB dumped → no plaintext recoverable; weak passwords not brute-forceable (Secret Key). |
| QS-2 | Durability | Device dies → restored via another device, Emergency Kit + password, or the recovery code; only with none of these is loss expected & disclosed. |
| QS-3 | Safety | Public share reported illegal → operator decrypts that share within minutes, quarantines, reports. |
| QS-4 | Performance | 200 photos uploaded → upload completes immediately; heavy transcodes finish async via workers. |
| QS-5 | Privacy | Person query → never reveals existence outside the viewer's authorized memories. |
| QS-6 | Maintainability | New feature → affected context, use-case and quality scenario identifiable from the docs. |

## Conflict map

| Conflict | Resolution |
|---|---|
| Confidentiality ↔ Safety | Resolved at the **share boundary**; private content unscannable (accepted, [risks](risks.md)). |
| Confidentiality ↔ Durability | Multi-path recovery (device / Emergency Kit / recovery code), no escrow; residual loss accepted. |
| Confidentiality ↔ Performance/Scale | [Agent Mode](../crosscutting/agent-mode.md) + client-side [search](../crosscutting/search.md). |
| Self-host simplicity ↔ SaaS scalability | One modular codebase; deployment varies. |
| Maintainability/KISS ↔ feature richness | YAGNI; modular monolith; extract on demand. |

## Stakeholders

| Stakeholder | Concerns → drives |
|---|---|
| **Product Owner** | vision, professionalization, SaaS revenue, AI velocity → Maintainability |
| **Self-hoster / Operator** | simple install, footprint, backup/upgrade; owns legal duty → Reliability, Security, Portability |
| **SaaS-Operator** (now-driver) | scaling, isolation, Trust & Safety, cost → Performance, Reliability, Security |
| **Data-protection / Compliance** | EU residency, erasure, privacy-by-design → Security |
| **Memory-Curator** (primary) | easy curation, fast upload, beauty, **no data loss**, privacy → Usability, Reliability, Security |
| **Tenant-Owner / Admin** | members, roles, branding, terms → Functional Suitability |
| **Group-Owner** | group membership → Usability |
| **Public-share Visitor** | account-free access, no social-graph leakage → Usability, Security |
| **Depicted person** (non-user) | non-enumerable, context-bound visibility, erasure → Security |
| **Mobile user** | native parity, deep links, resumable upload, on-device processing → Usability, Compatibility |
| **Developer / AI agent** | clear docs, modular code, testability, deterministic rules → Maintainability |
| **Apple / TestFlight** | review rules, privacy policy → constraint |

*Parked:* white-label. *Excluded:* third-party API integrator. Full actor model: [actors](../actors.md).
