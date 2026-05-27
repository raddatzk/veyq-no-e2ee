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

veyq does not embed external music players in V1. Opening a playlist happens through a normal external provider link, so the browser contacts the provider only when a user chooses to open that link.
