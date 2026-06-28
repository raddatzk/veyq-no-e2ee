# ADR 0012 — Content-key hierarchy: per-Memory keys, random and many-wrapped

- Status: Accepted
- Date: 2026-06-28

## Context

[ADR 0002](0002-personal-scope-per-tenant.md) + [cr-0026](../../changes/cr-0026-personal-scope.md) settled
the **scope** layer (opaque `scopeId` + kind; a member holds a scope via a grant = the scope key sealed to
their identity). What lives *inside* a scope — folders, Memories, chapters, media, journal — needs its own
key model. The [Security Model](../crosscutting/security-model.md) sketches **per-subtree content keys** and
"a share = one subtree content key wrapped to a per-share key"; the crypto vectors pin a `contentKey`-based
`slug_token` / `chunk_frame` / `dedup_token` / `journal_open`. The open question (inbox #46) is the **exact
granularity and wrapping** — and specifically: *how do you share a single Memory without exposing the rest
of the scope?* This ADR nails it. (Considered and rejected during refinement: **one content key per scope** —
sharing one Memory would hand out the whole-scope key, leaving the confidentiality of the *other* Memories to
**server gating, not crypto**; and **a full per-subtree key tree** — maximal flexibility but a deep key
hierarchy + re-wrap-on-move complexity the product doesn't need.)

## Decision

**Content keys are per Memory — random, and wrapped to many recipients.** Three layers under the scope:

1. **`K_SC` — scope content key (one per scope).** Obtained by a member via their grant (the scope key
   unwraps it). It is the **navigation root**: node **names** (folder / Memory / chapter titles), paths and
   the **blinded slug tokens** (`slug_token`) and per-scope **dedup tokens** key off `K_SC`, so a member who
   holds the scope decrypts the **whole tree's structure + names with one key** — without touching any
   Memory's content.
2. **`K_mem` — per-Memory content key (one per Memory, a fresh random 32-byte key).** Wrapped under `K_SC`
   for members. Encrypts that Memory's **content** — chapter bodies, journal, sensitive metadata
   (capture time, location) — and **wraps that Memory's `K_blob`s**. A Memory is the **unit of sharing**.
3. **`K_blob` — per-blob key (one per media blob, random).** Wrapped under its Memory's `K_mem`; encrypts the
   media blob's `chunk_frame`s. Lets a Memory's media be keyed independently (and dedup tokens stay per-scope).

**Folders carry no key** — they are pure organization. A folder's *name* is navigation metadata under `K_SC`
like any node; it has no content key.

**`K_mem` is RANDOM, not derived from `K_SC` — so it is "one key, many wraps"** (the same pattern as the
Master Key, [ADR 0005](0005-unlock-methods-and-device-trust.md)). This is the crux that makes per-Memory
sharing **cryptographically** enforced:

- **Members:** `K_mem` is wrapped under `K_SC` (`symWrap`). Every scope member opens it.
- **Sharing one Memory adds an *independent second wrap* of that same `K_mem`** — never `K_SC`:
  - **Public link share:** `symWrap(K_share, K_mem)` where `K_share` is a fresh per-share key whose secret
    rides the **URL fragment** (never the server); the shared Memory's name travels in the share record too,
    re-sealed to `K_share`. A guest opens **only** that Memory.
  - **Member share** (give one person ongoing access to one Memory): `asymWrap(recipientIdentityPub, K_mem)`
    — a grant at the Memory grain.
- Because `K_mem` is random, handing a recipient `K_mem` reveals **neither `K_SC`** (so not the rest of the
  scope) **nor any other Memory's `K_mem`** (each is wrapped under `K_SC`). Least-privilege is **crypto-
  enforced**, not server-gated.

**Rotation / revocation.** Removing a share record is instant (server-side, no keys). The eventual crypto
defense is **rotating the affected key**: a single Memory by re-sealing its content under a fresh `K_mem`
(re-wrapped to members); the whole scope by rotating the scope keypair + `K_SC` (re-wrap to remaining
members). Already-read content cannot be retracted — a stated limitation.

**Operator-blind, unchanged.** The server stores only ciphertext content + the opaque wraps (`K_mem`-under-
`K_SC`, share wraps), node ids/parents/kinds/ordering/timestamps, and the blinded slug/dedup tokens. It never
holds `K_SC`, `K_mem`, `K_blob`, or any name.

## Consequences

- **Per-Memory sharing works without exposing the scope** (the #46 answer): a share is one extra wrap of the
  Memory's `K_mem`; `K_SC` stays in the owner-circle.
- **Cheap navigation, scoped content.** One `K_SC` decrypts the whole tree's names/structure; a Memory's
  content needs its `K_mem`. A guest of a shared Memory sees that Memory's name + content and nothing else.
- **`cross-space-memory-move`** ([memory](../../changes/) note): moving a Memory across scopes is a
  copy + re-encrypt (new scope's `K_SC`/`K_mem`), never a tree move — consistent with [scopes & tenancy](../crosscutting/scopes-and-tenancy.md).
- **Per-Memory rotation is possible** precisely because `K_mem` is random + wrapped (a derived key could not
  be rotated without rotating `K_SC`).
- **Cost:** one wrap (`K_mem`-under-`K_SC`) per Memory + one per `K_blob`; a share adds one wrap per share.
  Negligible vs. content size.
- **Deferred to the implementation CR / vectors:** the exact wire formats (already partly pinned —
  `slug_token`/`chunk_frame`/`dedup_token`/`journal_open` in `contracts/crypto/vectors/v1.json`), whether a
  shared Memory's *sub*tree (its chapters) shares the one `K_mem` (yes — a chapter is part of its Memory) and
  the share-record shape. Group/recipient key-directory trust is the active-operator gap already in the
  Security Model.

## Related

- [Security Model](../crosscutting/security-model.md) (the key-hierarchy diagram + sharing/T&S),
  [Scopes & Tenancy](../crosscutting/scopes-and-tenancy.md) (scope keys, grants, navigation contract),
  [Account & Key Model](../crosscutting/account-key-model.md).
- [ADR 0002](0002-personal-scope-per-tenant.md), [ADR 0005](0005-unlock-methods-and-device-trust.md)
  (one-key-many-wraps), [ADR 0009](0009-native-crypto-per-platform-vectors.md) (vectors are the byte
  contract).
- Feature: [Memory](../features/memory/_index.md); first use-case
  [uc.memory.tree](../features/memory/use-cases/uc.memory.tree.md). Implementation: a later content CR.
