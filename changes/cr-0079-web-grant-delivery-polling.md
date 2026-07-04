---
id: cr-0079
title: Web grant-delivery background polling — close the "awaiting access" window without a re-entry
status: done
satisfies: []            # extends the delivery mechanism; realizes the "light poll" cr-0076 deferred
adrs: ["0021", "0020"]
invariants: [operator-blind-e2ee]
---

## Goal

Close the residual **"awaiting access"** window for member-add delivery ([ADR 0020](../docs/decisions/0020-grant-distribution-invite-wrap.md)).
The pull worker ([cr-0076](cr-0076-background-task-mechanism.md)) wraps `K_SC` to pending members, but until now
only ran **on scope entry** ([cr-0077](cr-0077-member-add-onboarding.md)/[cr-0078](cr-0078-web-settings-hub.md)) —
so a member who registered while a key-holder was already sitting in the space waited for that holder's *next*
entry. This adds **background polling**: while a shared scope is open, the client polls the delivery worker on an
interval, so delivery lands without a re-entry.

**Web-only** — no contract, no backend. The interim to the eventual **SSE push** from the same backend
(the zero-latency answer, explicitly deferred in [ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)).

## Context & constraints

- **Pull, on a timer** ([ADR 0021](../docs/decisions/0021-operator-blind-background-tasks.md)): no new
  transport — the same authenticated claim/complete REST calls, now also fired every ~45 s while a shared scope
  is open (in addition to the on-entry run).
- **Visibility-aware:** ticks are skipped while the tab is hidden (Page Visibility API) and fire immediately when
  it becomes visible again — covers "the user comes back to the tab" without burning battery in the background.
- **Shared scopes only:** a TENANT/GROUP scope may have grant-less members; Personal never does, so it runs no
  poll. The poll stops on scope switch and on logout/re-lock.
- **Idempotent + first-online-wins** (cr-0076): multiple tabs or members polling is harmless — the backend
  leases each job once and no-ops an already-delivered grant. Operator-blind is untouched: the client wraps
  on-device, the server only relays ciphertext.
- **Verify:** `cd web && npm run check`.

## Phases

- [x] 1 web — a small polling controller ([`grantDeliveryPolling.ts`](../web/src/features/tasks/grantDeliveryPolling.ts)):
  `start(scopeId, currentKey)` runs the worker immediately, then on a `45 s` interval (skipping hidden-tab
  ticks) plus once on `visibilitychange→visible`; `stop()` clears the timer + listener. `currentKey` is read per
  tick (not captured), so a closed scope no-ops. Wired into [`activeSpace`](../web/src/app/activeSpace.ts):
  `enterScope` starts it for a shared scope (stops it for Personal), `clearActiveSpace` stops it. · Done when:
  `npm run check` green.
- [x] 2 verify — unit tests cover: immediate + per-interval delivery, stop, hidden-tab skip + resume-on-visible,
  the closed-scope no-op, and a clean restart for a new scope. (The on-entry delivery half was already shown
  live in the cr-0077 smoke.) · Done when: the tests pass under `npm run check`.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Background grant-delivery polling while a shared scope is open | Status |
|---|---|---|
| web | interval + visibility-aware poll of the delivery worker | ☑ this CR |
| apple / android | native background poll (own worker loop) | ◻ **deferred (web-first; own CRs)** |

## Definition of done

While a key-holder has a shared scope open, the client delivers pending grants on entry **and** on a
visibility-aware background interval, so a newly-registered member gains access without any key-holder
re-entering the space — first-online-wins, idempotent, operator-blind. `npm run check` green (unit tests for
the poll lifecycle). SSE push remains the eventual zero-latency replacement (ADR 0021, deferred).

## Retrospective

Small, self-contained. Kept the poll in its own controller ([`grantDeliveryPolling.ts`](../web/src/features/tasks/grantDeliveryPolling.ts))
rather than inlining a `setInterval` in `activeSpace` — the key getter is passed in (not imported), so there is
no `activeSpace ↔ tasks` import cycle and the lifecycle (start on shared-scope entry, stop on switch/logout) stays
readable. Interval is a `45 s` constant; the visibility gate means an idle background tab costs nothing. The real
end-state is still an SSE push (ADR 0021) — this just shrinks the awaiting-access window from "next entry" to
"≤ one interval" in the meantime.
