# Enabler: Node metadata — the sealed `wrappedMeta` blob

- **ID:** en.memory.node-meta
- **Type:** **Enabler** — structural foundation, no standalone end-user goal
  ([features/README → use-case vs enabler vs ADR vs change-request](../../README.md#use-case-vs-enabler-vs-adr-vs-change-request)).
- **Status:** **partly built** — the envelope + the `date` sub-object shipped with **cr-0067**
  ([uc.memory.date](../use-cases/uc.memory.date.md)); the `location` sub-object is refined but not yet built
  ([uc.memory.place](../use-cases/uc.memory.place.md)).
- **Provides:** **one** optional, opaque, **client-versioned** metadata blob (`wrappedMeta`) on a content node
  (MEMORY/CHAPTER), sealed under that node's **`K_mem`** — the single sealed home for a Memory's curated
  node-level metadata (its **date**, and next its **place**). Materialized so a browse card / a tenant map can
  show + sort by it **without loading the Memory's media**.
- **Serves (use-cases):** [uc.memory.date](../use-cases/uc.memory.date.md) (the `date` sub-object),
  [uc.memory.place](../use-cases/uc.memory.place.md) (the `location` sub-object).
- **Depends on:** [en.memory.tree](en.memory.tree.md) — the node model + `K_mem` birth/open this seals under;
  through it `uc.account.unlock` (identity → `K_SC` → `K_mem`). Applies
  [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) ("capture time, location" as `K_mem` content) at
  the **node** level.
- **Preconditions:** an **unlocked** vault; the node's **`K_mem`** is unwrapped (its Memory is open, or its
  parent chain resolved).
- **Postconditions (success):** the node carries an opaque `wrappedMeta` ciphertext; the server stored + echoes
  it verbatim and never inspected it — no day, coordinate, or place name crosses.

## The envelope

- **`wrappedMeta`** — an optional field on a content node, **AEAD-sealed under the node's `K_mem`** (AAD context
  `node-meta`), wire-encoded as opaque **base64**. It carries a **client-only, versioned** JSON blob whose shape
  can **grow without a contract change** (the operator never learns a field — the lesson from the media EXIF
  tiers). Each sub-object's shape is defined by **its** use-case:

  ```
  { v: 1,
    date?:     { … },   // the sealed date  — see uc.memory.date
    location?: { … } }  // the sealed place — see uc.memory.place
  ```

- **Threaded through the existing node CRUD — no new endpoint.** Set at `createNode`; updated by the **partial**
  `updateNode` (`PATCH …/nodes/{nodeId}`) with **partial semantics** — a non-null `wrappedMeta` replaces it, an
  omitted/null one leaves it unchanged (so a rename/move that omits it is safe); returned (small) on `listNodes`.
  The blind server threads the ciphertext verbatim through `NodeEntity` / store / handler / resource.
- **Migration** `V17__node_meta.sql` (`veyq_node.wrapped_meta TEXT NULL`).

## Materialize, don't recompute — the reason it lives on the node

The browse card grid (and, later, the tenant map) show + sort by this metadata **but never load a Memory's
media**. So the effective value must **already be sealed on the node**, not computed at display from the media.
That is the whole reason `wrappedMeta` sits on the node and is returned by `listNodes`: a client unseals each
node's blob under its `K_mem` and renders — no media fetch, no server-side computation, operator-blind. Each
use-case defines **how** its sub-object is materialized (the date's `auto` reconcile; the place's deliberate
"set location").

## Decisions / notes

- **One extensible blob, not many fields.** Date now, place next, future metadata later — all in the same
  client-versioned `wrappedMeta`, so the contract + DB schema don't churn per addition.
- **Best-effort unseal.** A malformed or wrong-key blob is treated as absent (no metadata), never a broken
  header/card/pin — each use-case degrades gracefully.
- **Operator-blind at the node level** ([ADR 0012](../../../decisions/0012-content-key-hierarchy.md)): the server
  sees the node shape + coarse timestamps + this opaque blob; never its contents.

## Related

- Use-cases: [uc.memory.date](../use-cases/uc.memory.date.md), [uc.memory.place](../use-cases/uc.memory.place.md)
- Enablers: [en.memory.tree](en.memory.tree.md) (the node + `K_mem` substrate this builds on)
- Decisions: [ADR 0012](../../../decisions/0012-content-key-hierarchy.md) (content-key hierarchy; `K_mem` seals
  "capture time, location")
