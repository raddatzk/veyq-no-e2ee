# Keys

Key management is the cross-cutting feature for keeping encrypted scopes accessible without giving the
server key material. It covers wrapped grants, unlock wraps, recovery, rotation and the future trust
layer around key-directory changes.

- **Touched contexts:** Account, Spaces, Sharing, Administration.
- **Cross-cutting:** [Security Model](../../crosscutting/security-model.md), [Account keys, devices &
  recovery](../../crosscutting/account-key-model.md), [Crypto Wire-Format Spec](../../crosscutting/crypto-spec.md).

## Use-cases

- ✅ `uc.keys.rewrap-scope-grant` — replace the caller's own wrapped grant for any scope.
- ✅ `uc.keys.upsert-unlock-wrap` — add or replace a master-key unlock wrap.
- ✅ `uc.keys.remove-unlock-method` — remove a master-key unlock wrap.
- ✅ `uc.keys.rotate-identity` — rotate the account identity key and rewrap owned grants.
- ✅ `uc.keys.rotate-scope` — rotate a scope keypair after membership revocation (implemented as
  `uc.admin.invite-members-roles` scope rotation: re-grants active members + re-wraps the content key).
- `uc.keys.verify-directory` — detect identity-key substitution via transparency/pinning.
