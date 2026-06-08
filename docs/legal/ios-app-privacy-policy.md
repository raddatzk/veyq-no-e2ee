# veyq iOS App Privacy Policy

This policy describes the official veyq iOS app. veyq is self-hostable memory-sharing software. The iOS app connects to a veyq instance chosen by the user and does not create a separate central veyq cloud account by itself.

## Data The App Stores Locally

The iOS app stores local state needed to connect to and use the selected veyq instance:

- instance URLs and discovery metadata
- server capability snapshots
- authenticated user and tenant bootstrap state
- native session tokens in iOS Keychain
- local upload queue state for selected photos and videos

Native session tokens are stored in iOS Keychain. Upload queue state is used to retry or finish uploads selected by the user.

## Data Sent To A veyq Instance

The app sends data only to the user-selected veyq instance when needed for app workflows. This can include:

- account identifiers used by that instance for login and account display
- tenant and Memory identifiers
- Memory navigation and media requests
- photos and videos selected by the user for upload
- metadata already present in selected media, which may include capture dates or location metadata

The app does not upload photos or videos unless the user selects them for upload.

## Self-Hosted Instances

veyq instances can be self-hosted. The operator of a self-hosted instance controls that instance's retention, backups, server logs and access policies. The official iOS app does not control how a self-hosted instance stores or processes data after the app sends it there.

## Tracking And Advertising

The app does not use data for third-party advertising, cross-app tracking or data broker purposes.

Mobile V1 does not include crash reporting, analytics or push notification infrastructure. If those features are added later, this policy and the App Store privacy answers must be updated before release.

## External Services

The app primarily communicates with the selected veyq instance.

If a user opens an external playlist link, the device may contact the external provider. If map tile features are used, optional map tile behavior is governed by the selected instance and is described in [Privacy Notes](privacy.md).

## Contact

Privacy and TestFlight feedback for the official iOS app should be sent to:

```text
info@veyq.app
```
