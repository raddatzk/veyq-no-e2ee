export const JOURNAL_TRACK_EMBED_MAX_IDS = 20

export type MediaInsertRange = {
  start: number
  end: number
  caption?: string
  mediaIds?: string[]
}

export type JournalEmbedKind = 'media' | 'chapter' | 'playlist' | 'track'

export type JournalEmbedRange = {
  start: number
  end: number
  kind: JournalEmbedKind
  label?: string
  targetId?: string
  targetIds?: string[]
  mediaIds?: string[]
}

export type JournalEmbedTypeRange = {
  start: number
  end: number
}

export function mediaTriggerRange(markdown: string, position: number): MediaInsertRange | null {
  const beforeCursor = markdown.slice(0, position)
  const match = beforeCursor.match(/::media:$/)
  if (!match) return null
  return {
    start: position - match[0].length,
    end: position
  }
}

export function mediaTagRangeAtPosition(markdown: string, position: number): MediaInsertRange | null {
  for (const embed of journalEmbedRanges(markdown)) {
    if (embed.kind !== 'media') continue
    if (position < embed.start || position > embed.end) continue
    const mediaIds = embed.mediaIds ?? []
    if (mediaIds.length === 0) return null
    return {
      start: embed.start,
      end: embed.end,
      caption: embed.label,
      mediaIds
    }
  }
  return null
}

export function journalEmbedTriggerRange(markdown: string, position: number): JournalEmbedRange | null {
  const beforeCursor = markdown.slice(0, position)
  const match = beforeCursor.match(/::(chapter|playlist|track):$/)
  if (!match) return null
  return {
    start: position - match[0].length,
    end: position,
    kind: match[1] as JournalEmbedKind
  }
}

export function journalEmbedRangeAtPosition(markdown: string, position: number): JournalEmbedRange | null {
  for (const embed of journalEmbedRanges(markdown)) {
    if (embed.kind === 'media') continue
    if (position >= embed.start && position <= embed.end) return embed
  }
  return null
}

export function journalEmbedTypeTriggerRange(markdown: string, position: number): JournalEmbedTypeRange | null {
  const beforeCursor = markdown.slice(0, position)
  const match = beforeCursor.match(/::$/)
  if (!match) return null
  return {
    start: position - match[0].length,
    end: position
  }
}

export function journalEmbedRanges(markdown: string): JournalEmbedRange[] {
  const embed = /::(media|chapter|playlist|track):([^\s\n]+)(?:[ \t]+([^\n]*?))?(?=\n|$|::(?:media|chapter|playlist|track):)/g
  return Array.from(markdown.matchAll(embed))
    .map((match) => {
      const kind = match[1] as JournalEmbedKind
      const target = match[2].trim()
      const label = match[3]?.trim() || undefined
      const start = match.index ?? 0
      const end = start + match[0].length
      if (kind === 'media') {
        const mediaIds = target.split(',').map((id) => id.trim()).filter(Boolean)
        return { start, end, kind, label, targetId: mediaIds[0], targetIds: mediaIds, mediaIds }
      }
      if (kind === 'track') {
        const trackIds = normalizeJournalTrackIds(target.split(','))
        return { start, end, kind, label, targetId: trackIds[0], targetIds: trackIds }
      }
      return { start, end, kind, label, targetId: target, targetIds: [target] }
    })
    .filter((range) => range.kind !== 'media' || (range.mediaIds?.length ?? 0) > 0)
    .filter((range) => range.kind === 'media' || Boolean(range.targetId))
}

export function journalEmbedMarkdown(kind: JournalEmbedKind, ids: string[], label?: string): string {
  const target = (kind === 'track' ? normalizeJournalTrackIds(ids) : ids).join(',')
  const suffix = label?.trim() ? ` ${label.trim()}` : ''
  return `::${kind}:${target}${suffix}`
}

export function normalizeJournalTrackIds(ids: string[]): string[] {
  return Array.from(new Set(ids.map((id) => id.trim()).filter(Boolean))).slice(
    0,
    JOURNAL_TRACK_EMBED_MAX_IDS
  )
}
