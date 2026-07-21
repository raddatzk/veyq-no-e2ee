# Doc-vs-implementation review — open findings

What survives of a five-agent doc-vs-code review (2026-07-14). **Sixteen of its seventeen findings are done**;
one remains, below. (Findings 7, 12 and 14 were closed 2026-07-21 — 7 fixed in place, 12 and 14 measured
already-resolved by intervening work; their closure record is in [`docs/inbox.md`](../docs/inbox.md) #87.)

**How to use this.** The finding carries a **confirm command** — run it before acting, because it re-grounds
against the *current* code, which may have moved (that habit is why 12 and 14 turned out already-fixed). Then
refine with the user → CR, per [`docs/ai/README.md`](../docs/ai/README.md).

**The review's own thesis, which still holds:** refinement decides well but *asserts* badly — almost every past
defect was a checkable fact a `grep` would have settled. Durable state is trustworthy exactly where a validator
checks it (contracts, coverage refs, links) and drifts exactly where none does (crosscutting prose, the
architecture map, the styleguides). The "take the observation, re-derive the plan" rule this bought lives where
it binds — [`changes/README.md`](README.md) and [`docs/ai/README.md`](../docs/ai/README.md); not restated here.

---

## 6. ADR 0038's drift-guard catches 3 of 10 enum drifts *(agent)*

The overlap-≥2 heuristic in `scripts/validate_contracts_common.py:108-109` misses drops/renames on 2-value
families; `suite-version` (1 value) is structurally unguarded. An agent mutation-tested it across 10 cases.

**Read [ADR 0038](../docs/decisions/0038-contracts-shared-components-bundling.md) before touching the guard.** Its
decision to guard *instead of* extracting a shared `_common.openapi.yaml` rested on a consumer that could not
resolve external `$ref` at all. That consumer no longer exists, so extraction is now possible and the guard's
reason for existing is weaker than it reads. **Do not harden a guard that may not be the right mechanism.**

- Do: settle the ADR first. Then either strengthen the guard (exact-set comparison per family) or scope its
  coverage claim down to the truth.

---

**Also open, tracked in [`docs/inbox.md`](../docs/inbox.md), not here:** splitting the inbox by area. Its phantom
`docs/wiki/` header path is fixed and it is down from 82k to ~56k characters, so its trigger ("needs scrolling to
navigate") is no longer acute — but it has not been split.
