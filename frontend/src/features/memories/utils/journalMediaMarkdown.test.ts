import { describe, expect, it } from 'vitest'
import {
  JOURNAL_TRACK_EMBED_MAX_IDS,
  journalEmbedMarkdown,
  journalEmbedRanges
} from './journalMediaMarkdown'

describe('journalMediaMarkdown', () => {
  it('deduplicates and caps comma-separated track embed ids', () => {
    const ids = [
      'track-1',
      'track-1',
      ...Array.from({ length: 25 }, (_, index) => `track-${index + 2}`)
    ]
    const [range] = journalEmbedRanges(`::track:${ids.join(',')} Route`)

    expect(range.targetIds).toHaveLength(JOURNAL_TRACK_EMBED_MAX_IDS)
    expect(range.targetIds).toEqual(Array.from(new Set(ids)).slice(0, JOURNAL_TRACK_EMBED_MAX_IDS))
  })

  it('deduplicates and caps track ids while producing embed markdown', () => {
    const ids = [
      ' track-1 ',
      'track-1',
      ...Array.from({ length: 25 }, (_, index) => `track-${index + 2}`)
    ]

    expect(journalEmbedMarkdown('track', ids, 'Route')).toBe(
      `::track:${Array.from(new Set(ids.map((id) => id.trim())))
        .slice(0, JOURNAL_TRACK_EMBED_MAX_IDS)
        .join(',')} Route`
    )
  })
})
