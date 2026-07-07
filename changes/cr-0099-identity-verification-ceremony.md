---
id: cr-0099
title: Identity verification — QR safety-number compare → verified, changed-key review, directory trust badges
status: done
satisfies: [uc.spaces.verify-identity]
adrs: ["0028", "0009", "0023"]
invariants: [operator-blind-e2ee]
---

## Goal

The **human half** of [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md): let a member
**elevate a pin `pinned → verified`** by comparing the **safety-number out-of-band** — the ergonomic path is a
**QR scan** — and **review a changed key** side by side (accept → re-pin, or refuse → leave blocked). Plus the
**directory trust badges** (verified / pinned / changed) that read the pin store. This builds directly on
[cr-0098](cr-0098-identity-pinning-tofu.md): the sealed pin store, its data layer, and the `verified` state (already
in the `PinState` type) exist; the `fingerprint()` safety-number primitive exists and is **vector-pinned** but
**dormant** (never called in production, no UI). **Client-only and operator-blind — no server call**; the only write
is the **existing** `identity-pins` PUT (the `verified` state rides *inside* the sealed blob). Fills
`safety-number-verify` and `changed-key-review` of
[uc.spaces.verify-identity](../docs/features/spaces/use-cases/uc.spaces.verify-identity.md) and lands the badge
display. **No new crypto primitive, no contract, no backend, no http.**

## Context & constraints

- Durable truth: [uc.spaces.verify-identity](../docs/features/spaces/use-cases/uc.spaces.verify-identity.md) (the
  criteria this fills: `safety-number-verify`, `changed-key-review`; the badge read of `change-surfaced-not-trusted`),
  [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md) (the sealed store +
  the `verified` state + the **"verified wins"** merge rule),
  [ADR 0028](../docs/decisions/0028-identity-authenticity-tofu-verification.md) §3 (the verification ceremony),
  [ADR 0009](../docs/decisions/0009-native-crypto-per-platform-vectors.md) (`fingerprint()` — the 6×5-digit
  safety-number, already conformance-vectored), [ADR 0023](../docs/decisions/0023-per-tenant-member-identity.md)
  (identity is account-global → **verify once, verified everywhere**).
- Substrate from cr-0098 (reused, not rebuilt): the pin-store module
  ([identityPins.ts](../web/src/features/account/identityPins.ts)) with `checkAndPin` / `acceptKeyChange` / `getPin` /
  `mutateAndSave` and the version-guarded PUT with union-merge; the `verified` value already exists in `PinState`.
  `fingerprint()` + `serializeIdentityPublic` / `deserializeIdentityPublic` live in
  [v1.ts](../web/src/shared/crypto/v1.ts). A QR **scanner** already ships (the emergency-kit flow, `jsQR`) — reuse it,
  never a new dependency.
- **No contract / backend / http.** Verification is **client-only** (compare a scanned/spoken safety-number against
  the sealed pin — **no server call**, ADR 0028 § Quality notes), so the operator never learns who verified whom. The
  only persistence is the **existing** `GET/PUT /api/account/identity-pins` (cr-0098) — the `verified` flag is a field
  *inside* the Master-Key-sealed blob, opaque to the server.
- Invariant at risk: **none.** Operator-blindness is untouched — no new server state, no new endpoint; the trust graph
  stays off the server. Tier-consistent ([ADR 0026](../docs/decisions/0026-client-trust-tiers.md)): crypto-grade on a
  trusted client, honest-operator on operator-served web — the same tier as all veyq E2EE.
- **Two decisions locked (from the refine):**
  1. **The QR payload is the safety-number digits (confirm-only)** — the scanned value is compared against
     `fingerprint(pinnedKey)`. A match confirms the key you already hold; it does not *establish* a key from scratch
     (you always already hold the TOFU-pinned or directory key you are confirming). Simplest, Signal-style, and it
     keeps a scan from ever writing a key the app didn't already have.
  2. **Accept → `pinned`, never straight to `verified`.** Accepting a changed key re-pins it as `pinned`
     (`acceptKeyChange`, cr-0098); `verified` is set **only** after an actual out-of-band safety-number compare
     succeeds. A blind accept must never mint a green "verified" no human checked.

## Phases (web-only; native is a later CR)

- [x] 1 web — the **pin-store verify layer** (extend [identityPins.ts](../web/src/features/account/identityPins.ts)):
  `safetyNumber(identityPublicKeyB64)` (→ `fingerprint(deserializeIdentityPublic(...))` → the 6×5 digit groups);
  `markVerified(userId, verifiedKey)` — sets the pin `state = verified` **only if the current pin's key still equals
  `verifiedKey`** (else abort: the key moved under the compare, re-verify), version-guarded PUT with union-merge where
  **verified wins** a same-contact conflict; `trustState(userId, directoryKey) → 'verified' | 'pinned' | 'changed' |
  'unpinned'` (the badge read — pure, no write). Unit tests: `safetyNumber` matches the `sym`/`fingerprint` vector;
  `markVerified` round-trips + is a no-op/abort when the pinned key moved; the "verified wins" merge on a 409;
  `trustState` branches. · Done when: `npm run check` green.
- [x] 2 web — the **verify UI** (uc.spaces.verify-identity main flow + A2): a member "Verify" view showing **my**
  computed safety-number of that contact (6×5 digits) + a **QR of my own** identity fingerprint + a **scan**
  affordance (reuse the emergency-kit `jsQR` scanner). On scan → compare the scanned digits against
  `safetyNumber(pinnedKey)` → **matches** (offer "mark verified" → `markVerified`) or **differs** (warn *different
  key — do not trust*). **Read-the-digits-aloud fallback** (A2) when no camera. i18n EN+DE. · Done when: `npm run
  check` green + a manual compare smoke (two seeded members verify each other on a live instance).
  **Built + live-smoked** on a fresh instance (`veyq.localhost`) with a grant-less second member — the read-aloud
  (A2) compare elevated the pin `pinned → verified`, the badge flipped, and the state survived a reload.
  **Then DESCOPED (post-build decision, see Retrospective):** the proactive verify ceremony UI
  (`VerifyIdentityDialog` + `SafetyNumberScanner`) was **removed** — a per-member verified state is advisory noise
  for the normal user and only pays off under scope-wide strict mode (cr-0100). The pin-store primitives stay.
- [x] 3 web — the **changed-key review** (A/E1/E2) + **directory badges**: a `changed` badge opens a review showing
  **expected (pinned)** vs **current (directory)** safety-numbers **side by side**; **accept** → `acceptKeyChange`
  (re-pin as `pinned`; the user may then run Verify to reach `verified`), **refuse** → leave blocked (the wrap/delivery
  stays gated). Trust badges (verified / pinned / changed) on each member row in
  [GroupMembersPanel.vue](../web/src/features/spaces/GroupMembersPanel.vue),
  [TenantMembersPanel.vue](../web/src/features/spaces/TenantMembersPanel.vue), and the group member directory —
  driven by `trustState`. This also gives the cr-0098 rotation exclusion a **visible surface** (the excluded member
  now reads as `changed`, not a silent awaiting-access). i18n EN+DE. · Done when: `npm run check` green.
  **DESCOPED (post-build decision):** only the **changed** warning + review shipped; the **verified / pinned**
  badges were **removed** (advisory noise) — the directory now shows a trust surface *only* when a key changed.
- [x] 4 coverage/docs — flip [uc.spaces.verify-identity.yaml](../docs/coverage/features/spaces/use-cases/uc.spaces.verify-identity.yaml):
  fill `safety-number-verify` + `changed-key-review`, and record the badge build under the `web` gap; note in
  [en.account.identity-pinning](../docs/features/account/enablers/en.account.identity-pinning.md) that `verified` is
  now reachable via the ceremony. The use-case stays `planned` **only** because `strict-mode` remains (cr-0100).
  · Done when: `validate_coverage.py` + `validate_changes.py` + `validate_doc_links.py` + `validate_feature_wiring.py`
  green.

## Definition of done

**As shipped (after the post-build descope — see Retrospective):** the pin store gains the **verify primitives**
(`safetyNumber`, `markVerified`, `trustState`/`trustStates`, `myIdentityPublicKeyB64`), all unit-tested and
data-layer live-smoked. The **only user-facing trust surface is the change response**: a member whose directory
key differs from the pin shows a **changed** warning + a **side-by-side** expected-vs-current review with **accept**
(re-pin as `pinned`) / **refuse** (leave blocked), on both member panels. All **client-only and operator-blind** —
no server call; the trust graph stays off the server (cr-0098's `identity-pins` PUT is the only persistence). Fills
`changed-key-review` of `uc.spaces.verify-identity`; `safety-number-verify` stays a **gap** (primitives only, the
ceremony UI was descoped) and `strict-mode` remains — so the use-case stays `planned`. Guards green (`npm run
check`, coverage/changes/doc-link/feature-wiring validators).

_(Original DoD, superseded: "A member can verify a contact out-of-band … the directory shows trust badges
(verified/pinned/changed)." The proactive ceremony + verified/recognized badges were removed post-build.)_ **Out of scope (follow-ups):** the
account-global **strict mode** (verified-before-share hard gate + worker-skip, cr-0100); **identity / master-key
rotation** (its own refine, ADR 0028 §5); **native parity** (web-first, like the rest of the groups epic).

## Retrospective

Shipped web-only, no new crypto/contract/backend/http — exactly as scoped. The pin-store verify layer
(`safetyNumber`, `markVerified`, `trustState`/`trustStates`, `myIdentityPublicKeyB64`) plus the two ceremony
dialogs (`VerifyIdentityDialog`, `ChangedKeyReviewDialog`), the reused `SafetyNumberScanner` (jsQR, no new dep),
the `TrustBadge`, and the badges wired into both member panels. 17 new unit tests (real-crypto round-trips: the
`fingerprint` vector, the verified-wins 409 merge, the moved-key abort, the batch badge read); `npm run check`
green; all four validators green.

**Two design refinements made during the build:**
- `trustStates` (batch) added alongside the specced `trustState` — a directory of N members reads its badges in
  **one** pin-store fetch, not N GETs.
- `myIdentityPublicKeyB64` reconstructs the caller's own bundle from the vault private key (via a new
  `identityPublicFromPrivate` in v1.ts) rather than a directory self-match — robust even where the caller isn't
  in the scope directory.

**Live smoke** confirmed the end-to-end path on a fresh `veyq.localhost` instance: the grant-delivery worker
TOFU-pinned a newly-joined grant-less member (cr-0098) → badge *Bekannt*; the Verify dialog showed the correct
6×5 safety-number (byte-identical to the seeder's independent `fingerprint`) + my own QR; the read-aloud path
elevated to *Verifiziert* and **persisted across a reload**. The self row correctly shows no badge/verify button.
Infra footgun re-encountered: the running dev DB had drifted from `.run/.env` (documented) → a `down -v` +
`bootstrap:user` rebuild was needed before the smoke.

**Left for follow-ups (unchanged):** the changed-key *review* dialog was exercised by unit tests + renders via the
shared Dialog pattern, but a live *changed*-badge needs an identity rotation to stage (its own refine, ADR 0028 §5);
strict-mode (verified-before-share gate + worker-skip) is cr-0100; native parity is a later CR.

### Post-build descope (decided with the product owner right after the live smoke)

Reviewing the shipped UI raised two objections that held up: (1) a per-member **verified/pinned badge** in a members
directory reads as an *ambient property of the other user* ("mallory is verified") rather than *my private
confirmation*, and (2) proactive verification is an advanced/opt-in concern the normal user doesn't understand or
want. The genuinely valuable, actionable signal is a **changed key**. So we **removed** the proactive ceremony
(`VerifyIdentityDialog`, `SafetyNumberScanner`, `TrustBadge`) and the verified/recognized badges + i18n + the
`verified` CSS modifier, and kept **only** the changed-key warning + `ChangedKeyReviewDialog`. The pin-store
**primitives stay** (`markVerified`, `safetyNumber`, `myIdentityPublicKeyB64`, `identityPublicFromPrivate`) as the
library the strict-mode CR will wire.

A deeper design finding fell out of the same discussion and is why the ceremony is *deferred*, not just hidden:
**a per-account strict mode ("verify before share") can't protect shared-scope content** — any co-member without
strict mode can still key an unverified/substituted identity into the scope, exposing everyone's content. Real
protection needs a **scope-wide** policy, which in turn needs a *shared* source of verification truth without
leaking the trust graph to the operator. The candidate shape — a **K_SC-sealed, signed attestation registry with an
authority/quorum policy** (a scope-local mini web-of-trust) — is captured in `docs/inbox.md` as the seed for the
cr-0100 refine. Verification (and its UI) returns there, gated behind the strict-mode opt-in.
