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
- Standard images, including Veyq media embeds.

Example:

```markdown
# Day 2

We walked through the old town after breakfast.

![Harbor at sunset](veyq:media/med_abc123)

![Harbor carousel](veyq:media/med_abc123,med_def456)

Favorite stop: [the evening walk](veyq:memory/mem_child123).
```

## Veyq Embed Links

Veyq-specific embeds use Markdown link targets with the `veyq:` scheme. This keeps journals readable as plain Markdown while allowing the app to render richer Memory context.

### Media

```markdown
![Caption](veyq:media/{mediaId})
```

Media embeds render an image or video figure using the visible media item. The caption comes from the Markdown image alt text. If the media is missing or not visible to the current viewer, the UI renders an unavailable placeholder.

Multiple comma-separated media ids render as a carousel:

```markdown
![Caption](veyq:media/{mediaId1},{mediaId2},{mediaId3})
```

Private Memory journals may reference media directly owned by the current Memory. Public share journals may reference media inside the shared Memory subtree.

### Child Memory

```markdown
[Title](veyq:memory/{memoryId})
```

Memory embeds render an inline Memory link/card affordance. Private Memory journals may link to descendant Memories of the current Memory. Public share journals may link to Memories inside the shared subtree.

### Child Memory Path

```markdown
[Title](veyq:memory-path/{encodedPath})
```

Path embeds resolve by child Memory names under the current allowed root. Encode path separators and special characters as needed for a URL path target.

Example:

```markdown
[Dinner](veyq:memory-path/Day%202%2FDinner)
```

### Playlist

```markdown
[Playlist](veyq:playlist?url={encodedUrl})
```

Playlist embeds are model-light in the MVP. The app renders the label and URL context directly from the query parameter. Veyq does not create playlist database rows or cache external playlist metadata yet.

### Map

```markdown
[Place](veyq:map?lat=52.5200&lng=13.4050&zoom=12)
```

Map embeds are model-light in the MVP. `lat` and `lng` are required numeric parameters. `zoom` is optional and must be numeric when present. The frontend renders a compact place context rather than a full map model.

### Track

```markdown
[Track](veyq:track?url={encodedUrl})
```

Track embeds are model-light in the MVP. The app renders the label and URL context directly from the query parameter. Veyq does not create track database rows for journal embeds yet.

## Validation Rules

The backend validates `veyq:` targets when a journal is saved:

- Unsupported `veyq:` embed types are rejected.
- Media embeds must point to visible media in the allowed Memory context. Carousel embeds validate every comma-separated media id.
- Memory embeds must point to visible Memories in the allowed Memory context.
- Playlist and track embeds must include a non-empty `url` query parameter.
- Map embeds must include numeric `lat` and `lng`; `zoom` must be numeric when provided.
- Journal Markdown must be present and may not exceed the backend size limit.

Invalid or unauthorized references fail the save request. Existing journals are not partially updated.

## Public Shares

Public shares expose journals according to share capabilities:

- Viewer, Contributor and Manager shares can read journals.
- Manager shares can update journals.
- Uploader-only shares cannot read or update journals.

Journal embeds in public shares are validated against the shared subtree so they do not reveal unrelated Memory or media identifiers.

## MVP Boundaries

The Journal MVP deliberately avoids:

- Raw HTML authoring.
- Structured journal blocks.
- Autosave.
- Version history.
- Collaborative editing.
- Optimistic conflict resolution.
- Playlist, map or track domain tables for journal-only embeds.

These can be designed later without changing the plain Markdown storage model.
