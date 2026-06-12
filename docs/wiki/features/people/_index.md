# Feature: People

Manage **Participants** — named people in a tenant (usually a User, sometimes anonymous), later linked
to face clusters. Participation is **self-managed** and drives relevance and processing responsibility.

- **Touched contexts:** People, Identity & Auth (optional User link) — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (non-enumerable, viewer-scoped),
  [Agent Mode](../../crosscutting/agent-mode.md) (participation → processing responsibility),
  [Search](../../crosscutting/search.md) (face/person search, deferred).
- **Decisions:** [AD-22](../../decisions/README.md) (People context), [AD-12](../../decisions/README.md)
  (participation → responsibility), [AD-14](../../decisions/README.md) (on-device faces, deferred).

## Use-cases
- ☐ `uc.people.manage-participants` — add/remove yourself or others as a participant; name them
- ☐ `uc.people.link-user` — link a participant to a User account
- ☐ `uc.people.tag-faces` — assign on-device face clusters to a participant (deferred)
