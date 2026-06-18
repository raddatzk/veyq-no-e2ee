# UC: Set tenant branding & legal terms

- **ID:** uc.admin.branding-terms
- **Primary actor:** Tenant Owner
- **Secondary actor:** Public Viewer
- **Goal:** Maintain tenant-wide **legal terms** (and branding) that guests must accept before viewing
  or contributing through any of the tenant's public shares.
- **Preconditions:** Authenticated as the tenant's **Owner** (ACTIVE membership).
- **Postconditions (success):** The tenant's terms Markdown and a **version** are stored
  (operator-visible, **not** E2EE); guests see the current terms on every share and must accept the
  current version before proceeding.

## Two notice layers
Tenant-wide **terms** (here) and per-share **notices** (set on an individual share) coexist. Both are
Markdown, **versioned independently**; guest acceptance is recorded only in the **browser** — there is
no account to bind it to server-side.

## Main flow
1. The owner reads the current terms (`GET /api/tenants/{tenantId}/terms`) → Markdown + version
   (initially empty / `0`).
2. The owner edits the terms (a **template builder** offers standard clauses) and saves
   (`PUT …/terms`).
3. The server checks **Owner** role, trims/caps the input (≤ 20,000 chars) and — **if the content
   changed** — bumps the version.
4. On the next guest visit to any share of this tenant, the public share state includes the terms as a
   **TENANT notice** with the current version.
5. The guest **accepts**; the browser records acceptance keyed by share / scope / version; a version
   bump forces **re-acceptance**.

## Alternate / exception flows
- A1: **Empty / whitespace** terms → stored as **cleared** (no terms).
- A2: Re-saving **identical** content → **no** version bump.
- E1: A **non-Owner** (member / removed / guest) tries to set terms → `403`.
- E2: An unauthenticated or expired session → `401`.

## Quality notes
- *Operator-visible by design:* unlike Memory content, terms are **plaintext** branding/policy the
  server must serve to anonymous guests — note this differs from the E2EE content model.
- *No server-side acceptance log:* acceptance is **local** to the guest browser (guests have no
  account); **versioning** drives re-consent.
- *Scope:* this UC covers the implemented **terms** surface; richer branding (logo/colors) and per-locale
  terms are future refinements.

## Related
- Feature: [Administration & Operations](../_index.md) · Actors: [Tenant Owner](../../../actors.md),
  [Public Viewer](../../../actors.md)
- Concepts: [Security Model → share-boundary](../../../crosscutting/security-model.md),
  [Platform Concepts](../../../crosscutting/platform-concepts.md)
- Decisions: ADR 0012 (share notices & tenant terms)
- Related use-cases: `uc.sharing.share-public-viewer`, `uc.admin.invite-members-roles`
