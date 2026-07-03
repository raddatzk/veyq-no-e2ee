---
id: cr-0070
title: Raise thumbnail derivative 400→512 px + require a high-quality downscale filter (all surfaces)
status: done
satisfies: [uc.memory.gallery]
refines: [en.memory.derivatives]
invariants: [operator-blind-e2ee]
---

## Goal

Make gallery-grid thumbnails **crisp** instead of mushy. Two changes to the pinned derivative spec
([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)), kept at **parity across
web·apple·android** (the tiers are one shared spec):

1. **Thumbnail longest-edge cap 400 → 512 px** (quality unchanged at 0.80). A ~200 px grid tile on a 2×/3×
   display already needs 400+ device px; the old 400 cap gave zero retina headroom, so tiles rendered ~1:1 at
   best and upscaled on 3×. 512 restores headroom. Preview stays legacy-pinned (2048 / 0.85).
2. **High-quality downscale filter is mandatory.** A thumbnail is a large single-step reduction (e.g. 4032 →
   512 ≈ 8×); a platform's *cheap default* resampler aliases fine detail into a smear. The web canvas was the
   offender — it defaulted to `imageSmoothingQuality: 'low'`. Each surface must select its HQ scaler.

## Why

A detailed photo (fine text/lines) looked badly smeared in the grid while the lightbox (preview, 2048) was
crisp — reported as "unfassbar schlecht." Root cause was **not** JPEG quality (0.80 is fine): it was (a) the
low-quality canvas resample on web and (b) the 400 px cap being too small for hi-DPI + detailed content.

## Changes

- **web** ([media.ts](../web/src/features/memory/media.ts)): `TIER_SPECS.THUMBNAIL.maxEdge` 400 → 512;
  `drawToJpeg` now does a **progressive-halving** downscale (≤2× steps, each `imageSmoothingQuality='high'`)
  instead of one large `drawImage` — a single 8× canvas resize is one bilinear pass and stays soft. The legacy
  web client avoided this by deriving server-side (libvips); operator-blind forces on-device here.
- **apple** ([MediaDeriver.swift](../apple/Sources/VeyqKit/MediaDeriver.swift)): `DerivativeSpec.thumbnail`
  maxEdge 400 → 512. The native deriver already uses ImageIO `CGImageSourceCreateThumbnailAtIndex` (a proper
  resampling downscaler) — no filter change needed.
- **android** ([MediaDeriver.kt](../android/client/src/main/kotlin/com/veyq/client/MediaDeriver.kt)):
  `DerivativeSpec.THUMBNAIL` maxEdge 400 → 512. The native deriver already uses
  `createScaledBitmap(..., filter = true)` — no filter change needed.
- **doc** ([en.memory.derivatives.md](../docs/features/memory/enablers/en.memory.derivatives.md)): tier table +
  notes updated; added the "high-quality downscale filter" requirement per surface.
- **tests**: the 400-bound assertions on all three surfaces updated to 512 (web media.test.ts, apple
  MediaTests/NativeMediaDeriverTests/LiveRoundTripTests, android client MediaTest + app AndroidMediaDeriverTest).

## Scope / notes

- **New uploads only.** Derivatives are generated + sealed at upload time; existing media keep their 400 px /
  low-quality thumbnails until re-uploaded (there is no re-derive/migration path — [en.memory.derivatives]
  wipes+re-derives rather than migrates).
- Not a contract change (maxEdge is a client-side constant; the `THUMBNAIL`/`PREVIEW` kinds are unchanged).

## Verification

- web: `media.test.ts` green at 512.
- apple / android: the media edits are mechanical (matched def + tests, 400→512, at parity), but **both native
  modules fail to compile for an unrelated, pre-existing reason** — `VeyqClient` token handling: `refreshToken`
  is `String?` in the regenerated API model but `session.set(refresh:)` expects `String` (apple
  `VeyqClient.swift:88,106`; android `VeyqClient.kt:105,116`), untouched by this CR (web's session layer already
  handles the optional, which is why web is green). Re-confirmed live at close: `swift build` and
  `:client:compileKotlin` both fail there, so `swift test` / `:client:test` cannot run.

## Close (in-progress → done)

Closed with the **native-tier test verification explicitly deferred**. The 400→512 cap + the mandatory
high-quality downscale are in place across web·apple·android at parity and are **web-verified**; the native
media tests (512) are **not yet run** because those modules don't compile for the unrelated `VeyqClient` reason
above — a separate defect that predates and is untouched by this CR. When that blocker lands, run `swift test`
and `:client:test` (plus the instrumented `AndroidMediaDeriverTest`, which needs an emulator) to confirm the
native tiers; no cr-0070 code change should be needed there. This CR owns the spec + code change; the native
green-light rides on the `VeyqClient` fix, not on further work here.
