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

- [x] **1 — contracts.** `contracts/api/content.openapi.yaml`: `createNode` / `listNodes` / `updateNode`
  (rename/move/reorder) / `deleteNode` under `/api/scopes/{scopeId}/nodes` (a separate `content` tag — no
  collision with the `/api/scopes` resource); `NodeKind` (FOLDER/MEMORY/CHAPTER); base64-validated
  `encryptedName`/`slugToken`/`wrappedContentKey`; error codes `slug_taken` (409) / `illegal_structure` (422)
  / `scope_not_found` / `node_not_found`. **Done:** YAML valid, all `$ref` resolve, operationIds unique;
  auto-picked by the backend glob + added to the web/native generators in their phases.
- [x] **2 — native crypto.** Ported **`slug_token`** to apple `VeyqCrypto` + android `:crypto`
  (`HKDF(contentKey,"",​"veyq/v1/slug-token:"+parentSlot) → HMAC-SHA256(slugKey, NFC(slug))`; symWrap/symUnwrap
  already native). **Done:** apple `V1ConformanceTests` 15/0 incl. `testSlugToken`; android `:crypto:test`
  green incl. `slugToken` — byte-identical to the vector on both.
- [x] **3 — backend.** `V10__nodes.sql` `veyq_node` (+ `UNIQUE(parent_id, slug_token)`; the **root's parent
  slot is the scope id**, so the unique works at the root too); `features/content` (`NodeEntity` /
  `NodesStore` / `NodesHandler` / `ContentResource` impl of the generated `ContentApi`) under a scope-access
  guard (caller holds a membership for the scope → else 404); structural rules (FOLDER/MEMORY at root or
  under a FOLDER, CHAPTER only inside a MEMORY, no cycle on move). **Done:** `./gradlew check` green;
  `NodesSliceTest` 6/6 (create-tree→list, slug-collision 409, chapter-at-root 422, non-member 404, unauth
  401, rename + delete-subtree; operator-blind: stored ciphertext/`wrappedContentKey` verbatim).
- [x] **4 — web.** `features/memory/tree.ts` (+ the `content` spec in `generate:api` + `contentApi()`):
  `createFolder` / `createMemory` (seal name under `K_SC`, blinded `slugToken`, Memory births `K_mem` wrapped
  under `K_SC`) / `listTree` (decrypt names) / `openMemory` (unwrap `K_mem`) / `renameNode` / `deleteNode`.
  **Done:** `npm run check` green (73 tests; `tree.test.ts` round-trips name-seal + K_mem wrap/open + the slug
  token = `v1.slugToken(K_SC, scopeId, slug)` with real v1 crypto + mocked API).
- [ ] **5 — apple.** The same in `VeyqClient` (+ generated `ContentAPI` module). **Done when:** `swift test`
  green (create→list→decrypt round-trip) + iOS build.
- [ ] **6 — android.** The same in `:client` (`"content"` added to the generator specs). **Done when:**
  `:client:test` green (same round-trip) + `:app:assembleDebug`.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Memory tree: create/list/rename/move (operator-blind) | Status |
|---|---|---|
| contracts | `content.openapi.yaml` (nodes under a scope) | ✅ (phase 1) |
| crypto (native) | `slug_token` ported apple + android | ✅ (phase 2) |
| backend | `veyq_node` + guards + structural rules | ✅ (phase 3) |
| web | data layer (encrypt names + slug + `K_mem`) | ✅ (phase 4) |
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
