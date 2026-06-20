# ADR 0033: Native UI parity from legacy iOS

## Status

Accepted

## Context

veyq now has a legacy SwiftUI iOS app, a Greenfield Apple app and an Android app. The Greenfield
native apps share the E2EE core direction, but their visual baseline must not drift independently:
users should recognize the same Memory product on iPhone, iPad, Mac, Android phones and Android
tablets. The legacy iOS app already expresses the desired Memory-first mobile experience: the visual
tokens, Memory home structure, detail/gallery ergonomics, floating actions, empty states and compact
navigation are the native UX reference.

The Apple app is universal: it targets iPhone, iPad and Mac Catalyst. Android also covers more than
phones: tablets, foldables, ChromeOS windows and split-screen need an intentional adaptive layout
rather than a stretched phone list.

## Decision

The legacy iOS app under `legacy/ios/Veyq` is the native UI/UX reference for the Greenfield native
apps. The active Apple app under `apple/App/Veyq` and the Android app under `android/app` must move
in parity from that reference:

- Shared visual tokens come from the legacy native style: accent/success/info/danger colors, soft
  gradient background, surface/elevated surface, hairline, radius and compact control rhythm.
- iPhone and Android phone use the compact legacy-style flow.
- iPad, Mac and Android tablet/foldable use adaptive large-screen layouts that preserve the same
  visual language while adding sidebars, grids and readable content widths where the extra space
  improves the workflow.
- UI changes to Memory home, Memory detail/gallery, auth/setup, settings, sharing and uploads must
  consider Apple and Android in the same slice. Intentional platform differences are documented.
- This decision does not revive Album or Explorer language and does not change E2EE boundaries. UI
  code consumes decrypted view models and delegates crypto, sealing, wrapping and slug-token work to
  the Greenfield client/domain helpers.

## Consequences

- Native UI work has a clear source of truth and a parity matrix: legacy iOS reference, active Apple
  app, Android phone and Android tablet/foldable.
- Android tablets are first-class for layout quality, but not a separate product fork.
- The Apple app can remain universal without making iPad/Mac look like a scaled phone.
- Native UI changes have a slightly larger implementation surface because Android is not deferred.
- Visual regression checks should include compact and regular-width device classes for both native
  stacks once screenshot automation is available.
