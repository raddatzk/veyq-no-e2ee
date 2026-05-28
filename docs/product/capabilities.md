# veyq Capabilities

veyq capabilities are stable identifiers for product abilities that the server can expose to users and, later, to apps. They are not permissions and they do not replace API versioning. A capability says "this server knows how to do this thing".

## Current Baseline Capabilities

The V1 baseline capability IDs, versions and audiences are maintained in `CapabilityRegistry` and exposed through server settings. User-facing titles and descriptions are localized in the frontend i18n messages.

- `server.setup`: self-hosted Docker Compose setup, first admin creation and first Memory space setup.
- `auth.passkeys`: passkey setup, login and invited user onboarding.
- `spaces.tenant-group-personal`: tenant, group and personal Memory spaces.
- `memory.core`: Memory creation, nested Memories, covers, date ranges and favorites.
- `memory.media`: photo/video upload, previews, posters, metadata and original video playback.
- `memory.media-resumable-upload`: chunked resumable media uploads with progress and cleanup.
- `memory.media-upload-eta`: client-side remaining-time estimates for active media uploads.
- `memory.media-zip-download`: ZIP downloads for visible Memory media.
- `memory.journal-markdown`: Markdown journals and veyq embeds.
- `memory.maps`: GPS media maps with browser opt-in for external map tiles.
- `memory.tracks`: GPX track upload, labeling, listing, download and embeds.
- `memory.playlists`: external playlist links and journal playlist embeds.
- `memory.public-sharing`: public Memory shares with Viewer, Contributor, Manager and Uploader roles.
- `memory.public-uploader-links`: upload-only public links.
- `server.backup-upgrade`: helper-script backup and upgrade workflow.
- `server.auto-updates`: opt-in major-channel auto-updates for the application container.

## Maintenance Rules

- Capability IDs are lowercase dotted identifiers and should remain stable once released.
- Add a capability only for a meaningful user- or operator-visible ability, not for an implementation detail.
- Keep the backend registry language-neutral: it owns only `id`, `introducedInVersion`, `audience` and notification policy.
- Add frontend i18n entries for every shipped capability ID. Use the ID with non-alphanumeric characters replaced by underscores, for example `memory.playlists` -> `capabilities.items.memory_playlists`.
- Keep baseline V1 capabilities with `introducedInVersion = "1.0"` and `announceAsNew = false` so existing features are discoverable without spamming users.
- For a new additive feature, add a new capability with the version it first ships in. Set `announceAsNew = true` only when users should see a "new capability available" notice.
- For ServerOwner-only abilities, use `SERVER_OWNER`; for normal product abilities, use `ALL_USERS`.
- Do not remove or rename an existing capability during a major line. If a capability is replaced, keep the old ID documented and add a new ID for the replacement.
- Future app compatibility should depend on capability IDs, not on UI labels or the container tag alone.

## Notification Behavior

Capabilities and capability notifications are intentionally separate:

- Server settings can list all capabilities visible to the current user.
- User notifications are created only from capabilities explicitly marked as announceable.
- Each user dismisses capability notifications independently.
- ServerOwner update failures are separate server-wide notifications and are not modeled as user capabilities.
