# Feature: Administration & Operations

Server setup, tenant/member administration, branding & terms, backup/upgrade, and Trust & Safety
handling of reported shares.

- **Touched contexts:** Operations, Identity & Access, Spaces — see [System Architecture](../../overview/architecture.md).
- **Cross-cutting:** [Security Model → share-boundary T&S](../../crosscutting/security-model.md),
  [Platform Concepts](../../crosscutting/platform-concepts.md).
- **Decisions:** [AD-05](../../decisions/README.md) (T&S), [AD-16](../../decisions/README.md) (internal API).
- **Note:** the procedural operator **runbook** (install, env, backup, upgrade) lives in the repo
  `docs/`, not in this wiki — see [Deployment](../../overview/deployment.md).

## Use-cases
- ★ [First-run server setup](use-cases/uc.admin.setup-server.md)
- ★ [Create a tenant and invite its owner](use-cases/uc.admin.create-tenant-invite-owner.md)
- ★ [Invite members, grant access & assign roles](use-cases/uc.admin.invite-members-roles.md) — incl. remove & deactivate
- ☐ `uc.admin.branding-terms` — tenant branding and legal terms
- ☐ `uc.admin.backup-upgrade` — back up and upgrade
- ☐ `uc.admin.takedown-reported-share` — act on a reported public share
