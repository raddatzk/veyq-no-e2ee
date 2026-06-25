// Journal rich-embed tokens — `::media:id1,id2 optional label`, `::track:id1,id2 label`, `::chapter:id label`,
// `::playlist:id label`. The token lives inline in the journal Markdown (sealed like the rest of the body, so the
// server stays blind); the renderer resolves each token to real media thumbnails, an inline track map, a chapter
// link, or a streaming-playlist card. The wire format matches the legacy editor so old journals keep rendering. A
// token ends at a newline, end-of-text, or the start of the next token, so several can sit back-to-back.

export const JOURNAL_TRACK_EMBED_MAX_IDS = 20

export type JournalEmbedKind = 'media' | 'track' | 'chapter' | 'playlist'

/** A parsed embed token and its position in the source Markdown. `ids` is always non-empty. */
export type JournalEmbed = {
  start: number
  end: number
  kind: JournalEmbedKind
  ids: string[]
  label?: string
}

/** The journal body as an ordered run of Markdown text segments and embed tokens. */
export type JournalBlock =
  | { type: 'markdown'; markdown: string }
  | { type: 'embed'; embed: JournalEmbed }

const EMBED_RE = /::(media|track|chapter|playlist):([^\s\n]+)(?:[ \t]+([^\n]*?))?(?=\n|$|::(?:media|track|chapter|playlist):)/g

/** Kinds that reference a single entity (the parser keeps only the first id). */
const SINGLE_ID_KINDS = new Set<JournalEmbedKind>(['chapter', 'playlist'])

/** Dedupe + trim track ids and cap them (one embed can carry several tracks onto one map). */
export function normalizeTrackIds(ids: string[]): string[] {
  return Array.from(new Set(ids.map((id) => id.trim()).filter(Boolean))).slice(0, JOURNAL_TRACK_EMBED_MAX_IDS)
}

/** Find every embed token in the journal Markdown, in source order. */
export function parseJournalEmbeds(markdown: string): JournalEmbed[] {
  return Array.from(markdown.matchAll(EMBED_RE))
    .map((m) => {
      const kind = m[1] as JournalEmbedKind
      const raw = m[2].split(',').map((s) => s.trim()).filter(Boolean)
      const ids = kind === 'track' ? normalizeTrackIds(raw) : SINGLE_ID_KINDS.has(kind) ? raw.slice(0, 1) : raw
      const start = m.index ?? 0
      return { start, end: start + m[0].length, kind, ids, label: m[3]?.trim() || undefined }
    })
    .filter((e) => e.ids.length > 0)
}

/** Serialize an embed back to its token form (used by the editor pickers when inserting/editing). */
export function journalEmbedMarkdown(kind: JournalEmbedKind, ids: string[], label?: string): string {
  const norm = kind === 'track' ? normalizeTrackIds(ids) : SINGLE_ID_KINDS.has(kind) ? ids.slice(0, 1) : ids.map((s) => s.trim()).filter(Boolean)
  const suffix = label?.trim() ? ` ${label.trim()}` : ''
  return `::${kind}:${norm.join(',')}${suffix}`
}

/** Split the journal into an ordered list of Markdown segments and embed blocks for block-based rendering. */
export function splitJournalBlocks(markdown: string): JournalBlock[] {
  const blocks: JournalBlock[] = []
  let cursor = 0
  for (const embed of parseJournalEmbeds(markdown)) {
    if (embed.start > cursor) blocks.push({ type: 'markdown', markdown: markdown.slice(cursor, embed.start) })
    blocks.push({ type: 'embed', embed })
    cursor = embed.end
  }
  if (cursor < markdown.length) blocks.push({ type: 'markdown', markdown: markdown.slice(cursor) })
  return blocks
}
