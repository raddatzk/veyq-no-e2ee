---
id: cr-0139
title: Crypto conformance gaps — auth vectors + dead-code prune (audit P2g)
status: done
satisfies: []
adrs: []
invariants: [operator-blind]
---

## Goal

Close the crypto-conformance-vector gaps (audit bundle 70 / P2g): pin the Model-B auth derivation
(`auth_salt` + `auth_keypair`, ADR 0003) with **shared vectors asserted byte-identically on web + apple +
android** (it was only a per-stack non-vector sign/verify round-trip, a silent cross-platform drift risk),
extend the existing `sym_invite_temp_key` assertion to apple + android, and remove dead crypto
(`shareWrapKey`/`shareUploadToken` + the coupled `share_pw` vector, and the orphan `passkey_prf_hkdf` vector no
stack implements). Verifying the vectors on apple required first compile-fixing apple's drifted `VeyqKit`
(the apple twin of the #79 android breakage), which is folded in.

## Context & constraints

- **Refined first (the finding was loose on 2 of 4 items).** A sparring pass corrected it: `invite-temp-key`
  is not a keypair derivation — the vector `sym_invite_temp_key` already exists (pins the wrap-context string),
  so the real gap is asserting it on apple+android, not authoring new bytes. And `passkey_prf_hkdf` is an
  **orphan** — no stack implements passkeys (only a `MasterKeyWrap.PASSKEY` enum stub), so "assert on all
  stacks" is impossible; per the agreed decision it is **deleted** (re-add with the passkey feature).
- **The two real new vectors** (`auth.auth_salt`, `auth.auth_keypair`) were **computed from the canonical web
  impl** (`web/src/shared/crypto/v1.ts` — the reference all stacks mirror; the legacy generator is stale) with
  fixed inputs, then asserted on all three suites. `auth_keypair` pins the **production** Argon2 params
  (`m=32768`) so the vector guards the shipping derivation. All three suites go green → the password→Ed25519
  identity is now proven byte-identical cross-stack, not assumed.
- **Dead-code prune (web-only):** `shareWrapKey` (dead in prod, superseded by the live `sharesPwToken`) +
  `shareUploadToken` (fully dead) removed from `v1.ts`; their `share_pw` vector + web assertion removed
  (removing the function without the vector would red the web suite — they are coupled). `sharesPwToken`, the
  `.public-share-upload` CSS and the `share-upload` rate-limit bucket are untouched (false-positive collisions).
- **Apple `VeyqKit` compile fix (bundled prerequisite — apple #79-twin).** `swift test` builds the whole
  package, so apple crypto couldn't be verified until `VeyqClient.swift` compiled. It had the same media
  contract-drift android did (finding #80's predicted apple exposure): the nullable `refreshToken`, the
  `MediaListPage` list wrapper (`.media`), and three new generated response cases (`.unprocessableContent` 422,
  `.conflict` 409, `.notModified` 304). Fixed mechanically — the new cases map to `unexpectedStatus` (honest;
  no invented semantics) since those paths are WIP on apple.
- **Invariant — operator-blind: strengthened.** The new vectors pin the operator-blind auth derivation
  (password never leaves the device) against cross-platform drift; nothing new is sent to the operator.
- **Out of scope — apple `VeyqKit` fixture-sync (apple twin of cr-0138).** `swift test`'s data-layer suites
  (`ScopesTests`/`ContentTreeTests`/`MediaTests`) are still red on stale fixtures (epoch fields, MediaListPage,
  …) — the same fixture-drift cr-0138 fixed for android. Flagged on #80 as the remaining apple work; not this
  crypto CR.
- **Verify:** web `npm run check`; android `./gradlew :crypto:test`; apple `swift test --filter V1ConformanceTests`.

## Phases

- [x] 1 contracts+web+apple+android — add `auth.auth_salt`/`auth.auth_keypair` (computed from the web ref) +
  assert on all three suites; assert `sym_invite_temp_key` on apple+android; delete `shareWrapKey`/
  `shareUploadToken` + the `share_pw` + `passkey_prf_hkdf` vectors; compile-fix apple `VeyqClient.swift`. ·
  **Done:** web `npm run check` green (575 tests, incl. the 2 new conformance assertions); android `:crypto:test`
  BUILD SUCCESSFUL; apple `V1ConformanceTests` **16 tests / 0 failures** — all three byte-match the same
  vectors; trust-chain guards green.

## Definition of done

`auth_salt` + `auth_keypair` asserted byte-identically on web+apple+android; `sym_invite_temp_key` asserted on
all three; dead `shareWrapKey`/`shareUploadToken` + the `share_pw`/`passkey_prf_hkdf` vectors gone; all three
crypto conformance suites green; trust-chain guards green. (Apple data-layer fixture-sync deferred → #80.)

## Retrospective

- **The refine pass earned its keep.** Taken literally the finding said "add 4 vectors, assert on all stacks";
  the scan showed one target was a misnomer (the invite vector exists — assert it on mobile) and one was an
  orphan with no implementation (passkey). Authoring vectors for non-existent code would have been busywork or,
  worse, premature dead crypto. Computing the two real vectors from the canonical impl (not hand-typing bytes)
  is what made a green cross-stack run trustworthy.
- **P2g surfaced #80 for apple, concretely.** Verifying crypto on apple forced the apple `VeyqKit` to compile,
  which exposed the same silent contract-drift #79 found on android — confirming finding #80's "apple has the
  same exposure." The CI native-surface gap (#80) is the root cause of both; this CR fixes apple's compile
  layer as a side effect and leaves the fixture-sync as tracked follow-up.
- **Next time:** the auth derivation shipped on three stacks for many CRs pinned only by round-trips, not shared
  vectors — a real drift window that nothing would have caught. New cross-stack crypto should land its shared
  vector in the same CR as the implementation, not a later audit.
