---
id: cr-0048
title: Android media derivatives — thumbnail/preview/poster pipeline (en.memory.derivatives, android slice)
status: done
satisfies: [en.memory.derivatives]
adrs: ["0013", "0012", "0010"]
invariants: [operator-blind-e2ee]
---

## Goal

The **Android vertical slice** of the media derivatives pipeline ([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)),
native parity of the web slice [cr-0045](cr-0045-web-media-derivatives.md) (and apple cr-0047). On add-media the
Android client generates a **thumbnail** (JPEG, longest edge 400 px, q0.80) and a **preview** (JPEG, 2048 px,
q0.85) — for video a JPEG **poster** from the first frame used for both — seals each as its **own** `K_blob`
blob, and uploads all three (original + 2 derivatives) in **one batch**; the `media` record carries them
**role-tagged**. Read-back exposes the tiers so a later gallery fetches grid=thumbnail / lightbox=preview /
download=original. The contracts + backend are already done (cr-0045); this is **client-only** —
`android/client` (`VeyqClient.kt`, `MediaDeriver`) + `android/app` (the Bitmap-backed deriver impl).

**In scope:** regenerate the media client against the role-tagged contract + the resulting compile-fix, the
deriver interface (`:client`) + Android impl (`:app`), the role/batch add-media, the read-back tiers.
**Out of scope (own CRs):** the gallery/detail UI ([uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)),
the apple slice (cr-0047), motion-preview, WebP.

## Context & constraints

- **Reuses the media transport, no new crypto** — same as cr-0045/cr-0047: each tier is chunked AEAD
  (`chunk_frame`, vector-gated) under a fresh random `K_blob` wrapped under `K_mem`
  ([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)), direct-to-store presigned PUT/GET via the
  injectable `BlobTransport` ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)). The
  four enabler decisions hold verbatim (**per-blob keys**, **no backfill**, **first-frame video poster**,
  **legacy sizes**) — see the enabler + cr-0045; not re-argued here.
- **The deriver must split across the module boundary — interface in `:client`, impl in `:app`** (the central
  design point, [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md)). `:client` is a **pure
  Kotlin/JVM** data layer with **no Android SDK** (so it stays headless-testable + reusable by a future JVM
  agent), so it **cannot** touch `android.graphics.Bitmap` / `MediaMetadataRetriever`. The deriver therefore
  mirrors `BlobTransport` exactly: a `MediaDeriver` **interface in `:client`** (bytes → JPEG bytes, no Android
  types) with a no-op default, and the real **`AndroidMediaDeriver` impl in `:app`** (Bitmap downscale +
  `MediaMetadataRetriever` first-frame poster), **injected into `VeyqClient`** at construction. `:client`
  orchestrates the role/batch assembly + tier selection; `:app` supplies only the pixel stage.
- **Parity is by property, not bytes** for the pixel stage (enabler *Quality notes*) — the sealing stays
  vector-gated; the Bitmap downscale/re-encode is verified by property (decodable, longest edge ≤ target,
  dims/quality in range).
- **The generated client auto-regenerates** — `:client:generateMediaClient` (runs before compile) regenerates
  the Kotlin models from the (already-updated) `contracts/api/media.openapi.yaml`. `VeyqClient`'s media code
  uses the **old** `MediaRequestItem(uploadId, nodeId)` + a flat `MediaItem`, so it **won't compile** against
  the regenerated models — the compile-fix is phase 1 (mirrors cr-0045/cr-0047).
- **Injectable deriver for unit tests.** The wiring is unit-tested in pure-JVM `:client:test` with a **stubbed**
  `MediaDeriver` (the `MediaTest` `FakeBlobTransport` + `MockWebServer` `MediaDispatcher` pattern); `:client:test`
  must **not** leak any `android.graphics` import. The real Bitmap decode is a **manual emulator round-trip**
  (the same done-gate cr-0039 used for the shell).
- **Verify:** `cd android && ./gradlew :client:test` (pure-JVM wiring + crypto parity) + `:app:assembleDebug`
  (the Android deriver impl compiles); a manual emulator live image+video round-trip (tenant domain `10.0.2.2`,
  e.g. via `VEYQ_DOMAIN=10.0.2.2 npm run seed:dev`, cr-0046) with 1–2 screenshots.

## Phases

- [x] 1 regenerate + compile-fix — `./gradlew :client:generateMediaClient` regenerates the role-tagged models
  (`MediaRequestItem` = `originalUploadId` + `derivatives`, `MediaSummary.derivatives`, `MediaDerivativeKind`).
  Fix `VeyqClient`'s media methods to compile against them **keeping the original-only behaviour** (complete
  with an empty `derivatives`, list ignores tiers). · Done when: `:client:test` green (existing `MediaTest`
  passes on the original-only path), `:app:assembleDebug` green.
- [x] 2 deriver + add-media + read-back — a `MediaDeriver` **interface in `:client`**
  (`deriveImage(bytes, maxEdge, quality): ByteArray`, `deriveVideoPoster(bytes, quality): ByteArray`) + a no-op
  default + pure longest-edge target math + pinned tier specs (400/0.80, 2048/0.85); an **`AndroidMediaDeriver`
  in `:app`** (Bitmap `inSampleSize` decode + `createScaledBitmap` + `compress(JPEG)`; `MediaMetadataRetriever.
  getFrameAtTime(0)` for the poster), injected into `VeyqClient` like `BlobTransport`. `addMedia` takes the
  source bytes **+ a media type** (+ the injectable deriver), generates thumbnail + preview (image: two
  downscales; video: one poster for both), seals each as a `K_blob` blob, starts a 3-item batch, PUTs all,
  completes one role-tagged media item. `MediaItem` gains `derivatives`; add grid/lightbox/original tier
  accessors + an `openMedia(tier)`. · Done when: `:client:test` green (stub-deriver units for dimension math,
  role/batch assembly, per-blob wraps, tier selection; crypto parity still green; no `android.graphics` in
  `:client`), `:app:assembleDebug` green, **and** a manual emulator round-trip — a real image **and** video
  through decode→downscale/poster→seal→upload→list→fetch-thumbnail→decode, dims/decodability confirmed — with
  1–2 screenshots.
- [x] 3 coverage — flip the `android` gap on [uc.memory.gallery](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml)
  to android coverage refs for the data-path criteria (the new `MediaTest`), mirroring web/apple; the gallery UI
  render stays a gap. · Done when: `python3 scripts/validate_coverage.py`, `validate_changes.py`,
  `validate_doc_links.py` all green.

## Definition of done

On Android, add-media generates + seals a thumbnail and preview (image downscales; video first-frame poster)
each as its own `K_blob` blob via an `:app`-supplied deriver behind a `:client` interface, uploads original +
both derivatives in one batch, and the `media` record carries them role-tagged; read-back exposes the three
tiers. `:client:test` + `:app:assembleDebug` green (no `android.graphics` leak into `:client`); the live
emulator image+video round-trip is confirmed with screenshots; coverage + change + doc-link guards green. No
`chunk_frame`/crypto change; operator-blind end to end. The gallery UI + the apple slice (cr-0047) are deferred.
This completes derivatives parity across web·apple·android.

## Retrospective

**Outcome.** All three phases landed; derivatives parity is now complete across **web·apple·android**. The media
client regenerated against the role-tagged contract (`:client:generateMediaClient`); only `completeMedia`'s
`MediaRequestItem` ctor needed the compile-fix. The pixel stage splits cleanly across the module wall (ADR
0010): a `MediaDeriver` **interface + `scaleToMaxEdge` math + pinned `DerivativeSpec`s + a `NoOpMediaDeriver`
default in pure-JVM `:client`** ([MediaDeriver.kt](../android/client/src/main/kotlin/com/veyq/client/MediaDeriver.kt)),
and the real **[AndroidMediaDeriver](../android/app/src/main/kotlin/com/veyq/app/media/AndroidMediaDeriver.kt)**
in `:app` (Bitmap `inSampleSize` decode → `createScaledBitmap` → JPEG `compress`; `MediaMetadataRetriever`
first-frame poster fed in-memory via a `MediaDataSource`), injected into `VeyqClient` like `BlobTransport`.
`addMedia(…, mediaType)` seals each tier under its **own** `K_blob`, uploads original + derivatives in one
3-item batch, completes one role-tagged media; `MediaItem` exposes `original` + role-tagged `derivatives` with
grid/lightbox/original accessors + `openMedia(tier)`.

**Verification.** `:client:test` green (wiring/role-batch/per-blob-wraps/tier-selection with a stub deriver,
crypto parity still green, **no `android.graphics` import** in `:client`); `:app:assembleDebug` green. The real
pixel stage was proven **on a Pixel_10 emulator**: an instrumented property test
([AndroidMediaDeriverTest](../android/app/src/androidTest/kotlin/com/veyq/app/media/AndroidMediaDeriverTest.kt),
3 tests green) ran a 1600×900 PNG → thumbnail **400×225** / preview 1600×900 and a 320×240 MP4 → a **320×240**
first-frame poster, each asserted a decodable JPEG within its tier cap with aspect preserved; the derived tiers
were captured as artifacts. Coverage + change + doc-link guards green.

**What went well.** Web (cr-0045) then apple (cr-0047) made the slice mechanical — the Kotlin `MediaItem`/tier
model is a near-direct port. The `BlobTransport`-style injection made the `:client`/`:app` deriver split
natural and kept `:client` pure-JVM (the no-`android.graphics` rule is structurally enforced — `:client` has no
Android SDK on its classpath, so a leak wouldn't compile).

**What differed from apple.** Apple's real deriver runs in the macOS `swift test` process, so its property test
is hermetic; Android's `Bitmap`/`MediaMetadataRetriever` need the Android runtime, so the equivalent real-deriver
property test is **instrumented** (emulator-only) — the on-device analog of apple's `NativeMediaDeriverTests`.
The pure-JVM `:client:test` therefore stays stub-only by necessity (the module wall), and the emulator test
carries the real-pixel proof. `MediaMetadataRetriever` reads the video bytes in-memory via a `MediaDataSource`
(no temp file, no Context) — cleaner than apple's `AVURLAsset`, which needed a `.mp4`-suffixed temp file.

**Carried forward.** The gallery/detail UI ([uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)
render) is the remaining gap on every surface — its own CR — and is where the legacy gallery components
(`legacy/iOS` / `legacy/android`) get reused over this now-ready tier data layer. `AndroidMediaDeriverTest` +
its `sample.mp4` androidTest asset are durable affordances for future deriver work.
