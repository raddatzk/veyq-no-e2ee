---
id: cr-share-link-expiry-ui
title: Surface share-link expiry in the web UI (set + display)
status: planned
satisfies: [uc.sharing.share-public-viewer]
adrs: ["0029"]
invariants: [operator-blind-e2ee]
---

## Goal

Share expiry is plumbed end to end, but invisible in the web UI. `createShare(…, expiresAt)` and
`ManagedShare.expiresAt` exist (`web/src/features/memory/share.ts`); the backend enforces it (a share past
`expiresAt` resolves `404`, `SharingHandler`); and
[`uc.sharing.share-public-viewer`](../docs/features/sharing/use-cases/uc.sharing.share-public-viewer.md) §5
promises the curator can *"revoke one or **set an expiry**"*. Yet the UI neither **sets** nor **shows** it:
`ShareCreateDialog.vue` always passes the `expiresAt = null` default, and the shares list in `ShareDialog.vue`
renders `share.created` + `share.revoke` but not expiry. This CR wires the existing plumbing to the UI: an
optional expiry on create/edit, and an expiry line in the list (using the present `share.expires` label).
**No contract or backend change** — `expiresAt` is already accepted and enforced.

## Context & constraints

- **Use-case:** [`uc.sharing.share-public-viewer`](../docs/features/sharing/use-cases/uc.sharing.share-public-viewer.md)
  § *Manage / revoke* (step 5, "set an expiry") + acceptance criterion A1 (expiry → non-enumerable `404`).
- **Decision:** [ADR 0029](../docs/decisions/0029-public-viewer-share.md) (public viewer share; soft L1
  revoke/expiry).
- **Already built (do not re-build):** contract `expiresAt?` in the share create request + record
  (`contracts/api/sharing.openapi.yaml`); backend `SharingHandler` (resolve honours `enabled` + `expiresAt`);
  web `createShare(expiresAt)`, `ManagedShare.expiresAt`, `listSharesFor` parse (`share.ts`); i18n `share.expires`
  = `Expires {date}` / `Läuft ab am {date}` (present).
- **Invariant — operator-blind E2EE:** `expiresAt` is already opaque server-visible metadata (same class as
  `enabled` + `createdAt`); a picker only sets a timestamp, seals nothing new, and adds no content leak.
- **Reuse existing UI** ([styleguide](../docs/crosscutting/ui-web-styleguide.md) § Reuse first): use the
  project's existing PrimeVue date input; the list line mirrors the existing `share.created` row.

## Decisions (the *what/shape*, settled here)

1. **Expiry is optional** — the default stays "no expiry" (null); the picker only *adds* the ability to set one.
2. **Set on both create and edit** — the dialog already re-grades existing shares (cr-0119); expiry rides the
   same create/`updateShare` path.
3. **Display only when set** — the list shows the `share.expires` line for a share with a non-null `expiresAt`,
   next to `share.created`; a share with none shows nothing extra.

**Open — confirm at implement (or now):** does the picker apply to **all** roles the dialog creates
(VIEWER / CONTRIBUTOR / UPLOADER) or **only public VIEWER**? The backend enforces `expiresAt` uniformly, so
"all roles" is the low-friction default; §5's wording is VIEWER-centric. Flagged, not silently decided.

## Inventory (run the locators — don't estimate)

```sh
# The plumbed API (create param, record field, list parse) — the UI must reach these:
grep -n "expiresAt" web/src/features/memory/share.ts
# Where to add the picker (create/edit dialog) and the display line (shares list):
grep -n "createShare\|updateShare\|<Dialog\|role" web/src/features/memory/components/ShareCreateDialog.vue
grep -n "share.created\|share.revoke\|share-list-when" web/src/features/memory/components/ShareDialog.vue
# The label (present, EN+DE) and the contract/backend (shipped, no change):
grep -n "expires" web/src/i18n/index.ts
grep -n "expiresAt" contracts/api/sharing.openapi.yaml
```

## Phases

- [ ] 1 web — (a) `ShareCreateDialog`: optional expiry picker, passed to `createShare`/`updateShare`;
      (b) `ShareDialog` list: render `share.expires` when `expiresAt` is set. · Done when: `npm run check` green;
      a created share carries the chosen `expiresAt` and the list shows it.
- [ ] 2 coverage — `docs/coverage/features/sharing/use-cases/uc.sharing.share-public-viewer.yaml` gains a
      criterion for the **UI** set + display of expiry (a web unit/component test), or an explicit `gaps:`. ·
      Done when: `python3 scripts/validate_coverage.py` green.

*(No contracts/backend phase — `expiresAt` is already accepted and enforced.)*

## Definition of done

`npm run check` green; a curator can set an optional expiry on a share and see it in the list; the value round-
trips through the already-built `expiresAt` path; `docs/coverage/` updated for `uc.sharing.share-public-viewer`
(new criterion covered or explicit gap) with `python3 scripts/validate_coverage.py` green; all
`scripts/validate_*.py` green. Operator-blindness intact (no new sealed/plaintext surface).

## Retrospective

*(filled at close — planning, execution, and process/collaboration; both directions; end with a concrete
"next time".)*
