---
id: cr-0089
title: Scope-key rotation on the web — remove a member + client-side epoch rotation (G3a cont.)
status: done               # planned → in-progress → done (kept as a record)
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

- [x] 1 web crypto — an **epoch-key set** abstraction (`epochs.ts`): `openScope`/`openScopeById` open the grant →
  `E_current`, fetch the chain-links (`listScopeEpochs`), and peel down to `{E_0 … E_current}`; exposed on
  `activeSpace` (`activeEpochKeys()`, the write key = `E_current`, a per-epoch lookup for reads). · Done: `npm run
  check` green + `epochs.test.ts` chain-peel tests (a held latest epoch derives all earlier; an old epoch derives none).
- [x] 2 web tree — threaded the epoch through `tree.ts`: **create** seals name/slug/`K_mem` under `E_current` and
  tags `epoch = currentEpoch`; **list/read** opens each node under `E_{node.epoch}`; `nodeContentKey` resolves a
  top-level Memory's `K_mem` under its node's epoch (deeper chapters chain through `K_mem`, epoch-independent).
  `sortNodesByDate` took a per-node key resolver; browse/detail/spaceMap/covers thread `epochs`. Favorites seal
  under `E_0` (stable). · Done: `npm run check` green (a tree spanning epochs decrypts each node under its own epoch).
- [x] 3 web remove + rotate — a **remove** action in `GroupMembersPanel` (owner-only, inline confirm, self
  excluded) that: `removeGroupMember`, lists the survivors, then `rotateGroupForRemoval` — mint `E_{n+1}`,
  `symWrap(E_{n+1}, E_n)`, `asymWrap(E_{n+1})` to every remaining member's directory pubkey, POST `rotateScope`;
  `refreshActiveScope()` re-opens the active scope so the owner's next content seals under `E_{n+1}`. · Done:
  `npm run check` green + `scopes.test.ts::rotateGroupForRemoval` (mint + chain-link + per-member re-grant, all
  ciphertext) + a **live smoke on the real stack** (see Retrospective): three real rotations through the backend
  (epochs 1→2→3, a chain-link + owner re-grant each), content created at each epoch (`veyq_node.epoch` = the
  scope's current epoch), and a **deep multi-hop cross-epoch read** — at scope epoch 3 the client peeled
  `E_3→E_2→E_1→E_0` and decrypted every node under its own epoch, in the rendered UI. The live run **caught a real
  reactivity bug** (fixed, below).
- [x] 4 coverage — moved the web refs of `remove-member` / `remove-rotates-forward` / `operator-blind-rotation`
  in `uc.spaces.manage-group-members.yaml` onto the new web tests (`members`/`scopes`/`epochs`). · Done: the three
  trust-chain guards green (LIVE smoke tracked in the DoD, verified interactively).

## Definition of done

Removing a group member on the web deletes their membership **and** rotates the scope key forward; new content
seals under the new epoch while a remaining member reads old + new seamlessly; operator-blind (only opaque
ciphertext posted). Coverage + guards green. **Out of scope:** the async stale-epoch grant-delivery backstop (the
commit re-grants all remaining members synchronously); native parity.

## Retrospective

- **Execution — the live smoke earned its keep: it caught a real, security-relevant bug the unit tests couldn't.**
  I drove three real rotations through the running backend (Quarkus + MariaDB + Garage) and read the tree back in
  the browser. The client crypto was flawless — `openScopeById` peeled a depth-3 chain (`E_3→E_2→E_1→E_0`) and
  `listTree` decrypted every node under its own epoch. But creating content *right after a rotation while the browse
  view stayed mounted* sealed it under a **zeroed key** (`aes/gcm: invalid ghash tag` on the next read). Root cause:
  `activeEpochKeys()` returns key bytes held **outside** Vue reactivity, so the view's `epochs` computed (keyed only
  on `activeSpace.status`, which a rotation doesn't flip) kept a **cached reference to the old epoch-key object** —
  which `refreshActiveScope` had just **zeroed in place** before swapping. Fix: a reactive `activeSpace.epoch`
  mirror that `refreshActiveScope`/`enterScope` bump and the `epochs` computed now depends on, so it re-reads the
  live keys after a rotation. Added a store-level regression test (`activeSpace.test.ts`). Lesson: when key material
  is deliberately kept out of reactivity, any view that *reads* it needs an explicit reactive signal to re-read —
  and zeroing a buffer another live consumer still references is a footgun; the reactive swap must precede any use.
- **Method note (why a single principal, not two accounts).** The running dev instance had one seeded user; minting
  a second required the full tenant-invite → registration → grant-delivery onboarding (cr-0077/0086, already
  separately verified) plus repeated same-origin account switches — high cost that re-tests *other* CRs, not this
  one. So the live smoke exercised the cr-0089-specific risk directly: the real rotation round-trip, epoch tagging,
  and the deep cross-epoch read, with the sole owner as the "remaining member" (rotation re-grants the survivors —
  here, kevin). The removed-member half is covered by `removeGroupMember` (unit) + the backend
  `removeMemberThenLastOwnerGuard` slice test + the forward-secrecy math in `rotateGroupForRemoval`/`epochs.test.ts`
  (each epoch key is independent; the chain only unwraps downward). A future two-account native/e2e pass can close
  the last mile.
- **Process — well.** Backend + client crypto were unit/slice-green before the live run; the live run was the
  integration proof and the bug-finder; the fix landed with a regression test and `npm run check` green.
