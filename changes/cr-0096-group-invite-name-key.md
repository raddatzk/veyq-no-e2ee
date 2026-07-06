---
id: cr-0096
title: Group invite name via a derived name key — replace the one-off owner seal (verifiable invitee name)
status: done               # planned → in-progress → done (kept as a record)
satisfies: [uc.spaces.consent-group-add]
adrs: ["0027", "0014", "0024", "0009", "0026"]
invariants: [operator-blind-e2ee]
---

## Goal

Replace cr-0094's **one-off owner name-seal** — an unverifiable, owner-typed name a modified owner client can make
diverge from the group's real name — with a **name key `K_name` derived from the name's content-epoch key**, so a
pending invitee reads the **canonical** `encryptedName` the members read and the owner cannot privately show a
different name ([ADR 0027](../docs/decisions/0027-group-invite-name-shared-key.md)). `K_name_e = hkdfSha256(E_e,
salt, "group-name-key", 32)`, where `E_e` is the epoch the name was set under; the scope row carries a small
**name-epoch index** (like every node name, ADR 0024 §2), so the group name is **forward-secret** consistently with
the tree. The name seals under `K_name_e`; the owner hands the invitee `asymWrap(inviteePub, K_name_e,
"group-name-key")` (the derived key bytes) and the server returns the scope's `encryptedName` in the invitation.
Clean break — pre-production, no data migration. **A rename endpoint is out of scope** (a later slice); this lands
the name at epoch 0 from birth (FS-ready) + the invite-name change.

## Context & constraints

- Durable truth: [ADR 0027](../docs/decisions/0027-group-invite-name-shared-key.md) (the decision + rejected
  alternatives), [uc.spaces.consent-group-add](../docs/features/spaces/use-cases/uc.spaces.consent-group-add.md)
  (acceptance `one-off-name-seal` is rewritten to the derived-key model), [ADR 0014](../docs/decisions/0014-group-scopes-and-governance.md)
  §5 (the name-seal paragraph is updated), [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (`E₀` = the
  immutable chain root the key derives from), [ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md)
  (the new contexts are vector-pinned), [ADR 0026](../docs/decisions/0026-client-trust-tiers.md) (the binding is
  crypto-grade on a trusted client, honest-operator on served web — label the tier honestly, don't over-claim).
- Invariant at risk: **operator-blind.** The name stays ciphertext under a client-only key; the server never sees
  `K_name`, `E₀`, or the plaintext name. The one new read is `encryptedName` (already-stored ciphertext) surfaced
  to the pending invitee — bounded, no key.
- Builds on cr-0094 (the consent flow, accept/decline/skip) and cr-0095 (the rotation gate) — both unchanged here;
  this swaps only the *name-preview* mechanism, not the consent gating.
- **Superseded:** cr-0094's `group-name` name-seal, `sealedInviteName` (a name ciphertext), and `inviteName.ts`'s
  name-sealing are removed. Native (apple/android) stays a documented gap (group-add is web-first).

## Phases

- [x] 1 contracts — crypto: pin the **`K_name` derivation** (`hkdfSha256(E_e, salt, "group-name-key", 32)`) + the
  `asymWrap(K_name, "group-name-key")` wrap + the name's `symEncrypt` context as **conformance vector cases** (keep
  native byte-identical). API: `ScopeSummary` gains a **name-epoch index** alongside `encryptedName`; rename
  `AddGroupMemberRequest.sealedInviteName` → **`sealedNameKey`** (the wrapped `K_name`); `GroupInvitation` drops
  `sealedInviteName`, gains **`sealedNameKey`** + **`encryptedName`** (the scope's canonical sealed name, served to
  the pending invitee — no epoch index needed, it gets the key bytes). · Done when: `openApiGenerateSpecs` + web
  `generate:api` clean.
- [x] 2 backend — add a **name-epoch index** column on the scope (default 0 at birth); repurpose the membership
  `sealed_invite_name` column to hold the wrapped `K_name` (append-only Flyway; `added_by` kept). `addGroupMember`
  stores `sealedNameKey`; `listMyScopes`/directory return the name-epoch index; `listInvitations` returns
  `sealedNameKey` + the scope's `encryptedName`. No dual-shape (clean break). Slice tests updated (the proposal
  carries a name key, the invitee's invitation carries the name key + `encryptedName`; the consent gate + rotation
  gate from cr-0094/95 are unchanged and stay green). · Done when: `./gradlew test` green.
- [x] 3 web — `createGroup` seals `encryptedName` under `K_name_0` (derived from the birth `E₀`, name-epoch 0); the
  member name-open path derives `K_name_e` from the name's epoch key (peel the chain to `E_e`) instead of decrypting
  under `K_SC`; the owner add-flow derives `K_name_e` for the current name epoch and wraps it to the invitee
  (replacing `sealGroupNameToInvitee`); the invitee's invitations view unwraps `sealedNameKey` → `K_name` →
  decrypts `encryptedName` → the name. Remove the old one-off seal path. · Done when: `npm run check` green (incl. a
  rewritten `inviteName.test.ts`: name-key wrap round-trip + domain separation + the divergence property — an
  invitee reads the *same* `encryptedName` as a member).
- [x] 4 coverage & docs — rewrite `uc.spaces.consent-group-add` acceptance `one-off-name-seal` → the derived-key
  model (invitee reads the canonical name); update the ADR 0014 §5 name-seal paragraph to point at ADR 0027; flip
  the coverage refs. · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` green.

## Definition of done

A pending invitee sees the **group-consistent** name (the same `encryptedName` the members hold, opened via a
`K_name` derived from the name's epoch key) — the owner cannot privately show a divergent name. The group name is
forward-secret, consistent with node names. The one-off owner name-seal is gone.
Operator-blind preserved (no key, no plaintext to the server; only the already-stored `encryptedName` ciphertext is
surfaced to the invitee). Vectors keep native byte-identical (native parity itself deferred). Guards green.
**Out of scope:** the fingerprint/TOFU authenticity seam (stays deferred, ADR 0014 §5); native group-add parity.

## Retrospective

Landed as scoped: the one-off owner name-seal is gone, replaced by a name-key `K_name` derived from the name's
epoch key. A pending invitee is handed the wrapped `K_name` + the scope's canonical `encryptedName` and reads the
**same** name the members hold — closing the modified-owner private-name-lie (ADR 0027). The group name is now
forward-secret via a `name_epoch` index, consistent with node names.

- **Vectors:** three cases pinned — `group_name_key` (HKDF derivation), `wrap.sym_group_name` (the name under
  `K_name`, node-name context — also the first pinned node-name symWrap), `wrap.asym_group_name_key`
  (`asymWrap(K_name, "group-name-key")`, superseding cr-0094's `asym_group_name`). HKDF `info` =
  `veyq/v1/group-name-key`, empty salt (matching slug/dedup). Conformance green; native reads them byte-identical
  when group-add parity lands (still deferred, no native refs to the old vector so the rename was safe).
- **Migration:** V28 (append-only) — `veyq_scope.name_epoch INT DEFAULT 0` + `RENAME COLUMN sealed_invite_name TO
  sealed_name_key` (H2 2.x ∩ MariaDB common subset, V15/V18 precedent). Clean break, no data migration.
- **Scope widened for soundness beyond the phase-4 list:** `en.spaces.group-scope` and `_index.md` also described
  the name as sealed under `K_SC` directly — updated to the derived-key model so no future native implementer
  seals under the wrong key.
- **Context reuse:** the name seals with the existing `node-name` symWrap context (a group name *is* a name); the
  domain separation lives in the key derivation + the `group-name-key` asym context. No new seal primitive.
- **Deferred, unchanged:** a rename endpoint (the `name_epoch` bump the index enables) and the fingerprint/TOFU
  authenticity seam — both explicitly out of scope; native group-add parity stays a documented gap.
- **Guards:** conformance (23) + web `npm run check` (455 pass) + `./gradlew test` + coverage/changes/doc-links
  validators all green.
