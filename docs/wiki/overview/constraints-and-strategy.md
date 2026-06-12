# Constraints & Solution Strategy

> The boundary conditions we must respect, and the fundamental approaches that shape the architecture.

## Architecture constraints

| # | Constraint | Why |
|---|---|---|
| C1 | **Self-hostable by default** on modest hardware | Core promise; managed/commercial features stay optional layers. |
| C2 | **Full E2EE everywhere** — the server is always blind | The paramount invariant ([goals](goals-stakeholders-quality.md)); drives everything ([Security Model](../crosscutting/security-model.md)). |
| C3 | **Admin-controlled reverse proxy** handles domains/TLS/root-path | Self-host flexibility on arbitrary domains. |
| C4 | **API-first, internal** (OpenAPI generates code; no third-party API) | Backend/frontend alignment; small surface. |
| C5 | Quarkus/Kotlin + MariaDB/Flyway; Vue 3/TS; native iOS | Existing baseline. |
| C6 | All repository documentation in **English** | Single language; no translation pflege. |
| C7 | Product language **Memory / Chapter**; never *Album* / *Explorer* | Terminology discipline. |
| C8 | Stable decisions captured as **ADRs** ([decisions](../decisions/README.md)) | Traceable rationale. |
| C9 | **AGPL-leaning** licensing, trademark protection, EU hosting preference | Business model direction. |

## Solution strategy

| Driver | Strategy |
|---|---|
| **Security/Privacy** (paramount) | Full E2EE everywhere; blind server; envelope encryption with per-subtree keys. See [Security Model](../crosscutting/security-model.md). |
| **Maintainability / KISS** | **Modular monolith** with clearly cut bounded contexts; extract a service **only when a quality scenario forces it** (YAGNI). |
| **Performance / Scalability (SaaS)** | Push heavy compute to the **edge** via [Agent Mode](../crosscutting/agent-mode.md); keep the server slim. |
| **Reliability / Durability** | No silent data loss; soft-delete + multi-path recovery, no escrow (residual loss is the price of confidentiality). |
| **Self-host ↔ Scalability** | **One codebase, two deployments** (Compose + Helm); granularity decided in [Deployment](deployment.md). |

## Key stances

- **E2EE everywhere** is a constraint, not a toggle — every concept is designed around a blind server.
- **Divide & conquer**: use-cases → bounded contexts → building blocks → deployment.
- **Not dogmatic**: SOLID/DRY guide structure; KISS/YAGNI are the counterweights.
- **Robustness principle**: liberal in what we accept, conservative in what we send — with strict
  validation at trust boundaries (a security caveat).
