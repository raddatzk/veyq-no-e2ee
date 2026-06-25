# Privacy Notes

veyq is designed for self-hosted private memory sharing. This document describes privacy-relevant product behavior that operators should surface to their users before running a public or semi-public instance.

## Authentication Cookies

veyq uses technically necessary `HttpOnly` cookies for normal login sessions and public-share sessions. These cookies keep users and share visitors authenticated, rotate through refresh flows and are cleared on logout where applicable.

Public-share cookies are scoped to `/api/public-shares` and are used only to open and interact with the shared Memory according to the share role. veyq does not use these cookies for advertising, analytics or cross-site tracking.

## OpenStreetMap Map Tiles

The Leaflet map code runs locally in the veyq frontend. By default, veyq does not load external map tiles.

When a user enables OpenStreetMap tiles, the browser stores this choice in local storage under `veyq:osm-tiles-enabled`. The choice is global for veyq in that browser, not scoped to a tenant, Memory, share link or server-side user account.

After this opt-in, map views may request tile images from OpenStreetMap servers. Those requests can disclose technical connection data such as the visitor IP address, browser metadata and the visible map area. The user can disable OpenStreetMap tiles again from the veyq settings page, directly on the map, or from the privacy panel shown on public share pages.

Operators who need stricter privacy should keep OpenStreetMap tiles disabled and avoid adding third-party analytics, tracking pixels or externally hosted media to their deployment.

## External Playlist Links

veyq stores Memory playlist links as external references. It does not proxy Spotify, Apple Music, YouTube, SoundCloud or Deezer playback and does not host music files.

veyq does not embed external music players in major line 1. Opening a playlist happens through a normal external provider link, so the browser contacts the provider only when a user chooses to open that link.

## Native iOS App

The official iOS app connects to a user-selected veyq instance. It does not provide a separate central veyq cloud account by itself.

The App Store privacy source text for the official iOS app is maintained in [veyq iOS App Privacy Policy](ios-app-privacy-policy.md).

The app stores local connection and session state needed to use that selected instance:

- instance URLs and discovery metadata
- server capability snapshots
- authenticated user and tenant bootstrap state
- native session tokens in iOS Keychain
- local upload queue state for selected photos and videos

The app sends selected photos and videos only to the connected veyq instance. Media metadata, including dates and location metadata that may already be embedded in selected files, can become part of the Memory media record on that instance. Self-hosted operators control the retention, backup and log behavior of their own deployment.

The app does not use data for third-party advertising, cross-app tracking or data broker purposes. Crash reporting, analytics and push notification infrastructure are not part of mobile V1; if they are added later, this document and the App Store privacy answers must be updated before release.
