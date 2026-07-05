---
id: cr-0089
title: Scope-key rotation on the web — remove a member + client-side epoch rotation (G3a cont.)
status: planned
satisfies: [uc.spaces.manage-group-members]
adrs: ["0024", "0014", "0012"]
invariants: [operator-blind-e2ee]
---

## Goal

Wire the **client-side scope-key rotation** into the web so **removing a group member** actually delivers
forward-confidentiality: the removing owner's device mints a new content-key epoch, re-grants it to the remaining
members, and from then on new content seals under it — the removed member reads pre-removal content but **never**
post-removal content, even against a malicious operator ([ADR 0024](../docs/decisions/0024-scope-key-rotation.md)).
The remove/rotation **backend + contracts** landed in [cr-0088](cr-0088-group-member-management.md); this CR is the
web half, so the user-facing "remove" **ships with** its rotation (the refinement decision). It touches the web
**content-crypto layer** (epoch-aware scope open + tree seal), which is why it is its own slice.

## Context & constraints

- Durable truth: [uc.spaces.manage-group-members](../docs/features/spaces/use-cases/uc.spaces.manage-group-members.md),
  [en.spaces.scope-key-rotation](../docs/features/spaces/enablers/en.spaces.scope-key-rotation.md),
  [ADR 0024](../docs/decisions/0024-scope-key-rotation.md) (epoch chain; read first),
  [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) (the content-key hierarchy rotation touches).
- Reuses the cr-0088 backend: `removeMember`, `rotateScope` (POST `/epochs`), `listScopeEpochs`, the
  `currentEpoch`/`grantedEpoch` on `ScopeSummary`, the node `epoch`; the directory pubkeys (cr-0086); the tree
  crypto (`sealName`/`openName`/`slugToken`/`K_mem` wraps, cr-0026/0060). **No new crypto primitive** — the epoch
  chain composes `symWrap`/`asymWrap`.
- Invariants at risk:
  - **Forward-confidentiality is the point** — new content must seal under `E_current`; a bug that seals under an
    old epoch, or fails to re-grant a remaining member, breaks the guarantee. **Read after rotation must stay
    correct** — a member must decrypt every node by its own epoch key (mixed epochs across the tree).
  - **Operator-blind** — the mint + all wraps are client-side; only opaque ciphertext (chain-link + grants) is
    posted.

## Phases

- [ ] 1 web crypto — an **epoch-key set** abstraction: `openScope`/`openScopeById` open the grant → `E_current`,
  fetch the chain-links (`listScopeEpochs`), and peel down to `{E_0 … E_current}`; expose it on `activeSpace`
  (the write key = `E_current`, a per-epoch lookup for reads). · Done when: `npm run check` green + unit tests for
  the chain-peel (a held latest epoch derives all earlier; an old epoch derives none).
- [ ] 2 web tree — thread the epoch through `tree.ts`: **create** seals name/slug/`K_mem` under `E_current` and
  tags `epoch = currentEpoch`; **list/read** opens each node under `E_{node.epoch}`; `nodeContentKey` resolves a
  top-level Memory's `K_mem` under its node's epoch. Favorites seal under `E_0` (stable). · Done when: `npm run
  check` green; a tree spanning two epochs decrypts fully for a current member.
- [ ] 3 web remove + rotate — a **remove** action in `GroupMembersPanel` (owner-only) that: `removeMember`, then
  **rotates** — mint `E_{n+1}`, `symWrap(E_{n+1}, E_n)`, `asymWrap(E_{n+1})` to every remaining member's directory
  pubkey, POST `rotateScope`. · Done when: `npm run check` green + live (**two accounts**): owner removes a member
  → the ex-member keeps old Memories but a **new** Memory is unreadable to them (DB: new node epoch + the ex-member
  holds no current grant); a remaining member reads old + new seamlessly.
- [ ] 4 coverage — move the web/live refs of `remove-member` / `remove-rotates-forward` / `operator-blind-rotation`
  in `uc.spaces.manage-group-members.yaml` off the cr-0088 gap onto the new web tests. · Done when: the three
  trust-chain guards green.

## Definition of done

Removing a group member on the web deletes their membership **and** rotates the scope key forward; a two-account
live smoke shows the ex-member cannot read post-removal content while remaining members read old + new seamlessly;
operator-blind (only opaque ciphertext posted). Coverage + guards green. **Out of scope:** the async stale-epoch
grant-delivery backstop (the commit re-grants all remaining members synchronously); native parity.
