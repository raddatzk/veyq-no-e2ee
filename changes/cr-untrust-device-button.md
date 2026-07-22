---
id: cr-untrust-device-button
title: Surface a user-facing "untrust this device" control (web)
status: planned
satisfies: [uc.account.trust-device]
adrs: ["0005"]
invariants: [operator-blind-e2ee]
---

## Goal

Device trust is built end to end, but a user cannot *un*trust from the UI. `untrustThisDevice(email)`
(`web/src/features/account/trustDevice/trustDevice.ts`) deletes the local device key **and** the server DEVICE
wrap (`DELETE /api/account/wraps/{id}`, shipped), and it is fully unit-tested — yet its **only** caller today is
`LoginView.vue`, which invokes it *internally* to drop a stale password wrap before re-trust. There is no visible
control. Meanwhile [`uc.account.trust-device`](../docs/features/account/use-cases/uc.account.trust-device.md)
has a whole **## Untrust ("forget this device")** section and its plain-language part promises the user *"You can
untrust a device whenever you want."* This CR closes that promise↔UI gap: add a visible untrust control (and the
trust-state it acts on) in the account area, wired to the already-built logic. **No contract or backend change.**

## Context & constraints

- **Use-case:** [`uc.account.trust-device`](../docs/features/account/use-cases/uc.account.trust-device.md)
  § *Untrust ("forget this device")* + the state diagram (`Trusted --> Untrusted : untrust`).
- **Decision:** [ADR 0005](../docs/decisions/0005-unlock-methods-and-device-trust.md) (unlock methods + device
  trust; "every device is mine" — untrust deletes only *this* device's wrap).
- **Already built (do not re-build):** contract `DELETE /api/account/wraps/{id}`
  (`contracts/api/account.openapi.yaml`); backend `AccountWrapsResource.deleteWrap` → `KeyMaterialHandler` /
  `KeyMaterialStore.deleteWrap`; web `untrustThisDevice` + `isTrusted` (`trustDevice.ts`); i18n `unlock.untrust`
  (EN + DE, present in `web/src/i18n/index.ts`).
- **Invariant at risk — operator-blind E2EE:** untrust must delete **only the caller's own** DEVICE wrap (the
  endpoint is already caller-scoped + idempotent); the control adds no new server visibility. Re-read the
  [security model](../docs/crosscutting/security-model.md) before touching the key path.
- **Reuse existing UI, never invent** ([styleguide](../docs/crosscutting/ui-web-styleguide.md) § Reuse first):
  the nearest built account/profile surface is the home; the control reuses existing button/confirm patterns.

## Decisions (the *what/shape*, settled here)

1. **Home = the account/profile surface, not the login/unlock screen.** Trust is granted silently at login
   ("remember this device"); the untrust counterpart belongs where a signed-in user manages their account, so it
   is reachable without re-authenticating. Implementation confirms the exact host via the locator below.
2. **Show trust state, then offer the action.** The control reflects `isTrusted(email)` and only offers untrust
   when this device is trusted (else nothing to forget).
3. **Confirm before acting** — untrust means the next unlock needs the Secret Key; a mis-click is costly.

## Inventory (run the locators — don't estimate)

```sh
# The built API + its only current (internal) caller:
grep -rn "untrustThisDevice\|isTrusted\b" web/src --include=*.ts --include=*.vue
# Candidate homes — the account/profile surfaces + the trust-related UI:
grep -rln "ProfileMenu\|activeSpace\|account\|settings" web/src/views web/src/components/app
grep -rln "trustThisDevice\|remember\|isTrusted" web/src/views web/src/features/account
# The label (present, EN+DE) and the endpoint (shipped, no change):
grep -n "untrust" web/src/i18n/index.ts
grep -n "wraps/{id}\|deleteWrap" contracts/api/account.openapi.yaml backend/src/main/kotlin/com/veyq/app/features/account/keymaterial/*.kt
```

## Phases

- [ ] 1 web — add the untrust control (trust-state display + confirm + call `untrustThisDevice`) in the
      account/profile surface, reusing `unlock.untrust`. · Done when: `npm run check` green and the control
      untrusts the current device (state flips to untrusted; the DEVICE wrap is gone).
- [ ] 2 coverage — `docs/coverage/features/account/use-cases/uc.account.trust-device.yaml` gains a criterion for
      the **UI** untrust path (a web component test), or an explicit `gaps:` with reason. · Done when:
      `python3 scripts/validate_coverage.py` green.

*(No contracts/backend phase — both are already shipped and unchanged.)*

## Definition of done

`npm run check` green; a user can untrust the current device from the web UI and the trust state reflects it;
`docs/coverage/` updated for `uc.account.trust-device` (new criterion covered or an explicit gap) with
`python3 scripts/validate_coverage.py` green; all `scripts/validate_*.py` green. No new server visibility —
operator-blindness intact.

## Retrospective

*(filled at close — planning, execution, and process/collaboration; both directions; end with a concrete
"next time".)*
