# Doc-vs-implementation review — open findings

What survives of a five-agent doc-vs-code review (2026-07-14). Thirteen of its seventeen findings are done and
their chronicle is gone; four remain, below.

**How to use this.** Each finding carries a **confirm command** — run it before acting, because it re-grounds the
finding against the *current* code, which may have moved. Then refine with the user → CR, per
[`docs/ai/README.md`](../docs/ai/README.md). Trust levels: *(verified)* = re-checked by hand; *(agent)* =
reported by a review subagent, high-confidence but not independently re-run.

**Its own track record is the reason for that instruction.** The observations here were sound; the estimates were
not — three Tier-1 inventories were 3×, 10× and 4.6× under-counted, and one finding priced months of work around a
precondition that did not exist. **Take the observation, re-derive the plan.** That habit is now a rule and lives
where it binds — [`changes/README.md`](README.md) and [`docs/ai/README.md`](../docs/ai/README.md) § *Refinement
checklist*. It is not restated here.

**The review's own thesis, which still holds:** refinement decides well but *asserts* badly — almost every past
defect was a checkable fact a `grep` would have settled, not a wrong decision. Durable state is trustworthy
exactly where a validator checks it (contracts, coverage refs, links) and drifts exactly where none does
(crosscutting prose, the architecture map, the styleguides).

---

## 12. Bucket authz doc drift — the sharpest one, and security-shaped *(agent)*

[`en.memory.buckets.md`](../docs/features/memory/enablers/en.memory.buckets.md) describes bucket ops as
owner-gated; cr-0123 relaxed them to member-gated. **A reader infers a stronger guarantee than the code gives** —
the kind of doc lie that gets designed against. Its `:41` also claims a `UNIQUE` partial index that `V35` does not
create (the same gap [ADR 0034](../docs/decisions/0034-media-uploader-buckets.md) §4 and
[architecture §11](../docs/architecture/README.md) already carry).

- Confirm: `grep -n 'owner_key == caller\|owner-gated' docs/features/memory/enablers/en.memory.buckets.md` against
  the bucket ops in `MediaHandler.kt`.
- Do: correct the enabler to member-gated.

## 7. Stale ADRs written in the future/past tense *(agent)*

0027 "until then the code still does the one-off seal" (false since cr-0096); 0025 "are unbuilt" (cr-0091 done);
several ADRs future-tense for CRs that landed.

- Confirm: `grep -n 'until then\|are unbuilt\|To be implemented' docs/decisions/*.md`
- Do: edit to current state in place.

## 14. Dead i18n keys = documented-but-never-built features *(agent)*

`share.expires`, `unlock.untrust`, `unlock.trustFailed` are defined, unreferenced, and each maps to a
use-case-documented feature that was never built — so the doc over-promises.

- Confirm: per key, `grep -rc "'<key>'" web/src` → 0 refs beyond the definition.
- Do: per key, either build it or mark the use-case section deferred.

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
