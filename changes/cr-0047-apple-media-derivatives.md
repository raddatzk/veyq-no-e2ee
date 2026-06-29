---
id: cr-0047
title: Apple media derivatives — thumbnail/preview/poster pipeline (en.memory.derivatives, apple slice)
status: done
satisfies: [en.memory.derivatives]
adrs: ["0013", "0012"]
invariants: [operator-blind-e2ee]
---

## Goal

The **Apple vertical slice** of the media derivatives pipeline ([en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md)),
native parity of the web slice [cr-0045](cr-0045-web-media-derivatives.md). On add-media the Apple client
generates a **thumbnail** (JPEG, longest edge 400 px, q0.80) and a **preview** (JPEG, 2048 px, q0.85) — for
video a JPEG **poster** from the first frame used for both — seals each as its **own** `K_blob` blob, and
uploads all three (original + 2 derivatives) in **one batch**; the `media` record carries them **role-tagged**.
Read-back exposes the tiers so a later gallery fetches grid=thumbnail / lightbox=preview / download=original
without ever pulling the full original for a grid. The contracts + backend are already done (cr-0045); this is
**client-only** — `apple/Sources/VeyqKit/VeyqClient.swift` + `MediaAPI` (auto-generated) + `MediaTests`.

**In scope:** regenerate `MediaAPI` against the role-tagged contract + the resulting compile-fix, the native
deriver, the role/batch add-media, and the read-back tiers. **Out of scope (own CRs):** the gallery/detail UI
([uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md)), the android slice (cr-0048),
motion-preview, WebP.

## Context & constraints

- **Reuses the media transport, no new crypto** — same as cr-0045: each tier is chunked AEAD (`chunk_frame`,
  vector-gated) under a fresh random `K_blob` wrapped under `K_mem` ([ADR 0012](../docs/decisions/0012-content-key-hierarchy.md)),
  direct-to-store presigned PUT/GET ([ADR 0013](../docs/decisions/0013-blob-storage-presigned-direct-to-store.md)).
  The four enabler decisions hold verbatim: **per-blob keys**, **no backfill**, **first-frame video poster**,
  **legacy sizes** — see [en.memory.derivatives](../docs/features/memory/enablers/en.memory.derivatives.md) and
  cr-0045; not re-argued here.
- **Parity is by property here, not bytes** (enabler *Quality notes*): the sealing stays vector-gated, but the
  pixel stage (decode → downscale → JPEG re-encode) is platform-native and byte-divergent — verified by
  property (decodable, longest edge ≤ target, dims/quality in range), not a byte vector.
- **The deriver is `VeyqKit`-resident and cross-platform — use ImageIO/CoreGraphics + AVFoundation, NOT UIKit.**
  `VeyqKit` builds on macOS = iOS = iPadOS (+ tvOS libs), so the deriver must compile on **all** of them. UIKit
  `UIImage`/`UIGraphicsImageRenderer` (the legacy `MemoryDetailView.swift:310` reference) is iOS-only and would
  break the macOS build. Use **ImageIO** (`CGImageSourceCreateThumbnailAtIndex` with
  `kCGImageSourceThumbnailMaxPixelSize` + `kCGImageDestinationLossyCompressionQuality`) for the image downscale
  and **AVFoundation** `AVAssetImageGenerator` for the video first-frame poster — both are macOS+iOS. This is
  the one Apple-specific design point; unlike android (cr-0048) no app-layer split is needed (ImageIO has no
  framework-module wall the data layer can't cross).
- **The generated client auto-regenerates** — the swift-openapi-generator **build plugin** regenerates
  `MediaAPI` from the (already-updated) `contracts/api/media.openapi.yaml` on `swift build`. The current
  `VeyqClient` media code uses the **old** `MediaRequestItem(uploadId:nodeId:)` and a flat `MediaSummary`, so it
  **won't compile** against the regenerated types — the compile-fix is phase 1 (mirrors how cr-0045 fixed web).
- **Injectable deriver for unit tests.** The wiring (dimension math, role/batch assembly, tier selection) is
  unit-tested under `swift test` with a stubbed `MediaDeriver` (the `MediaTests` `MediaStub` + `FakeBlobTransport`
  pattern); the real ImageIO/AVFoundation decode is a **manual live round-trip** (the same done-gate cr-0034
  used for the shell — no automated device-pixel test).
- **Verify:** `cd apple && swift test`; `xcodebuild` iOS + macOS (+ tvOS libs) green (`-skipPackagePluginValidation`);
  the crypto conformance test stays green; a manual live image+video round-trip with 1–2 screenshots.

## Phases

- [x] 1 regenerate + compile-fix — `swift build` regenerates `MediaAPI` (role-tagged `MediaRequestItem` =
  `originalUploadId` + `derivatives`, `MediaSummary.derivatives`, `MediaDerivativeKind`). Fix `VeyqClient`'s
  media methods to compile against the new types **keeping the original-only behaviour** (complete with an
  empty `derivatives`, list ignores tiers). · Done when: `swift test` green (existing `MediaTests` pass on the
  original-only path), iOS+macOS build green.
- [x] 2 deriver + add-media + read-back — a `MediaDeriver` protocol in `VeyqKit`
  (`deriveImage(_:maxEdge:quality:) -> Data` via ImageIO, `deriveVideoPoster(_:) -> Data` via
  AVAssetImageGenerator) + pure longest-edge target math + the pinned tier specs (400/0.80, 2048/0.85).
  `addMedia` takes the source bytes **+ a media type** (+ an injectable deriver, defaulting to the native one),
  generates thumbnail + preview (image: two downscales; video: one poster for both), seals each as a `K_blob`
  blob, starts a 3-item batch, PUTs all, completes one role-tagged media item. `MediaItem` gains `derivatives`;
  add grid/lightbox/original tier accessors + an `openMedia(tier:)`. · Done when: `swift test` green (stub-deriver
  units for dimension math, role/batch assembly, per-blob wraps, tier selection; conformance still green) **and**
  a manual live round-trip — a real image **and** video through decode→downscale/poster→seal→upload→list→
  fetch-thumbnail→decode, dims/decodability confirmed — with 1–2 screenshots.
  · **Done:** `swift test` green (7 new media units: dimension math, role/batch assembly, per-blob wraps, tier
  selection; conformance still green); iOS + macOS + tvOS-libs `xcodebuild` green; the **live image+video
  round-trip** ran for real (env-gated `LiveRoundTripTests.testDerivativeTiersRoundTripAgainstLiveInstance`
  against the bundled Garage, `directTransport: true`): a 1600×900 PNG → thumbnail **400×225** / preview
  1600×900 and a 1280×720 MP4 → a **1280×720** first-frame poster reused for both tiers, each fetched as
  ciphertext + decoded back, dims/decodability asserted — screenshots captured.
- [x] 3 coverage — flip the `apple` gap on [uc.memory.gallery](../docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml)
  to apple coverage refs for the data-path criteria (the new `MediaTests`), mirroring the web refs cr-0045 added;
  android stays a gap. · Done when: `python3 scripts/validate_coverage.py`, `validate_changes.py`,
  `validate_doc_links.py` all green.

## Definition of done

On Apple, add-media generates + seals a thumbnail and preview (image downscales; video first-frame poster) each
as its own `K_blob` blob, uploads original + both derivatives in one batch, and the `media` record carries them
role-tagged; read-back exposes the three tiers. `swift test` + iOS/macOS builds green; the live image+video
round-trip is confirmed with screenshots; coverage + change + doc-link guards green. No `chunk_frame`/crypto
change; operator-blind end to end; the deriver compiles on macOS (ImageIO, not UIKit). The gallery UI + the
android slice (cr-0048) are deferred.

## Retrospective

**Outcome.** All three phases landed. `MediaAPI` regenerated against the role-tagged contract (the build
plugin did it on `swift build`; only `completeMedia`'s init needed the compile-fix). The new
[`MediaDeriver.swift`](../apple/Sources/VeyqKit/MediaDeriver.swift) carries the `MediaDeriver` protocol, the
pure `scaleToMaxEdge` math, the pinned `DerivativeSpec`s (400/0.80, 2048/0.85), and the `NativeMediaDeriver`
over **ImageIO** (`CGImageSourceCreateThumbnailAtIndex`) + **AVFoundation** (`AVAssetImageGenerator`). `addMedia`
now takes `mediaType` + an injectable deriver, generates thumbnail+preview (video: one poster for both), seals
each tier under its **own** `K_blob`, uploads original + derivatives in one 3-item batch, and completes one
role-tagged media; `MediaItem` exposes `original` + role-tagged `derivatives` with grid/lightbox/original tier
accessors and `openMedia(tier:)`. `swift test` (89, incl. 7 new media units) + iOS/macOS/tvOS-libs builds + the
three doc guards are green. The **live round-trip** was driven for real against the bundled Garage.

**What went well.** The web slice (cr-0045) made the shape unambiguous — the Swift `MediaItem`/tier model is a
near-direct port of `media.ts`. ImageIO/AVFoundation gave a clean cross-platform deriver (compiles on macOS =
iOS = tvOS, no UIKit), so the one Apple-specific design point held. The injectable `MediaDeriver` kept the
wiring fully unit-testable while the real decode was proven by the live smoke — exactly the split the CR called
for. The contract was already done, so no backend/contract churn.

**What went wrong / friction.**
- *Execution (mine):* I launched `quarkusDev` with the **wrong** Garage secret (`0123…×4` from memory, not the
  real 64-hex `0123456789abcdef…` in `garage-init.sh`) — caught it before it mattered and relaunched. The
  authoritative secret is in the init script, not the shorthand.
- *Real bug the live test caught:* `deriveVideoPoster` staged the bytes in an **extension-less** temp file, and
  `AVURLAsset` refuses to parse a container it can't identify from the path — the poster failed with
  `mediaDeriveFailed`. Fixed by writing a `.mp4`-suffixed temp file (covers the ISOBMFF mp4/mov/m4v family) and
  relaxing the first-frame tolerance to `before=0 / after=+∞` (a strict 0/0 window fails on encodes with no
  frame exactly at t=0). The stubbed units could never have surfaced either — both are squarely in the pixel
  stage. **Follow-up the session added:** because ImageIO/AVFoundation *do* decode in the macOS `swift test`
  process (unlike web's jsdom), the real `NativeMediaDeriver` is now covered **hermetically by property**
  ([`NativeMediaDeriverTests`](../apple/Tests/VeyqKitTests/NativeMediaDeriverTests.swift): generate a real
  PNG/MP4 → derive → decode → assert decodable JPEG, longest edge ≤ cap, aspect preserved) in ~1s with no
  backend — so this bug class now fails fast in the normal suite, and the env-gated live test is left to prove
  only the transport. (Fixtures shared via `MediaFixtures`.)
- *Test ergonomics:* `try XCTUnwrap(try await …)` doesn't compile (await in a non-concurrent autoclosure); the
  awaited call has to be hoisted to a `let` first. Hit it in both the unit and live tests.

**Carried forward.** The android slice (cr-0048) is the remaining parity gap; the gallery/detail UI
([uc.memory.gallery](../docs/features/memory/use-cases/uc.memory.gallery.md) render) is its own CR on every
surface. `LiveRoundTripTests.testDerivativeTiersRoundTripAgainstLiveInstance` is a durable env-gated affordance
(generates a real PNG + MP4 on-device, writes decoded tiers to `VEYQ_ARTIFACT_DIR`), inert in a plain
`swift test`.
