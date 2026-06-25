# veyq

Self-hostable, end-to-end-encrypted **memory-sharing** app. The server is **operator-blind**:
it stores and serves only ciphertext. The product center is the **Memory** (nested into **Chapters**).

> Fresh start in progress. `legacy/` holds the previous full implementation and its
> target-picture docs — **reference only, never edit it.** The fresh state is built up under
> `docs/` via the inbox → refine → change-request workflow (see `docs/ai/README.md`).

## Hard invariants — never violate
- **Operator-blind E2EE everywhere.** The server sees only ciphertext + opaque metadata (IDs,
  sizes, coarse timestamps, scope/membership, blinded tokens). A user's unshared personal space
  is confidential even from the operator. No path may break this.
- **Product language: Memory / Chapter.** Folders organize only. Never "Album" / "Explorer".
- **Self-hostable by default**; central infrastructure always optional.
- **API-first:** contracts under `contracts/` are the source of truth; never hand-write DTOs.
- **All docs in English.** `legacy/` is read-only reference.

## Repository shape
- `contracts/` — OpenAPI + crypto vectors: the source of truth code is generated from.
- `backend/` + four client surfaces `http/ web/ apple/ android/`, kept at **parity**; `agent/` reserved.
- `docs/` — internal engineering state (English) + working flow (`ai/`, `inbox.md`).
- `wiki/` — **published** end-user GitHub Wiki: plain language, **EN+DE**, focused on what you can do
  and "your data is safe, here's why".
- `changes/` — temporary change-requests. `legacy/` — read-only reference.

## How we work
- Working rules + workflow: **`docs/ai/README.md`** (inbox → refine → change-request).
- Be a **sparring partner**: question proposals (mine and the user's), surface tradeoffs and
  simpler alternatives — don't just agree. The hard invariants above are the only thing not up
  for debate.
- Scripts and utilities in **Python** (bash only for thin CI/hook glue).
- Durable state lives in `docs/`; change-requests in `changes/` are **temporary** (deleted when done).

## Prove your work
Per-platform verification commands are added to each platform's own `CLAUDE.md` as that code
lands. Until then, the relevant check is named in the active change-request.
