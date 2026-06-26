---
id: cr-0007
title: Import the Secret Key from the Emergency Kit (upload + QR scan)
status: planned          # planned → in-progress → done (then delete the file)
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
- [ ] **2 — web (UI + scanner).** Add two icon-buttons next to the Secret-Key field in `LoginView`'s floor
  card — **upload** (`pi-upload`, hidden `<input type=file>`) and **scan** (`pi-qrcode`). Add a small
  `KitScanner` overlay: `getUserMedia({video:{facingMode:'environment'}})`, a rAF loop decoding frames with
  `jsqr`, cancel button, and clear handling of **permission denied / no camera**. On a successful import
  (either path) fill `secretKey` from the parsed kit; a kit that doesn't parse shows a generic "couldn't read
  the kit" message (never reveals why). i18n (en+de) for the button labels + errors. **Done when:**
  `npm run check` green **and** a preview smoke shows **upload** of a kit PDF filling the Secret-Key field and
  then unlocking (the camera scan is verified manually where a camera exists — jsdom/headless has none).
- [ ] **3 — docs.** Update `uc.account.unlock.md` so QR / file import of the Secret Key is **available** (not
  deferred) for the floor path; re-run `python3 scripts/validate_coverage.py`. **Done when:** the validator
  is green and the doc no longer calls kit import deferred.

## Definition of done

On the unlock floor card a user can fill their Secret Key by **uploading** the Emergency Kit (PDF or QR
image) or **scanning** the QR with their camera, both decoded **on-device** via `parseKit`; a kit that can't
be read fails generically; typing/pasting still works. The flow stays operator-blind (nothing leaves the
device). Coverage + the unlock use-case reflect that kit import is now built (floor path). After merge, keep
this file as a record.
