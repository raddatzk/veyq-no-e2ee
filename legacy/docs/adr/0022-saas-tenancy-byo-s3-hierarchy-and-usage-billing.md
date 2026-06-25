# ADR 0022: SaaS Tenancy — Storage Ownership, Subtenant Hierarchy, and Usage Billing

## Status

Proposed (design spike for task #21). The storage-ownership question is settled; the hierarchy and billing
dimensions are a recommended direction + open questions (not yet implemented).

## Context

veyq today has a flat tenancy model: a **Tenant** owns exactly one **Scope** (`TENANT_ROOT`, `uq_scope_tenant`),
each user has a `PERSONAL_ROOT` scope, and access = an active membership **plus** a scope grant (the content key
wrapped to the member's identity, ADR 0005). All content is sealed under a per-scope content key; the server is
operator-blind. Blobs live in an S3-compatible store (ADR 0021); the server sees blob `byteSize` in plaintext
but never the content.

We want three SaaS capabilities:

1. **A storage-ownership model** that works for both deployments.
2. **A tenant/group hierarchy** — a primary platform tenant (veyq.app / veyq.cloud), customer groups
   ("affenbande"), and subgroups beneath them.
3. **Usage-based billing** — meter the primary group and the individual personal space by usage.

## Decision 1 — The backend owns the store credentials (no per-user BYO-S3 zero-knowledge)

There are exactly two deployment scenarios, and in BOTH the object store belongs to the same party that runs the
backend — so the backend legitimately holds the S3 credentials:

- **Self-hosted:** the operator runs the backend AND provides the S3 (external managed S3, or the bundled MinIO).
  The operator trusts their own backend, so the credentials live in the backend's env/secret store. (ADR 0021.)
- **veyq.cloud (SaaS):** the bytes live in the **SaaS provider's own bucket**; the SaaS backend owns those
  credentials. End users do not bring their own S3.

So an earlier idea — every end user/tenant supplying their own S3, with the backend forbidden from knowing the
creds (operator-blind, client-held sealed creds) — is **dropped**: it solved a problem we don't have. The backend
holding the store credentials is acceptable because it is always the store's rightful owner. Presigned
direct-to-store (ADR 0021) therefore applies in both scenarios unchanged.

The content is E2EE regardless: the store only ever holds AEAD ciphertext, so "the backend/operator holds the S3
creds" does NOT mean it can read media — it cannot decrypt without the per-scope content key, which it never has.

**Multi-tenant isolation within one SaaS bucket:** namespace object keys per scope (e.g. prefix `scopeId/…`) so
a tenant's objects are enumerable/deletable/meterable as a unit and a tenant could later be migrated to its own
bucket. (Today keys are `uploadId/index`; adding the scope prefix is a small, additive change.)

## Decision 2 (recommended) — Hierarchy = nested tenants for admin + billing, NOT implicit content access

- Add an optional `parent_tenant_id` to `veyq_tenant`: the primary platform tenant is the root; customer groups
  hang under it; subgroups under those. A tree.
- **Each group keeps its own scope + content key** (isolation preserved). The hierarchy does **not** grant a
  parent implicit read access to a child's content — that would break E2EE/operator-blind. A parent admin can
  *manage* (create/rename/remove subgroups, invite, see usage) but reads child content only via an explicit
  grant (the existing invite/membership path). Cross-level content moves stay a re-encryption op
  ([[cross-space-memory-move]]).
- Roles compose down the tree: a group Owner administers its subtree; membership/grants stay per scope.

Open questions: nesting depth cap; whether subgroups share the parent's bucket (likely yes, scope-prefixed) or
get their own; whether domain routing (one domain per tenant, V21) extends to subgroups (subdomains).

## Decision 3 (recommended) — Usage billing on operator-hosted bytes, rolled up to a billing root

- **Measurable operator-blind:** storage = Σ `byteSize` of the blobs a scope owns (server-visible); egress =
  already counted by the rate limiter (`share-egress-bytes-per-min`); object/media counts. Content stays
  encrypted — metering needs only sizes/counts, so it does **not** weaken E2EE.
- **Billing root:** each tree has a billing owner (the primary group; the individual for a personal space). A
  group's billed usage = its own scope + Σ descendant scopes' usage (rolled up over `parent_tenant_id`).
- Implementation sketch: a periodic meter job sums `byteSize` per scope (cheap SQL) into a usage table keyed by
  billing root + period; egress accumulates from the existing limiter counters. No content access required.

Open questions: metering period/granularity; proration around scope rotation (re-sealed content briefly doubles
bytes); the payment integration itself (out of scope here).

## Consequences

- Storage is simple: one operator-owned store per deployment, backend-held credentials, presigned transfers
  (ADR 0021). No client-held S3 creds, no per-tenant credential vault, no zero-knowledge storage layer to build.
- `parent_tenant_id` is an additive migration; the flat model is the degenerate single-level tree, so existing
  tenants are unaffected.
- Billing reuses already-visible signals (sizes, egress counters) — no new content exposure.
- **Sequencing:** task #7 (presigned uploads, operator/default store) is **unblocked** by this decision and can
  proceed. The scope-prefixed object keys, the `parent_tenant_id` hierarchy migration, and the metering job are
  each their own implementation slices once this spike is accepted.
