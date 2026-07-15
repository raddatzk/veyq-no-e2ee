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
- `backend/` + **one live client surface, `web/`** ([ADR 0043](docs/decisions/0043-web-first-v1-native-parked.md):
  web-first V1). `apple/` and `android/` hold parked code — no CI builds them, they make no coverage claim, and
  nothing in `docs/` may present them as live until an explicit un-park. Client surfaces are kept at **parity by
  shared contracts, not shared code** ([ADR 0010](docs/decisions/0010-native-clients-shared-contracts-not-code.md));
  `agent/` reserved.
- `docs/` — internal engineering state (English) + working flow (`ai/`, `inbox.md`).
- `wiki/` — **published** end-user GitHub Wiki: plain language, **EN+DE**, focused on what you can do
  and "your data is safe, here's why".
- `changes/` — the **live** work order + any open review backlog; a CR is deleted once done.
- `legacy/` — read-only reference, and **the one directory that leaves at v1**: it splits off to its own
  archive repo when the E2EE product moves to a fresh one. Nothing here depends on it — verified by moving it
  aside and running the trust chain: all guards green, and **no `docs/` link points into it**. What remains
  are ~100 **provenance notes** in prose and code comments (*"ported from `legacy/iOS/Veyq/Auth/ConnectView.swift`"*,
  the inbox's seeded pointers). Those are worth keeping and must **not** be rewritten one by one: after the
  split they stay literal paths, just rooted in the archive instead of here. **This line is that root** — when
  the archive exists, name it here, and all ~100 resolve again.

## How we work
- Working rules + workflow: **`docs/ai/README.md`** (inbox → refine → change-request).
- Be a **sparring partner**: question proposals (mine and the user's), surface tradeoffs and
  simpler alternatives — don't just agree. The hard invariants above are the only thing not up
  for debate.
- **Structured over fast — there is no pressure to ship something demoable quickly.** Build the
  foundation/enabler properly and slice for *soundness*, not for the earliest visible result. Prefer doing
  it right (e.g. the derivatives pipeline before the gallery) over a throwaway stopgap. Correctness and a
  clean structure come first; speed-to-demo is explicitly **not** a goal.
- Scripts and utilities in **Python** (bash only for thin CI/hook glue).
- **No deprecated / unmaintained dependencies.** Never add — and don't keep — a dependency that is EOL,
  archived, or abandoned by its maintainers, or whose only living successor is commercial/closed (that breaks
  *self-hostable by default*). Prefer the **actively-maintained, vendor-neutral standard** over a single
  vendor's client. When a dep we rely on becomes deprecated, replacing it is **in-scope now**, not someday.
  (E.g. MinIO's OSS server was archived in 2026 and its successor AIStor is commercial — so the bundled store
  stays a generic **S3-compatible OSS** one, and the backend signs via the vendor-neutral **AWS S3 SDK**,
  never a MinIO-locked client.)
- Durable engineering state lives in `docs/`. A change-request is scaffolding: **deleted when done**, after
  what it taught is moved to where it binds — a code trap to a comment at that line, a working lesson to a
  rule. Never to git: history does not survive the v1 move.

## Prove your work
Per-platform verification commands are added to each platform's own `CLAUDE.md` as that code
lands. Until then, the relevant check is named in the active change-request.
