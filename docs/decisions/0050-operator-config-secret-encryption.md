# ADR 0050 — Operator config secrets are encrypted at rest with an env-supplied KEK

- Status: Accepted
- Date: 2026-07-24

## Context

veyq stores **zero usable secrets in its database** today, and this is a stated property: an invite is a
`token_hash`, a session is a token **hash**, a share password is a **verifier + salt**, every `wrapped_*` column
is client-sealed ciphertext the operator cannot open. "A DB dump yields no usable secret" holds
([security-model](../crosscutting/security-model.md)).

Per-tenant SMTP ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md)) needs the **SMTP password stored
server-side**: the tenant owner enters it at runtime and the send path must use it — there is no way around it
for tenant-self-service transport. Storing it in the clear would be the **first usable secret ever persisted in
the veyq DB**, breaking the property above. There is **no operator-side encryption-at-rest today** — the only
crypto is the client-side content E2EE ([ADR 0012](0012-content-key-hierarchy.md)), which the server cannot use
(it holds no content key). At-rest handling of operator/PII secrets ultimately belongs to the multi-ServerOwner
/ server-scope-key work (inbox #43), which is unbuilt; this ADR pulls forward the **minimal, self-contained**
slice the mailer needs, without pre-empting #43's key hierarchy.

## Decision

**A server-held config secret that must be persisted is encrypted at rest with AES-256-GCM under a
Key-Encryption-Key supplied via the operator's environment.** Concretely:

- The KEK is a 32-byte key from env — `VEYQ_SECURITY_CONFIG_SECRET_KEY` (property `veyq.security.config-secret-key`),
  base64 — the same trust tier as the datasource and blob-store creds already there, never committed, never in
  the DB. The name is deliberately generic (not mail-specific): it seals *any* server-held config secret.
- The secret is sealed with **AES-256-GCM via standard JCA** (no new dependency, no home-grown crypto); the DB
  stores only `ciphertext + a per-record random 12-byte nonce`. The server unseals on demand (at send time).
- This is **operator-side** crypto, distinct from the client-side content E2EE ([ADR 0012](0012-content-key-hierarchy.md)):
  it protects an operator's *own* infrastructure secret, not user content, so it does **not** touch
  operator-blindness — the server was always allowed to hold its own infra creds; this only stops them sitting
  in the DB in the clear.
- **Scope: the mail password now**, and the same envelope is the pattern for any future server-held config
  secret. **Not** the full #43 (no multi-ServerOwner, no per-tenant server-scope key).
- **No KEK rotation in v1** (documented limitation; rotation = re-seal every record under a new KEK, a later
  concern). **KEK loss** ⇒ the sealed configs are unreadable and the owner re-enters the password — an accepted
  operational cost.

## Consequences

- **The property is preserved.** A DB dump *alone* still yields no usable secret — decryption needs the env KEK,
  and an attacker with the operator's env already holds the DB creds anyway. The guarantee is extended to
  operator config secrets, not surrendered.
- **veyq's first server-held key.** Small, standard, self-contained — but a real new primitive. It marks the
  exact seam **#43** will generalize (encrypting more operational PII/secrets, possibly under a per-ServerOwner
  server-scope key with rotation). This ADR is the narrow down-payment, cited from there.
- **Shared-instance trust (stated).** The Server Operator holds the KEK and can unseal a tenant's SMTP password —
  inherent to running the send path on the tenant's behalf ([ADR 0048](0048-provider-neutral-smtp-mail-transport.md));
  self-hosted, operator = tenant, moot.
- **The sealed secret never leaves sealed.** Write-only over the API (never returned or logged); a DB dump,
  backup, or subpoena of data-at-rest sees ciphertext.
- **Rejected — plaintext in the DB.** Breaks the "no usable secret in a DB dump" property for a first-of-its-kind
  operator secret.
- **Rejected — building the full #43 vault / server-scope key now.** Premature: the mailer needs *one* narrow
  secret protected, not a multi-operator key hierarchy. This ADR is deliberately smaller and cited from #43.
- **Rejected — keeping the password in env, keyed per tenant.** Cannot self-service (a tenant can't edit env at
  runtime) and does not scale to many tenants; the whole point of per-tenant settings is runtime configuration.
