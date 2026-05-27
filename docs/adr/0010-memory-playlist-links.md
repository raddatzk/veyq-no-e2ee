# ADR 0010: Memory Playlist Links

## Status

Accepted

## Context

Memories can use music as contextual material for trips, events and smaller moments. Users should be able to attach a playlist link from supported external streaming providers without turning veyq into a music hosting service.

External music providers use different identifiers, catalogs, country availability rules, subscription models and playback APIs. A pasted playlist link binds playback to that provider. Automatic cross-provider matching would require provider APIs, provider credentials, user authorization in some cases and imperfect catalog matching.

External embeds also contact providers and add privacy and UI complexity. V1 should keep playlist context lightweight and avoid loading third-party music players inside veyq.

## Decision

veyq playlist context is represented as an internal Memory Playlist object with exactly one external provider URL in V1.

Supported initial providers:

- Spotify playlist links.
- Apple Music playlist links.
- YouTube playlist links.
- SoundCloud playlist links.
- Deezer playlist links.

veyq detects the provider and external identifier from the pasted URL. The Playlist tab shows compact cards for opening the provider URL, editing title, note or URL, marking a featured playlist and deleting playlist context. V1 does not render provider embeds or playback widgets.

Playlist visibility follows Memory visibility:

- Normal authenticated users who can view the Memory can view attached playlists.
- Public share roles `VIEWER`, `CONTRIBUTOR` and `MANAGER` can view playlists inside the shared subtree.
- `UPLOADER` shares cannot view playlists because they do not expose the Memory gallery.

Playlist editing follows existing Memory contribution rules:

- `CONTRIBUTOR` and `MANAGER` shares can create, edit and delete playlist context inside the shared subtree.
- A registered authenticated user can edit or delete playlist entries when they can still view the Memory.

Journal embeds should reference internal playlist ids once the playlist model exists:

```markdown
[Playlist](veyq:playlist/{playlistId})
```

Existing URL-based playlist journal embeds can remain as a legacy/lightweight form, but new stored playlist context should use internal references so deleted or unavailable playlists can render as unavailable without rewriting journal Markdown.

## Consequences

Users can paste the playlist link they already have, and veyq can show it as Memory context without OAuth, account linking or catalog matching.

Users who prefer another streaming service are not automatically served an equivalent playlist. A later feature can add explicit provider matching or multiple provider links if the product need is strong enough.

The V1 data model stays simple: provider, URL and external id live directly on `memory_playlist`. Future OAuth, metadata caching, ISRC matching and provider import features can extend the same Memory Playlist object without replacing the first URL-based workflow.
