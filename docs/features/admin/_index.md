# Feature: Administration & Operations

Server setup, tenant/member administration, branding & legal terms, backup/upgrade, and Trust & Safety
handling of reported shares.

- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md) (share-boundary T&S),
  [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md).
- **Note:** the procedural operator **runbook** (install, env, backup, upgrade) belongs in the repo's
  infrastructure docs, not here — this feature is the *product* surface of administration.

## Use-cases

- ★ [First-run server setup](use-cases/uc.admin.setup-server.md) — **refined**
- ★ [Create a tenant & invite its owner](use-cases/uc.admin.create-tenant.md) — **refined**
- Invite members, grant access & assign roles — planned
- Manage server users & roles — planned
- ★ [Set tenant terms (versioned)](use-cases/uc.admin.tenant-terms.md) — **built (web, cr-0114)** (ADR 0035; per-locale markdown + `terms_version`; the member accept-gate consumes the version)
- ★ [Configure per-tenant mail (SMTP + invite template)](use-cases/uc.admin.tenant-mail-settings.md) — **planned** (cr-email-smtp-transport; ADR 0048/0049/0050; per-tenant SMTP, password sealed at rest, invite templates over a closed variable set)
- Set tenant branding — planned
- Back up & upgrade the instance — planned (mostly runbook)
- Act on a reported public share — planned
