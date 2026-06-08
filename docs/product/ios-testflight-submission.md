# iOS TestFlight Submission

This document is the source text for the first official veyq iOS TestFlight submission. It keeps App Store Connect metadata, beta review notes, privacy answers and export-compliance assumptions together so they can be copied into App Store Connect without reconstructing intent from scattered notes.

## App Information

- App name: `veyq`
- Bundle ID: `app.veyq.ios`
- SKU: `veyq-ios`
- Primary language: English
- Additional language: German, once localized App Store metadata is ready.
- Category: Photo & Video
- Alternative category if Apple review feedback pushes back: Lifestyle
- Age rating expectation: no unrestricted web access, no gambling, no ads, no commerce. User-generated Memory media can contain personal photos and videos; the first beta should use trusted testers and non-sensitive sample content.

## Versioning

iOS uses its own major-plus-build line, separate from backend `major.build` versions:

- iOS app major source: `ios/VERSION`
- `CFBundleShortVersionString`: app major, for example `1`
- `CFBundleVersion`: GitHub Actions run number, for example `42`

Increment `ios/VERSION` when the app drops support for a server capability that has been removed from maintained server releases for at least one year.

## TestFlight Beta Information

Beta app description:

```text
veyq is a native iOS app for connecting to a veyq instance, browsing curated Memories, viewing Memory media and uploading photos or videos into a Memory.
```

What to test:

```text
Please test connecting to a veyq instance, browser-based login handoff, tenant selection, Memory navigation, Memory detail loading, media gallery viewing, photo/video uploads through the upload queue, retry behavior for failed uploads and logout.

Use only non-sensitive test Memories and sample photos or videos for this beta.
```

Feedback email:

```text
info@veyq.app
```

External testing notes:

```text
This beta is intended for trusted testers validating the first native iOS workflow. veyq is self-hostable memory-sharing software. The app connects to a user-selected veyq instance and does not provide a separate central cloud account by itself.
```

Beta review notes:

```text
The app connects to a veyq test instance and uses browser-based login handoff for authentication. After login, testers can select a tenant, browse Memories, view Memory media and upload photos or videos to a Memory.

If a demo account is needed, use the provided test instance and account credentials. The test instance contains non-sensitive sample Memories only.
```

Open fields before external TestFlight review:

- Confirm `info@veyq.app` exists and is monitored.
- Add the App Store Connect beta review contact name, phone number and email.
- Provide a demo instance URL and demo account only if Apple cannot use a normal reviewer login flow.

## App Privacy Answers

Tracking:

- Does this app track users? No.
- Third-party advertising: No.
- Cross-app or cross-site tracking: No.
- Data broker use: No.

Data linked to the user:

- Contact Info: email address or account identifier used by the selected veyq instance for login and account display.
- User Content: Memories, journal text, photos and videos uploaded by the user to the selected veyq instance.
- Identifiers: user id, tenant id and native session identifiers used to authenticate API requests to the selected instance.

Data that may be collected depending on user content:

- Location: photo/video metadata may include location if the selected media already contains it. veyq does not add background location tracking in mobile V1.

Data not collected by mobile V1:

- Purchases
- Financial Info
- Health and Fitness
- Sensitive Info beyond whatever the user voluntarily includes in Memory content
- Contacts address book
- Browsing History
- Search History
- Diagnostics, unless crash reporting or analytics is added later

Local device storage:

- instance URLs and discovery metadata
- server capability snapshots
- authenticated user and tenant bootstrap state
- native session tokens in iOS Keychain
- local upload queue state for selected photos and videos

Data destination:

- Uploaded photos and videos are sent only to the user-selected veyq instance.
- Self-hosted operators control retention, backups and server logs for their own instance.
- The official app does not sell data or use it for advertising.

External services:

- The app talks to selected veyq instance APIs.
- User-opened external playlist links may open provider URLs.
- Optional map tile behavior is documented in [Privacy Notes](../legal/privacy.md).

## Export Compliance

Expected App Store Connect answer:

```text
The app uses standard Apple platform encryption and transport security, including HTTPS/TLS for network communication and iOS Keychain for native session storage. The app does not implement custom cryptographic algorithms.
```

If App Store Connect asks whether the app uses encryption, answer yes for standard platform encryption. If it offers the standard exemption path for apps using only HTTPS/TLS and platform-provided cryptography, use that exemption and add the generated export compliance key to the Xcode project only after Apple provides it.

## Privacy Policy

Use the public privacy policy URL once published:

```text
https://veyq.app/privacy
```

Until that page is live, [Privacy Notes](../legal/privacy.md) and [iOS App Privacy Policy](../legal/ios-app-privacy-policy.md) are the source text.
