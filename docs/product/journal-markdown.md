# Journal Markdown

Each Memory has exactly one Markdown journal. The journal is meant for narrative text and lightweight context embeds, not for a block-based CMS structure.

The Markdown body is stored as plain text in the Memory journal model. Clients render it with standard Markdown support and then sanitize the generated HTML before inserting it into the page. Raw HTML in journal Markdown is intentionally unsupported.

## Supported Markdown

The MVP supports normal Markdown authoring patterns:

- Paragraphs and line breaks.
- Headings.
- Emphasis and strong emphasis.
- Ordered and unordered lists.
- Blockquotes.
- Inline code and code blocks.
- Standard links.
- Standard images. veyq media uses the dedicated `::media:` token syntax below.

Example:

```markdown
# Day 2

We walked through the old town after breakfast.

::media:med_abc123 Harbor at sunset

::media:med_abc123,med_def456 Harbor carousel

Favorite stop:
::chapter:mem_child123 the evening walk
```

## veyq Embed Token Syntax

veyq-specific embeds use inline `::type:id` tokens. Text after the first whitespace is used as the caption or label. This keeps journals readable as plain Markdown while allowing the app to render richer Memory context.

Canonical forms:

```text
::media:<mediaId>
::media:<mediaId1>,<mediaId2> Optional caption
::chapter:<memoryId> Optional label
::playlist:<playlistId> Optional label
::track:<trackId> Optional label
```

The supported `type` values are `media`, `chapter`, `playlist` and `track`. Media IDs are comma-separated; whitespace starts the optional caption. For chapter, playlist and track embeds, the first whitespace starts the optional display label. Commas are reserved as media ID separators.

Legacy Markdown links and images with `veyq:` targets are not veyq embeds. They are preserved as ordinary Markdown text/link/image content and are not migrated.

### Media

```markdown
::media:{mediaId} Caption
```

Media embeds render an image or video figure using the visible media item. The caption comes from the optional text after the media id list. If the media is missing or not visible to the current viewer, the UI renders an unavailable placeholder.

Multiple comma-separated media ids render as a carousel:

```markdown
::media:{mediaId1},{mediaId2},{mediaId3} Caption
```

Private Memory journals may reference media directly owned by the current Memory. Public share journals may reference media inside the shared Memory subtree.

### Chapter

```markdown
::chapter:{chapterId} Title
```

Chapter embeds render an inline Chapter link/card affordance. Private Memory journals may link to descendant Memories of the current Memory. Public share journals may link to Memories inside the shared subtree.

### Playlist

```markdown
::playlist:{playlistId} Playlist
```

Playlist embeds reference Memory playlist objects. If a referenced playlist is deleted later, the journal Markdown is preserved and the renderer shows an unavailable embed. A veyq playlist holds one provider link in major line 1, such as Spotify, Apple Music, YouTube, SoundCloud or Deezer.

### Track

```markdown
::track:{trackId} Track
```

Track embeds reference stored GPX tracks in the current Memory context. If a referenced track is deleted later, the journal Markdown is preserved and the renderer shows an unavailable embed.

## Validation Rules

The backend validates `::` veyq tokens when a journal is saved:

- Unsupported `::` embed types are rejected.
- Media embeds must point to visible media in the allowed Memory context. Carousel embeds validate every comma-separated media id.
- Memory embeds must point to visible Memories in the allowed Memory context.
- Stored playlist embeds must point to a visible playlist in the allowed Memory context.
- Track embeds must point to a visible stored track in the allowed Memory context.
- Journal Markdown must be present and may not exceed the backend size limit.

Invalid or unauthorized references fail the save request. Existing journals are not partially updated.

## Public Shares

Public shares expose journals according to share capabilities:

- Viewer, Contributor and Manager shares can read journals.
- Manager shares can update journals.
- Uploader-only shares cannot read or update journals.

Journal embeds in public shares are validated against the shared subtree so they do not reveal unrelated Memory or media identifiers.

## Live Editing

The web Journal editor supports collaborative live sync for internal Memory editors and public Manager-share editors. veyq issues a short-lived Memory-bound editor token, and the Hocuspocus/Yjs collaboration sidecar uses that token to open the Markdown journal and synchronize connected editors. Backend validation and snapshot persistence use a separate sidecar secret so long editing sessions do not need long-lived browser tokens.

Markdown remains the canonical stored format and public API shape. The collaboration service is not allowed to bypass backend validation: every persisted snapshot must pass the same `::` embed validation rules as a REST journal update.

Live editing includes editor presence, auto-save and reconnect state. Open Yjs documents are evicted by the sidecar after they become idle; new sessions then reload Markdown from veyq. If the live collaboration service is unavailable, web clients fall back to the existing REST Markdown editor. Native iOS clients continue to read journals through the Markdown API.

## MVP Boundaries

The Journal MVP deliberately avoids:

- Raw HTML authoring.
- Structured journal blocks.
- Version history.
- Optimistic conflict resolution.
- Map domain tables for journal-only embeds.

Version history and durable offline conflict resolution can be designed later without changing the plain Markdown storage model.
