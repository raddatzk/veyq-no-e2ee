---
id: cr-0132
title: Test-citation precision — re-point 4 tangential coverage refs (audit P2d)
status: done
satisfies: []
adrs: []
invariants: []
---

## Goal

Fix four coverage citations that pointed at tests which do not actually prove the criterion they were
attached to. The trust-chain validator only checks a referenced test *exists*, not that it *proves* the
claim, so a tangential ref reads as green-but-hollow coverage. This CR re-points (or, where the ref was pure
duplication, removes) each of the four, and records *why* each cited test proves its criterion inline in the
coverage YAML so the citation can't silently drift again. No test or product code changes — coverage-doc
precision only.

## Context & constraints

- **Docs-only**; the four files are `docs/coverage/features/**`. Verified each new ref against the actual
  test body (via an Explore pass over `MediaBucketsSliceTest`, `MediaSliceTest`, `TenantTermsSliceTest`,
  `media.buckets.test.ts`, `MemoryDetail.test.ts`, `MediaLightbox.test.ts`) — not inherited from the finding.
- **`operator-blind-buckets`** (`uc.memory.buckets`) cited backend `listBucketsByNonMemberIs404` — an
  authz-404 test, tangential to blindness. Re-pointed to `uploadStampsOwnerKeyAndMaterializesPrimary` (a
  personal Memory's lone primary carries only the owner's opaque `owner_key`, no sealed name) +
  `renameSubBucketUpdatesSealedName` (the sealed name is opaque base64 the operator only relays). The web
  `media.buckets.test.ts` ref stays — it holds the actual wrong-AAD proof (`openBucketName` under the wrong
  context returns `null`).
- **`date-sorted-grid`** (`uc.memory.gallery`) cited backend `MediaSliceTest.startCompleteListRoundTrip` +
  `updateMetaOverwritesSealedTierAndLists` — a blob round-trip + a sealed-meta overwrite, neither of which
  asserts sort order. Sorting is client-side; the real proof is `MemoryDetail.test.ts` (newest-first default +
  the oldest-first toggle re-sorting listed tiles with no refetch) + `media.test.ts` (the `effectiveCapturedAt`
  key; undated → an undefined key that sorts last). The backend's sealed-`capturedAt` relay is already proven
  under `lightbox-exif-detail` / `lightbox-correct-capturedat`, so the two backend refs are **dropped** here
  rather than mis-cited — no coverage is lost, only the mis-citation.
- **`operator-blind`** (`uc.account.accept-terms`) cited only `readOrAcceptByANonMemberIs404` (the non-member
  404 clause). **Added** `memberAcceptsThenABumpReStalesAndAStaleAcceptIs409`, which is where acceptance is
  shown to be a bare integer version — the "coarse operational metadata, never content" core of the criterion.
  The criterion's accountless-visitor "no record kept" clause is true by construction (both endpoints are
  membership-gated) but has **no dedicated test** — left as an honest thin spot (noted inline), not papered
  over with a false citation.
- **`trash-soft-delete-any-member`** (`uc.memory.delete-media`) cited web `MediaLightbox.test.ts` as a unit
  ref. That file only tests the lightbox delete affordance (button + `Delete` key emit a `delete` event) — it
  belongs to `lightbox-delete-and-hotkey` (which already cites it) and proves no server-side trash semantics.
  The criterion's actual claims (flag flip, drop from list + reconcile id-set, `delete_revision` bump,
  non-member 404, unauth 401) are fully proven by the three backend slice tests + `http/media/trash.http`, so
  the tangential web ref is **removed**.
- **Verify:** `python3 scripts/validate_coverage.py` green (every re-pointed ref still resolves) + the other
  two guards.

## Phases

- [x] 1 coverage — re-point/remove the four citations across `uc.memory.buckets`, `uc.memory.gallery`,
  `uc.account.accept-terms`, `uc.memory.delete-media`, each with an inline rationale. · **Done:**
  `validate_coverage.py` → "39 use-case(s) mapped, all acceptance criteria covered"; `validate_changes.py` +
  `validate_doc_links.py` green.

## Definition of done

The four criteria cite the tests that actually prove them (verified against the test bodies); every ref
resolves; the three trust-chain guards stay green. No `satisfies:` change — this corrects existing coverage,
it doesn't add a use-case.

## Retrospective

- **The finding under-specified two of the four, and taking it at face value would have been wrong.**
  `accept-terms operator-blind`'s existing ref (`readOrAcceptByANonMemberIs404`) was *correct* for the 404
  clause — it needed an *addition* (the integer-version test) for the "never content" core, not a swap. And
  `date-sorted-grid`'s backend refs weren't just "wrong tests" — they proved a real but *different* property
  (the sealed relay) already cited elsewhere, so the fix was removal, not replacement. Reading each test body
  first (not the finding's verb) is what surfaced this. Root cause of the risk: a coverage citation encodes a
  claim "test T proves criterion C" that no tool checks — only a human reading T against C catches a hollow one.
- **Found an unclaimed gap and named it rather than hiding it:** the accountless-visitor anonymity clause has
  no test. Recording that inline is more honest than leaving the criterion looking fully proven.
- **Next time:** when a coverage criterion bundles two properties (here: operator-blindness *and* sorting, or
  metadata-shape *and* non-member-404), cite one test per property or split the criterion — a single ref
  against a two-part claim is exactly what lets a tangential citation hide.
