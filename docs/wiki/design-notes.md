# Design Notes & Open Questions

> Exploratory ideas and decisions-in-progress not yet stable enough for the main pages. Decided items
> graduate into [crosscutting](crosscutting/) / [decisions](decisions/README.md); open items are
> tracked here and in the decision log.

## Open decisions

1. **AD-18 params** — journal snapshot triggers (`X` interval, `Y` update threshold) and round-robin vs. presence-based leader.
2. **Second folder model — "Drive" (Later)** — a plain web-storage file tree (generic files and
   folders) alongside Memory curation, distinct from **curation folders**. Reuses scope keys + Agent
   Mode; becomes its own feature when scheduled.
3. **Trash & restore (Later)** — a user-facing soft-delete UX (trash → restore → purge) over the
   [AD-10](decisions/README.md) deletion model; a future `uc.memory.delete-restore`.
4. **Per-media caption (Later)** — a short one-line text per photo/video. Today the only text surfaces
   are the Memory **title** and the **Journal**.
5. **Later** — Key Transparency rollout; encrypted-relay CRDT details for shared journals; convergent-
   encryption-for-dedup tuning; full legal/DPA apparatus before SaaS launch.

6. **Key-directory trust hardening (Later)** — **manual key verification** (out-of-band fingerprint/QR,
   Signal-style) as a user action atop TOFU/KT; and **identity-key rotation** (re-wrap all grants
   old→new, sign with the old key, sync the new pair to all devices) for the compromised-key case. Both
   lean on Key Transparency.

## Delivery note — sequencing the E2EE rollout

The architecture target is **E2EE everywhere**. Delivery can still be incremental: the **personal
(unshared) scope** is the simplest, highest-value first slice (no sharing complexity); the
**shared-scope** key-management + Key Transparency investment can follow. The design assumes all-E2E
from day one so nothing is painted into a corner.

## Diagram candidates (PlantUML strengths Mermaid lacks)

Worth adding as the docs grow: **use-case diagrams** per feature (actors ↔ use-cases), **activity
diagrams with swimlanes** for complex flows (e.g. the Agent-Mode upload + fallback), **component
diagrams** for the bounded contexts. See [Patterns & Conventions](crosscutting/patterns-and-conventions.md)
and the feature indexes.

## Reference material being mined (legacy `docs/`)

`memory-product-context.md`, `architecture.md`, the feature catalog `product/features/*.yaml`, and the
20 ADRs under `docs/adr/`. These are **reference** and are retired section-by-section as these pages
supersede them.
