# veyq Major Line 1 Scope

This document defines the current major line 1 product target. veyq does not treat "v1" as a single fixed release; the major number identifies a compatibility line and is incremented only for breaking changes.

## Product Promise

Major line 1 lets a self-hoster run veyq, create Memories, add photos, videos and journal text, organize related Memories as chapters, and share Memory subtrees with clear public roles.

The product should feel like a private Memory archive with beautiful sharing. It is not intended to be a public cloud service, social network, generic file manager or complete travel-mapping product.

## Included In Major Line 1

- Initial server setup, local accounts, passkey support and tenant creation.
- Tenant, group and personal Memory spaces with tenant switching.
- Memory CRUD, nested chapters, favorites and folder-based organizational navigation.
- Memory gallery, cover selection, date ranges, metadata display and timestamp correction.
- Markdown journals with veyq embeds for visible media, chapters, maps, playlists and tracks.
- Media map for uploaded photos and videos that contain GPS metadata. External OpenStreetMap tiles are disabled by default and require a global browser opt-in with opt-out on maps, public shares and settings.
- GPX track upload, activity type labeling, track listing, original GPX download and journal track embeds.
- Memory playlists with one Spotify, Apple Music, YouTube, SoundCloud or Deezer provider link, external opening, compact management cards and journal playlist embeds.
- Media upload with resumable chunk sessions, progress UI, cancellation and cleanup of unfinished sessions.
- Original photo/video storage with JPEG previews and video poster generation through ffmpeg.
- Original-only video playback in the browser lightbox, using HTTP range requests.
- ZIP downloads for direct Memory media and visible descendant media.
- Public Memory shares with Viewer, Contributor, Manager and Uploader roles, optional 10-character share passwords and anonymous uploader attribution.
- Tenant user invitations, tenant role management and group management.
- Docker Compose self-hosting with Traefik, MariaDB, local filesystem media storage, backup guidance and image-based upgrades.

## Explicitly Deferred

- Video derivatives, H264 fallback files, HLS/DASH and adaptive quality selection.
- Advanced map and track features such as combined media/track maps, route styling, map clustering, manual geotagging and track provider imports.
- Weather, playlist metadata caching, automatic playlist matching, provider OAuth playback and person models beyond tenant-user Memory participants.
- Face recognition, semantic search, classification, cross-Memory person graphs and public participant pages.
- Supported app-store mobile releases, push notifications, federation, remote share following and offline sync. Native mobile apps may exist as experimental/pre-release clients during major line 1.
- End-to-end encryption, managed hosting features and central veyq services.
- Comments, social reactions, manual asset sorting and cross-Memory asset moves.
- Demo seed Memories. Major line 1 relies on clear empty states instead.

## Acceptance Checklist

- A fresh Docker Compose install reaches setup through the configured HTTPS host.
- The official runtime image contains ffmpeg and can generate video poster previews.
- A user can create a tenant, invite another user, create a group and switch spaces.
- A user can create a Memory, upload photos/videos, set a cover, edit the journal, create a chapter and download a ZIP.
- Viewer, Contributor, Manager and Uploader shares work from a fresh browser session without exposing unauthorized media.
- Upload-only shares show a focused upload flow and do not expose the gallery or other contributors.
- Backups cover both MariaDB data and the media volume before upgrades.
- `backend ./gradlew check`, `frontend npm run check` and relevant Playwright smoke tests pass before publishing a major-line build.
