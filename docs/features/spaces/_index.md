# Feature: Spaces (scopes)

Where content lives and who may access it: the **scopes** a user holds — the shared **tenant** root, optional
**groups**, and a per-tenant **personal** space — and how each is created, listed and **opened** on the
device. **Operator-blind throughout** — the server stores only the opaque scope row (id / kind / tenant) and
the **ciphertext grant** (the scope key sealed to a member's identity); it never holds a scope key.

- **Cross-cutting:** [Scopes & Tenancy](../../crosscutting/scopes-and-tenancy.md) (scope kinds, grants =
  membership, host-based tenant resolution), [Account & Key Model](../account/enablers/en.account.key-model.md)
  (identity key opens grants), [Security Model](../../crosscutting/security-model.md) (operator-blind).
- **Decisions:** [ADR 0002](../../decisions/0002-personal-scope-per-tenant.md) (personal scope is
  tenant-bound, one per member per tenant), [ADR 0007](../../decisions/0007-membership-role-grant-relation.md)
  (membership = role + grant), [ADR 0006](../../decisions/0006-cold-tenant-scope-birth.md) (cold scope
  birth), [ADR 0009](../../decisions/0009-native-crypto-per-platform-vectors.md) (the hybrid-KEM grant),
  [ADR 0014](../../decisions/0014-group-scopes-and-governance.md) (group scopes + governance — private even
  from the tenant owner), [ADR 0015](../../decisions/0015-operator-blind-readable-urls.md) (readable URLs in
  the `#fragment`).
- **Note:** a **scope** is the unit content is keyed on (opaque `scopeId` + `kind`); a **grant** is how a
  member holds its key. The tenant's root scope is born at tenant activation
  ([cr-0010](../../../changes/cr-0010-tenant-activation.md)); this feature adds the **personal** scope and the
  general scope-identity model.

## Enablers

- ★ [en.spaces.personal-scope](enablers/en.spaces.personal-scope.md) — **built** (the per-tenant PERSONAL
  scope born + opened, client-driven & operator-blind; the keyed container the content flows fill;
  [cr-0026](../../../changes/cr-0026-personal-scope.md))
- ★ [en.spaces.group-scope](enablers/en.spaces.group-scope.md) — **refined** (a shared, **named** GROUP scope
  born by its owner + opened — confidential even from the tenant owner;
  [ADR 0014](../../decisions/0014-group-scopes-and-governance.md), building in cr-0032)

## Use-cases

- ★ [List & switch spaces](use-cases/uc.spaces.switch-spaces.md) — **built (web)** (tenant switcher +
  per-space `#fragment` switching; [cr-0031](../../../changes/cr-0031-memory-shell-polish.md))
- Create / share with a group — **in progress** (G1 `en.spaces.group-scope` in cr-0032; member-add G2,
  management G3 — [ADR 0014](../../decisions/0014-group-scopes-and-governance.md))
- Public share of a subtree — planned (per-share key; least-privilege; the sharing feature)
