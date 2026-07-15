---
id: cr-0170
title: Space icon — a sealed, constant-length avatar for the tenant scope
status: planned
satisfies: [uc.spaces.set-space-icon]
adrs: ["0007", "0017", "0022", "0023", "0024", "0025", "0026", "0027", "0042"]
invariants:
  - operator-blind E2EE — the server must never see the image, a key, or the image's size
  - API-first — contracts are the source of truth; no hand-written DTOs
---

## Goal

Give a **tenant** a face. Its owner picks one image, frames it 1:1, and the client bakes the crop, re-encodes it
to a fixed square, **pads it to a constant plaintext length**, and seals it under a key derived from the scope's
current content epoch. The server stores `sealed_icon` + `icon_epoch` on the scope row — an opaque blob of an
always-identical size plus a small integer — and the avatar renders on the tenant's own row **and on every other
tenant's row in the switcher**, which is the point of the feature: those rows are what a member needs to tell
their tenants apart. Serving that second case is why `GET /api/scopes/my-tenants` widens to carry each tenant's
own `wrappedGrant`. The **initial stays the fallback** wherever no icon is set or no grant is held.

## Context & constraints

The durable truth this must honor — links, not copies:

- **The use-case:** [`uc.spaces.set-space-icon`](../docs/features/spaces/use-cases/uc.spaces.set-space-icon.md).
  Every decision (why sealed, the derived key, the baked crop, the padding, the owner gate, `iconEpoch` as
  forward-compatibility) is stated there with its reasoning. **Read it before this file.**
- **The read path + the rule that permits it:**
  [`en.spaces.tenant-scope`](../docs/features/spaces/enablers/en.spaces.tenant-scope.md) § *Every tenant's grant,
  in one Host-independent call* — it owns why serving all grants discloses nothing, why ADR 0002/0020 read
  broader than their rationales and do not bind, and which trust tier the widening is charged to.
- **Where it shows:** [`uc.spaces.switch-spaces`](../docs/features/spaces/use-cases/uc.spaces.switch-spaces.md)
  main-flow step 2.
- **Contracts:** `contracts/api/scopes.openapi.yaml`, `contracts/crypto/vectors/v1.json`.
- **Invariants at risk:** operator-blindness (a sealed field on a row that until now held *no* sealed content —
  this is the first), and the **length** channel the padding exists to close.
- **The shape to copy:** `renameGroup` is the same write, one scope-kind down — the same owner gate, the same
  epoch rule, the same `409 stale_epoch` + retry-once. Deviate from it only where this CR says why.

## Inventory

Counts are command output, re-run 2026-07-15 — not estimates.

| Surface | What | Command → output |
|---|---|---|
| contracts | `ScopeSummary` +`sealedIcon`/`iconEpoch`; `MyTenant` +`wrappedGrant`/`sealedIcon`/`iconEpoch`; new `POST`+`DELETE /api/scopes/{scopeId}/icon`; new `space_icon_key` vector | — |
| contracts | **A claim that becomes false and must be retracted** | `grep -n "no key material is involved" contracts/api/scopes.openapi.yaml` → **`609`** |
| backend | migration head → next is **V40** | `ls backend/src/main/resources/db/migration \| sort -V \| tail -1` → `V39__share_password.sql` |
| backend | test files touching the scope-row shape | `grep -rln "nameEpoch\|ScopeRow\|TenantRow" backend/src/test \| wc -l` → **2** (`ScopesSliceTest.kt`, `OperatorBlindProbeTest.kt`) |
| backend | *(the naive grep lies — do not use it)* | adding `encryptedName` → **20**, because 18 of those are **node** names, not scope names |
| backend | current marker count | `grep -rho "@Test[A-Za-z]*" backend/src/test \| grep -cx "@Test"` → **312** |
| web | `TenantAvatar` sites — **all four renders + the import are one file** | `grep -rn "TenantAvatar" web/src` → **5** lines: import `AppSidebar.vue:14`, renders `:254` (sidebar active), `:292` (switcher overview), `:447` / `:460` (mobile sheet). All fed by one `ref<MyTenant[]>` (`:42`, loaded `:188`) — so one shape change reaches every site. No `TenantAvatar.test.ts` exists. |
| web | test files touching the scope-row shape | `grep -rln "listMyScopes\|ScopeSummary\|encryptedName\|nameEpoch" web/src --include="*.test.ts" \| wc -l` → **9** |
| web | `deriveImage` mock sites — widening its `opts` touches each | `grep -rln "deriveImage" web/src --include="*.test.ts" \| wc -l` → **4** |
| web | current marker count (CI-run files only; the 4 `*.e2e.test.ts` are `describe.skipIf`-gated and no CI job runs them) | `find web/src -name "*.test.ts" ! -name "*.e2e.test.ts" -exec grep -hoE "^[[:space:]]*(it\|test)\(" {} + \| wc -l` → **631** |

## Traps

Each of these was found while refining and would otherwise be found by the build, or by production.

1. **`symEncrypt` does not exist.** `grep -rn "symEncrypt" web/src \| grep -v generated` → **0**. The sibling docs
   (`uc.spaces.rename-group`, ADR 0027) write the seal as `symEncrypt(K, x)` — that is notation, not a symbol.
   The real seam is `symWrap(kek, bytes, context, nonce)` (`web/src/shared/crypto/v1.ts:103`), wrapped in the
   `sealName` shape (`web/src/features/memory/tree.ts:72` — b64std over `0x02 || nonce(12) || aead`, fresh
   12-byte nonce).
2. **`TEXT` is 65,535 bytes on MariaDB and effectively unbounded on H2 — the test engine.** An oversized
   `@Size` therefore passes the *entire* hermetic suite and fails only in production. Two fields already do
   exactly this (`account.openapi.yaml:512` declares `@Size(max = 1048576)` over a `TEXT` column;
   `content.openapi.yaml:435` declares `65536` against a 65,535-byte column) → **inbox #94**. The icon's `@Size`
   is the **exact computed base64 length of the sealed constant**, and it is proven by a real-MariaDB write at
   the cap in the live smoke — not by the H2 suite, which cannot see this class of bug.
3. **The write key is not the read key.** `activeScopeKey()` returns only `E_current`
   (`web/src/app/activeSpace.ts:62`). Reading an icon sealed at an older `iconEpoch` needs the **peeled chain** —
   `keyAt(epochs, iconEpoch)` for reads vs `writeKey(epochs)` for writes, the same asymmetry `openGroupName`
   (`scopes.ts:106`) and `renameGroup` (`:299`) already have.
4. **`ScopesStore.myTenants` has a live bug in the exact function this CR edits.** `memberships.first().role`
   (`ScopesStore.kt:390`) can return a *personal*-scope `OWNER` row for a plain tenant member → `MyTenant.role`
   reports `OWNER` wrongly. Selecting the **root-scope** membership (`scopeId == tenantId`) fixes the role **and**
   yields the `wrappedGrant` this CR needs — one edit, both. **This CR closes inbox #93.** It is a display field,
   not a gate, so it is not an escalation — but `ScopesSliceTest.kt:470` only ever seeds a real owner, so the
   test that catches it must seed an **outsider who also owns a personal scope in that tenant**.
5. **Padding is greenfield.** `grep -rniE "\bpad(ding)?\b" web/src backend/src contracts` (minus CSS) → **1**
   hit, and it is an unrelated HIBP `Add-Padding` request header. There is **no** length-hiding code in this
   repo, so the pad/unpad pair is the single riskiest new primitive here. Its unpad-failure posture is the
   use-case's E2: fall back to the initial, **never throw**.
6. **Conformance vectors are single-stack now.** Commit `7a1c4e9f` (HEAD~2) deleted the native surfaces and
   ADR 0009 with them; only `web/src/shared/crypto/v1.conformance.test.ts` asserts
   `contracts/crypto/vectors/v1.json`. The icon vector is a **regression pin for one implementation** — do not
   describe it as cross-stack parity, and do not copy `inviteName.ts:18`'s "native byte-identical" comment,
   which is stale (→ inbox #95).
7. **`/server/settings` is not a route.** The ADR 0022 hub replaced it; the only settings route is
   `router.ts:33` (`/settings/:section?`). `router.ts:17-23`'s comments still describe the old routes — stale,
   belongs to inbox #78's opportunistic nits, not to this CR.

## Phases

- [ ] **1 contracts** — `ScopeSummary` + `MyTenant` field additions (`wrappedGrant`/`sealedIcon` **nullable**:
      a member is grant-less during awaiting-access and a space may have no icon); `POST` + `DELETE
      /api/scopes/{scopeId}/icon` modelled on `/api/scopes/{scopeId}/name` (`scopes.openapi.yaml:565`) with the
      same `x-field-extra-annotation` validation shape; **retract the `:609` "no key material is involved"
      claim**; add the `space_icon_key` vector to `contracts/crypto/vectors/v1.json` (clone `group_name_key`'s
      five fields, `info` = hex of `veyq/v1/space-icon-key`) — inert until phase 3a asserts it. ·
      **Done when:** codegen regenerates cleanly on both surfaces and every `scripts/validate_*.py` is green.
- [ ] **2 backend** — V40 (`sealed_icon TEXT NULL`, `icon_epoch INT NOT NULL DEFAULT 0`; H2 ∩ MariaDB subset);
      entity fields; `ScopesStore.setIcon`/`clearIcon` (blind overwrite, `@Transactional`, the `renameGroup:139`
      shape) + `listMine` carrying the two fields + **`myTenants` re-selecting the root-scope membership**
      (trap 4); `SetScopeIconOutcome`/`ClearScopeIconOutcome` with the `renameGroup:179` guard order **exactly**
      — membership → kind (`!= TENANT`) → owner → epoch — since that order is ADR 0042's contract, not a style;
      the two resource adapters. **A remove takes no epoch** (use-case A5 — there is no ciphertext to be stale).
      Tests: the owner gate incl. an outsider who owns a personal scope in the tenant; the stale-epoch 409;
      `OperatorBlindProbeTest` extended so the new endpoints answer 404 to a non-member. ·
      **Done when:** `./gradlew check` green (312 markers + the new ones).
- [ ] **3a web — primitives, behaviour-preserving** — `features/spaces/spaceIcon.ts`: `deriveSpaceIconKey`
      (HKDF, `veyq/v1/space-icon-key`) + `pad`/`unpad` to the constant + `sealIcon`/`openIcon` on `symWrap`
      (trap 1); the conformance `it()` in `v1.conformance.test.ts` (**vector first, then green** — ADR 0017
      rule 4); `deriveImage` taught a `mimeType` **and a background fill** so WebP-with-alpha works and a
      transparent logo does not flatten onto a black canvas (touches the 4 mock sites); the cropper **extracted**
      out of `MemoryCoverDialog.vue` with an aspect-ratio prop. · **Done when:** `npm run check` green (631 + new)
      **and the Memory cover is unchanged** — still 16:9, still saves, proven by its existing tests plus one
      manual re-crop. Nothing user-visible ships in this phase.
- [ ] **3b web — the feature** — a tenant identity header in `TenantMembersPanel`, mirroring
      `GroupMembersPanel.vue:290-308`'s `group-name-header` (the built pattern; **not** a new screen), owner-gated
      off the `isOwner` that panel already computes; set / replace / remove wired to the two endpoints with the
      `renameGroup:299` retry-once-on-`stale_epoch` loop; `listMyTenants` opening each tenant's grant
      **transiently**, decrypting the icon, and **zeroing the key in `finally`** (the `listMyGroups:127` pattern),
      with `useObjectUrls` owning the resulting URLs; the four `AppSidebar` avatar sites fed from the widened
      `MyTenant`. · **Done when:** `npm run check` green **and** a live smoke on `veyq.localhost:8081` (per the
      S3-backed dev backend; never a second `quarkusDev`): owner sets an icon → it appears on the sidebar row and
      in the switcher overview from a second tenant; a second account still awaiting access sees the **initial**;
      the DB shows opaque ciphertext of the constant length and no image bytes.
- [ ] **4 coverage** — fill `docs/coverage/features/spaces/use-cases/uc.spaces.set-space-icon.yaml`: drop
      `status: planned`, give every one of the 8 criteria a real test ref or an explicit gap with a reason. ·
      **Done when:** `python3 scripts/validate_coverage.py` green.

## Definition of done

`./gradlew check` + `npm run check` green; `for f in scripts/validate_*.py; do python3 "$f"; done` **all** green;
`docs/coverage/` filled for `uc.spaces.set-space-icon` with no `status: planned` left. The live smoke above is
part of done, not a nice-to-have — **two of this CR's risks (the MariaDB `TEXT` cap and the cross-tenant grant
read) are structurally invisible to the hermetic suites**, which is exactly why they are called out here.

**Guard promoted from a one-shot proof:** the constant-length claim is the invariant this CR rests on, and a
one-shot "I checked two icons" cannot hold it — a future quality tweak would silently reintroduce a variable
length. It becomes a **permanent unit test**: seal a flat two-colour square and a dense photograph, assert the
two ciphertexts have **identical** length. A grep cannot express this; the test can.

Inbox to close on landing: **#58** (the feature) and **#93** (the `myTenants` role bug, fixed by trap 4's edit).

## Retrospective

_To fill at close — what went wrong in planning, in execution, and in process/collaboration; root cause; one
concrete "next time". Both directions. The process half is also spoken to the user before the session ends._
