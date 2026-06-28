---
id: cr-0027
title: The Memory tree — encrypted folder/Memory nodes (names, blinded slugs, per-Memory K_mem)
status: planned          # planned → in-progress → done (the file is kept as a record)
satisfies: [en.memory.tree]   # an enabler — the foundation the Memory use-cases build on
adrs: [0012, 0009, 0002, 0007]
invariants: [operator-blind]
---

## Goal

Build the **first content slice**: the encrypted **node tree** inside a scope — **FOLDER** (organization
only), **MEMORY** (the content unit, born with a per-Memory key `K_mem`), **CHAPTER** (inside a Memory) — with
**encrypted names**, **blinded slug tokens** for unique-per-parent permalinks, create / list / rename / move,
all operator-blind. First content lives in the **personal scope**
([cr-0026](cr-0026-personal-scope.md)). **Out of scope** (their own later slices): media (`K_blob` + chunked
AEAD), journal, places/tracks, sharing, rich curation, and the UI head (deferred to the shell — this CR is
the data layer + backend).

## Context & constraints

- Enabler: [en.memory.tree](../docs/features/memory/enablers/en.memory.tree.md); decision:
  [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) (`K_SC` → `K_mem` (random, many-wrapped) →
  `K_blob`; folders keyless). Navigation contract: [scopes-and-tenancy](../docs/crosscutting/scopes-and-tenancy.md).
- **`K_SC` already exists** — it is the **scope key from [cr-0026](cr-0026-personal-scope.md)** (a random
  symmetric key the member unwraps from their grant). Names/slug/dedup key off it; `K_mem` is wrapped under it
  (`symWrap(K_SC, K_mem)`). No new scope-level key material.
- **Crypto reuse — almost nothing new.** Node-name seal + `K_mem` wrap are `symWrap` with new context strings
  (`"node-name"`, `"content-key"`) — the same vector-pinned primitive, no new vector. The **`slug_token`**
  vector already exists; it just isn't ported natively yet (cr-0025 did KEM/identity, not the content side),
  so apple/android gain **`slugToken` only** (vector-gated). No backend/contract crypto change.
- **Operator-blind (invariant):** the server stores ciphertext names + opaque `parentId`/`kind`/`orderIndex`/
  `slugToken` + the `K_mem` wrap; it never sees a name or a content key. Guards: scope-access (caller holds a
  grant), `UNIQUE(parentId, slugToken)`, structural rules (no folder-in-Memory, no cycle), no cross-scope move.

## Phases

- [ ] **1 — contracts.** New `contracts/api/content.openapi.yaml`: nodes under a scope —
  `POST /api/scopes/{scopeId}/nodes` (create: `{parentId?, kind, encryptedName, slugToken, orderIndex,
  wrappedContentKey?}` → `{nodeId}`; 409 `slug_taken`; 422 `illegal_structure`), `GET /api/scopes/{scopeId}/nodes`
  (the tree: opaque rows), `PATCH …/nodes/{nodeId}` (rename = new encryptedName+slugToken; move = new
  parentId+slugToken; reorder = orderIndex), `DELETE …/nodes/{nodeId}`. Base64-validated opaque fields,
  `@Authenticated`. **Done when:** spec valid, `$refs` resolve.
- [ ] **2 — native crypto.** Port **`slug_token`** to apple `VeyqCrypto` + android `:crypto` (vector-gated;
  `symWrap`/`symUnwrap` are already native). **Done when:** `swift test` + `:crypto:test` green incl. the
  `slug_token` vector on both.
- [ ] **3 — backend.** `V10` `veyq_node` (id, scope_id, parent_id?, kind, encrypted_name, slug_token,
  order_index, wrapped_content_key?, created_at) + `UNIQUE(parent_id, slug_token)`; `content/node`
  feature (Resource/Handler/Store) under a scope-access guard (caller has a membership for the scope);
  structural rules (no folder-in-Memory, no cycle on move, no cross-scope move). **Done when:** `./gradlew
  check` green incl. a `NodesSliceTest` (create→list, slug-collision 409, illegal-structure 422, rename/move
  recompute slug, non-member 404, operator-blind: only ciphertext stored).
- [ ] **4 — web.** Data layer in `features/memory/` (the generated content client + `tree.ts`): create folder/
  Memory (encrypt name + slug under `K_SC`; Memory births `K_mem` wrapped under `K_SC`), list+decrypt the
  tree, rename/move. **Done when:** `npm run check` green + a unit round-trip (create→list→decrypt name;
  Memory `K_mem` wraps/opens) with real v1 crypto + mocked API.
- [ ] **5 — apple.** The same in `VeyqClient` (+ generated `ContentAPI` module). **Done when:** `swift test`
  green (create→list→decrypt round-trip) + iOS build.
- [ ] **6 — android.** The same in `:client` (`"content"` added to the generator specs). **Done when:**
  `:client:test` green (same round-trip) + `:app:assembleDebug`.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Memory tree: create/list/rename/move (operator-blind) | Status |
|---|---|---|
| contracts | `content.openapi.yaml` (nodes under a scope) | ◻ |
| crypto (native) | `slug_token` ported apple + android | ◻ |
| backend | `veyq_node` + guards + structural rules | ◻ |
| web | data layer (encrypt names + slug + `K_mem`) | ◻ |
| apple / android | data layer (+ generated content client) | ◻ |
| UI (all) | the tree UI | ◻ **deferred to the shell** |
| windows / linux | — | ◻ (when those clients exist) |

## Definition of done

A member can create + organize an encrypted Memory tree (FOLDER/MEMORY/CHAPTER) in their personal scope on
web·apple·android: names encrypted under `K_SC`, unique-per-parent blinded slug tokens, each Memory born with
a random `K_mem` wrapped under `K_SC` (ready to be shared later, ADR 0012), rename/move recompute the slug,
structural rules + scope-access enforced server-side, operator-blind throughout, all suites green. **Media,
journal, places, sharing and the tree UI are follow-on slices.** Then decide the add-media slice (`K_blob` +
chunked AEAD + the unified media transport).
