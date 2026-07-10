---
id: cr-0109
title: Web — flat topbar uploads button + open the cover cropper from the lightbox
status: done
satisfies: [uc.memory.upload, uc.memory.cover]
invariants: [operator-blind-e2ee]
---

## Goal

Two small **web-only** polish fixes reported together — no contracts, backend, or crypto:

1. **Topbar uploads button — style parity + a distinct icon.** The global uploads-tray trigger
   ([UploadStatusMenu.vue](../web/src/features/memory/components/UploadStatusMenu.vue)) rendered as a **boxed**
   button (1px border + surface fill) beside the flat, transparent `topbar-icon-button` siblings (theme, settings)
   — reading as the odd one out — **and** carried the **same `pi-cloud-upload` glyph** as the in-Memory **Upload**
   action button ([MemoryDetail.vue](../web/src/features/memory/components/MemoryDetail.vue)), a confusing
   collision. Make the tray trigger **transparent/borderless** like its neighbours and give it the **`pi-list`**
   glyph (it is a status/tray, not the upload action).

2. **Cover from the lightbox — frame the crop, don't guess it.** Choosing **"Set as cover"** on a photo in the
   gallery lightbox set the cover **in one click** with a default full-frame crop (the cr-0073 behaviour), so the
   user could not frame it at pick time. Make it **open the crop editor seeded on that photo** instead — the
   lightbox **stays open** beneath the modal so browsing continues, and the user frames the 16:9 crop right away.

## Context & constraints

- **Refined state this builds on:**
  [uc.memory.upload](../docs/features/memory/use-cases/uc.memory.upload.md) (the global uploads tray — the icon and
  style sit **below** its `global-uploads-tray` criterion's altitude, a pure presentation tweak, no flow change)
  and [uc.memory.cover](../docs/features/memory/use-cases/uc.memory.cover.md) — **main flow steps 1–2 now describe
  the lightbox "Set as cover" opening the crop editor** (seeded on that photo, lightbox staying open) rather than a
  one-click full-frame set. The header cover flow (upload / re-crop current) is unchanged.
- **Two independent tweaks, bundled as trivial sibling polish.** They touch different features and could be two
  CRs; each is ~10 lines of web-only change with no shared surface, so they ride one record (see retro).
- **Presentation only — operator-blind untouched.** No wire change, no new bytes, no crypto. The cover still seals
  `{ source: { kind: 'media', mediaId }, crop }` under `K_mem` via the existing `updateNode` path; the only change
  is *when* the crop is chosen (at pick time, in the editor) vs. defaulted to the full frame.
- **Reuse the existing UI — no new components.** The uploads trigger adopts the existing `.topbar-icon-button`
  visual (transparent + `--p-content-hover-background` hover, same 36px square). The cover editor gains a **third
  crop source** `media` alongside its existing `upload`/`keep` — it already crops an injected image URL, so the
  lightbox photo's **already-decrypted `thumbs` tile** (the exact tier `loadNodeCover` renders the cover from) is
  passed in as that source: **no extra decrypt**, and the crop is display-accurate.
- **Keep the lightbox usable under the modal.** The lightbox listens on `window` for Escape/←/→. `MediaLightbox`
  gains a `suspendKeys` prop (bound to `coverEditorOpen`) that early-returns its key handler while the cropper is
  open — otherwise one Escape would close **both** and arrows would navigate the lightbox under the modal. The
  PrimeVue modal (auto z-index ≥ 1101) already layers above the lightbox (`z-index: 1000`).
- **Fallback preserved.** A photo with no ready thumbnail (non-derivable / still loading) still takes the
  one-click full-frame set — the cropper needs a source image.
- **Non-goals:** the in-Memory Upload **action** button keeps `pi-cloud-upload` (it *is* the upload action); no
  change to the cover seal/model, the header cover flow, or the share-viewer; native heads (web-first,
  [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md); their own CRs).
- **Verify:** `cd web && npm run check` (typecheck + vitest + build); the three trust-chain guards green; a live
  browser smoke on `veyq.localhost`.

## Phases

- [x] 1 web — the two fixes.
  - **Uploads button** ([UploadStatusMenu.vue](../web/src/features/memory/components/UploadStatusMenu.vue)):
    `.uploads-trigger` → transparent + borderless + `--p-content-hover-background` hover (matching
    `.topbar-icon-button`); resting glyph `pi-cloud-upload` → `pi-list` (the active spinner is unchanged).
  - **Cover from lightbox:** `MemoryCoverDialog` gains a `mediaSource` prop + a `{ kind: 'media', mediaId }`
    `Pick`, whose save emits the existing `{ kind: 'media', mediaId, crop }` intent (the parent's `onCoverSave`
    already handles it); it restores the stored crop when the picked photo is already the cover.
    `MemoryDetail.onSetCoverFromPhoto` opens the editor seeded with the photo's `thumbs` tile (fallback: one-click
    full-frame when absent) and passes `:suspend-keys="coverEditorOpen"` to the lightbox; the header button opens
    the editor unseeded. `MediaLightbox` gains the `suspendKeys` guard.
  - · Done when: `npm run check` green; `MemoryCoverDialog.test.ts` covers the new `media` source (crop + emit,
    and stored-crop restore when the photo is already the cover); `MediaLightbox.test.ts` (the `setCover` emit)
    stays green.
- [x] 2 coverage — [uc.memory.cover.yaml](../docs/coverage/features/memory/use-cases/uc.memory.cover.yaml):
  split the malformed criterion (a lost `- id:` line had folded the lightbox/crop-editor criterion into
  `one-crop-everywhere`, shadowing its text+coverage via duplicate YAML keys) into a restored `one-crop-everywhere`
  + a proper `set-cover-and-crop-editor`, and update the latter's text + `gaps.web` prose to the cr-0109 behaviour
  (unit refs assert the dialog crop-editor + the lightbox emit; the `MemoryDetail` glue is disclosed as
  live-smoke-verified). `uc.memory.upload` unchanged (below-altitude presentation). · Done when:
  `validate_coverage.py`, `validate_changes.py`, `validate_doc_links.py` all green.
- [x] 3 verify (browser smoke) — live `bootstrap:user` on `veyq.localhost` (per
  [web/CLAUDE.md](../web/CLAUDE.md)):
  - **Uploads button:** computed style transparent (`rgba(0,0,0,0)`) + borderless (`border-width 0`), 36×36 —
    identical to `.topbar-icon-button`; glyph `pi pi-list`.
  - **Cover crop:** in a 34-photo Memory, the lightbox "Als Cover festlegen" opened the cropper **over** the
    lightbox (modal z-index 1101 > lightbox 1000) showing the decrypted photo; ArrowRight did **not** navigate and
    window-Escape did **not** close the lightbox while the cropper was open (keys suspended); after Cancel the
    lightbox resumed (ArrowRight 1 → 2 of 34). · Confirmed with 2 screenshots.

## Parity ledger (per [ADR 0010](../docs/decisions/0010-native-clients-shared-contracts-not-code.md))

| Surface | Flat topbar uploads button (`pi-list`) + lightbox "Set as cover" opens the cropper | Status |
|---|---|---|
| web | `UploadStatusMenu` style/icon; `MemoryCoverDialog` `media` source; `MemoryDetail`/`MediaLightbox` wiring | ✅ this CR |
| apple / android | native gallery / cover / upload UI not built yet | ◻ **deferred (web-first; own CRs)** |

## Definition of done

The topbar uploads-tray button is flat/transparent like its theme + settings siblings and carries `pi-list`, no
longer colliding with the in-Memory Upload action's `pi-cloud-upload`. Choosing "Set as cover" in the gallery
lightbox opens the crop editor seeded on that photo (its stored crop pre-loaded when it is already the cover),
with the lightbox staying open beneath the modal and its keyboard suspended until the editor closes; the header
cover flow is unchanged. Operator-blind untouched — no contracts/backend/crypto, no new bytes. `npm run check`
+ the conformance test + the three trust-chain guards green; `uc.memory.cover` state + coverage updated; the live
smoke confirmed with screenshots.

## Retrospective

Small and clean — the cover change slotted into the editor's existing "crop an injected image URL" design by
adding one `Pick` variant, and reused the already-decrypted `thumbs` tile as the source (the exact tier the cover
renders from, so the crop is display-accurate with zero extra decrypt).

What didn't go well:

- **A latent coverage bug surfaced, not a new one.** `uc.memory.cover.yaml` had lost the `- id:` line of its
  lightbox/crop-editor criterion, so YAML duplicate-key semantics silently folded its text + coverage into
  `one-crop-everywhere` — the trust chain was green but one criterion was mislabelled and another was shadowed.
  Found only because this CR edited exactly that criterion. **Next time:** a coverage lint that rejects duplicate
  `text:`/`coverage:` keys within one criterion would catch it at authoring; worth adding when the pain repeats
  (rule-of-three).
- **Bundled two independent tweaks.** They satisfy different use-cases, so by the convention ("genuinely
  independent work = separate CRs") they could have been cr-0109 + cr-0110. Judged trivial-sibling polish and
  bundled on the "one small CR" framing — acceptable here, but the honest default for non-trivial work is
  separate CRs.
- **Glue is smoke-covered, not unit-covered.** The `MemoryDetail` wiring (seeded-open + keep-lightbox-open + keys
  suspended) is verified by the live smoke, not a unit test — mirroring how cr-0073 covered its cover smoke. A
  `MemoryDetail` mount test (the `galleryFakeStore` harness exists) would guard it deterministically; skipped as
  disproportionate for this size, and disclosed in `gaps.web`. **Next time:** if the cover glue regresses, that
  mount test is the fix.

Process: waited for the explicit "los" before coding (the memory note), German conversation with English
code/docs, reused the existing UI in-style, and used cheap text-based smoke probes before the two final
screenshots (per the memory's "cheap diagnostics before expensive ones").
