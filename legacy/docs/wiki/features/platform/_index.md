# Feature: Platform

Cross-cutting **engineering-trust** behavior that protects every feature — abuse / rate-limiting and the
web client's boot-and-route shell. These are shared technical guarantees, **not** product capabilities
(**`capabilities: []`**): they have no Memory-level user goal of their own, but every other feature
depends on them.

- **Touched contexts:** all (shared infrastructure) — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Platform Concepts](../../crosscutting/platform-concepts.md) (auth, robustness,
  capability discovery), [Security Model](../../crosscutting/security-model.md) (abuse resistance at the
  blind server).
- **Status:** engineering-trust — tests here protect shared technical infrastructure and map to no
  product acceptance criterion.

## Use-cases
- ★ [Rate-limit abusive traffic](use-cases/uc.platform.rate-limiting.md)
- ★ [Boot the web app shell](use-cases/uc.platform.app-shell.md)
- ★ [Negotiate client/server capability compatibility](use-cases/uc.platform.capability-negotiation.md)
- ★ [Seed local smoke data](use-cases/uc.platform.local-smoke.md)
