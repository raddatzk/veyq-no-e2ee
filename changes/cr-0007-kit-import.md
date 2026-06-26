---
id: cr-0007
title: Import the Secret Key from the Emergency Kit (upload + QR scan)
status: done             # planned → in-progress → done (then delete the file)
satisfies: [uc.account.unlock]
adrs: [0005]
invariants: [operator-blind]
---

## Goal

On the unlock floor card, let a returning user **import their Secret Key from the Emergency Kit** instead of
typing/pasting it — via **Upload** (the kit PDF or a QR image) and **Scan** (the device camera). The kit is
already machine-readable: `downloadKitPdf` writes a `veyqkit:<b64url(JSON{v:1,email,sk})>` token both as a
**QR code** and **embedded uncompressed in the PDF metadata**, and `parseKit` already extracts it (tested,
incl. from raw PDF bytes). This realizes the **"QR / file import of the Secret Key"** that
[uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) currently lists as deferred.

## Context & constraints

- Use-case: [uc.account.unlock](../docs/features/account/use-cases/uc.account.unlock.md) — its note that QR /
  file import "belong to deferred use-cases" is what this CR closes (for the floor's Secret-Key entry).
- Reuse: `parseKit` / `kitPayload` (`web/.../emergencyKit/emergencyKit.ts`) — the parse is done and tested;
  this CR only feeds bytes into it and wires the affordances. The Secret-Key field lives in `LoginView`'s
  password-mode unlock card.
- **Operator-blind:** import is **purely client-side** — the kit is read in the browser, the Secret Key fills
  the local field; nothing is sent to the server. No invariant at risk.
- **Decisions (settled with the user):** QR decoding uses **`jsqr`** (a small pure-JS dependency → works in
  every browser, incl. Firefox/Safari) rather than the native `BarcodeDetector`; built as this tracked CR.

## Phases

- [x] **1 — web (import logic).** Added `jsqr` + `importKitFromFile(file)` in emergencyKit: text-parse via
  `parseKit` (kit PDF / any token-carrying text), else for an **image** draw to a canvas and decode the QR
  with `jsqr` (lazy-imported, like jspdf/qrcode). 2 unit tests: a PDF-shaped blob → `KitData`; a non-kit file
  → null. **Done:** `npm run check` green (58).
- [x] **2 — web (UI + scanner).** Added **in-field** trailing icons on the Secret-Key field in `LoginView`'s
  floor card — **upload** (`pi-upload`, hidden file input) and **scan** (`pi-qrcode`, shown only where
  `navigator.mediaDevices.getUserMedia` exists), sitting inside the input next to a **show/hide** toggle. To
  host all three icons consistently the field uses `InputText` + a self-managed mask toggle (`showSecret`,
  `type=password|text`) rather than PrimeVue `Password` (whose toggle can't be extended); `--secret-icons`
  scales the input's right-padding to the icon count so the masked value never runs under them. New
  `KitScanner.vue` overlay: `getUserMedia(facingMode
  environment)`, a rAF loop decoding frames with `jsqr`, cancel, and graceful handling of permission-denied /
  no-camera (falls back to upload/paste). On import (either path) `secretKey` fills from the parsed kit; an
  unreadable kit shows a generic message. i18n (en+de). **Done:** `npm run check` green (58). Preview smoke
  end-to-end: on a fresh instance, both buttons render; an uploaded kit PDF (real `veyqkit:` token) filled the
  Secret-Key field exactly and **unlocked** — no console errors. (Camera scan shares the `parseKit` decode
  path; manual-only where a camera exists.)
- [x] **3 — docs.** Noted in `uc.account.unlock.md` main flow that the Secret Key may be typed/pasted **or
  imported from the kit** (upload / scan QR), decoded on-device; added a `secret-key-import-from-kit`
  coverage criterion (→ `emergencyKit.test.ts`). (The "deferred" note lived only in the old LoginView
  comment, rewritten in cr-0005 — nothing stale remains.) **Done:** `validate_coverage.py` green (4
  use-cases).

## Definition of done

On the unlock floor card a user can fill their Secret Key by **uploading** the Emergency Kit (PDF or QR
image) or **scanning** the QR with their camera, both decoded **on-device** via `parseKit`; a kit that can't
be read fails generically; typing/pasting still works. The flow stays operator-blind (nothing leaves the
device). Coverage + the unlock use-case reflect that kit import is now built (floor path). After merge, keep
this file as a record.
