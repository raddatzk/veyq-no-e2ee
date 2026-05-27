# Memory Playlists

Memory playlists are contextual music references attached to a Memory. They are not self-hosted audio files and they do not make veyq a streaming service.

## Product Shape

A user can paste a Spotify, Apple Music, YouTube, SoundCloud or Deezer playlist link into a Memory. veyq detects the provider, stores one external link on the playlist and shows one compact playlist card in the Memory context.

One playlist can be marked as featured for the Memory Header. If no playlist is explicitly featured, veyq uses the first playlist as the header default. The header shows a compact soundtrack link and chevrons switch between playlists in the Memory. Opening playback happens through the stored provider URL in the browser or installed provider app when the platform supports it.

The Playlist tab is the management surface for adding playlists, editing title, note or URL, changing the featured playlist, opening the provider URL and deleting playlists. V1 does not embed external players.

## Permissions

Playlist visibility follows Memory visibility:

- Authenticated users who can view the Memory can view playlists.
- Public share roles `VIEWER`, `CONTRIBUTOR` and `MANAGER` can view playlists in the shared subtree.
- `UPLOADER` shares cannot view playlists.

Editing follows contribution permissions:

- Contributors and Managers can add, edit and delete playlist context.
- Authenticated users can add, edit and delete playlist context for Memories they can view inside their tenant scope.

## Data Model

`memory_playlist`

- `id`
- `public_id`
- `memory_id`
- `title`
- `note`
- `featured`
- `provider`: `SPOTIFY`, `APPLE_MUSIC`, `YOUTUBE`, `SOUNDCLOUD`, `DEEZER`
- `url`
- `external_id`
- `created_by_user_id`
- `created_by_share_identity_id`
- `created_at`
- `updated_at`

## API Surface

Private Memory APIs:

- `GET /api/{tenantName}/memories/{memoryId}/playlists`
- `POST /api/{tenantName}/memories/{memoryId}/playlists`
- `PUT /api/{tenantName}/memories/{memoryId}/playlists/{playlistId}`
- `DELETE /api/{tenantName}/memories/{memoryId}/playlists/{playlistId}`

Public share APIs mirror the same read and allowed write operations under `/api/public-shares/{shareId}/memories/{memoryId}/playlists`.

## Journal Embeds

Journals should reference internal playlist ids:

```markdown
[Playlist](veyq:playlist/{playlistId})
```

If a playlist is deleted later, the journal Markdown stays unchanged and the renderer shows an unavailable playlist embed.

The current URL-based embed form can remain for compatibility:

```markdown
[Playlist](veyq:playlist?url={encodedUrl})
```

New UI flows prefer stored playlist references over URL-only journal embeds.

## Deferred

- Multiple provider links for one conceptual playlist.
- Automatic cross-provider playlist matching.
- OAuth login to provider accounts.
- External provider embeds or playback widgets.
- Server-side metadata fetching and cache refresh.
- Self-hosted audio files.
