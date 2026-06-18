import { describe, expect, it } from 'vitest'
import { journalEmbedMarkdown, normalizeTrackIds, parseJournalEmbeds, splitJournalBlocks } from './journalEmbed'

describe('parseJournalEmbeds', () => {
  it('parses a single media token with a label', () => {
    const e = parseJournalEmbeds('Look: ::media:abc Sunset over the bay')
    expect(e).toHaveLength(1)
    expect(e[0]).toMatchObject({ kind: 'media', ids: ['abc'], label: 'Sunset over the bay' })
  })

  it('parses comma-separated media ids (a carousel) without a label', () => {
    const e = parseJournalEmbeds('::media:a,b,c')
    expect(e[0]).toMatchObject({ kind: 'media', ids: ['a', 'b', 'c'] })
    expect(e[0].label).toBeUndefined()
  })

  it('keeps only the first id for a chapter', () => {
    const e = parseJournalEmbeds('::chapter:ch1,ignored Day one')
    expect(e[0]).toMatchObject({ kind: 'chapter', ids: ['ch1'], label: 'Day one' })
  })

  it('dedupes and caps track ids', () => {
    const e = parseJournalEmbeds('::track:t1,t1,t2 Hike')
    expect(e[0]).toMatchObject({ kind: 'track', ids: ['t1', 't2'], label: 'Hike' })
  })

  it('parses two back-to-back tokens', () => {
    const e = parseJournalEmbeds('::media:a Photo::track:t1 Route')
    expect(e.map((x) => x.kind)).toEqual(['media', 'track'])
    expect(e[0]).toMatchObject({ ids: ['a'], label: 'Photo' })
    expect(e[1]).toMatchObject({ ids: ['t1'], label: 'Route' })
  })

  it('ends a token at a newline', () => {
    const e = parseJournalEmbeds('::media:a A caption\nnext paragraph')
    expect(e[0].label).toBe('A caption')
    expect(e[0].end).toBe('::media:a A caption'.length)
  })

  it('ignores an empty target', () => {
    expect(parseJournalEmbeds('::media: nothing')).toHaveLength(0)
  })

  it('parses a playlist token and keeps only the first id', () => {
    const e = parseJournalEmbeds('::playlist:pl1,ignored My mix')
    expect(e[0]).toMatchObject({ kind: 'playlist', ids: ['pl1'], label: 'My mix' })
  })
})

describe('journalEmbedMarkdown', () => {
  it('round-trips a labelled media embed', () => {
    const md = journalEmbedMarkdown('media', ['a', 'b'], 'Trip')
    expect(md).toBe('::media:a,b Trip')
    expect(parseJournalEmbeds(md)[0]).toMatchObject({ kind: 'media', ids: ['a', 'b'], label: 'Trip' })
  })

  it('omits the suffix when there is no label', () => {
    expect(journalEmbedMarkdown('track', ['t1'])).toBe('::track:t1')
  })

  it('normalizes track ids on serialize', () => {
    expect(journalEmbedMarkdown('track', [' t1 ', 't1', 't2'])).toBe('::track:t1,t2')
  })
})

describe('splitJournalBlocks', () => {
  it('splits text and embeds in source order', () => {
    const blocks = splitJournalBlocks('Intro\n\n::media:a Photo\n\nOutro')
    expect(blocks.map((b) => b.type)).toEqual(['markdown', 'embed', 'markdown'])
    expect(blocks[0]).toMatchObject({ type: 'markdown', markdown: 'Intro\n\n' })
    expect(blocks[1]).toMatchObject({ type: 'embed' })
    expect(blocks[2]).toMatchObject({ type: 'markdown', markdown: '\n\nOutro' })
  })

  it('returns a single markdown block when there are no embeds', () => {
    const blocks = splitJournalBlocks('Just text')
    expect(blocks).toEqual([{ type: 'markdown', markdown: 'Just text' }])
  })

  it('handles an embed at the very start', () => {
    const blocks = splitJournalBlocks('::track:t1 Route')
    expect(blocks).toHaveLength(1)
    expect(blocks[0].type).toBe('embed')
  })
})

describe('normalizeTrackIds', () => {
  it('dedupes, trims, drops empties, and caps at the max', () => {
    const many = Array.from({ length: 25 }, (_, i) => `t${i}`)
    expect(normalizeTrackIds(['a', ' a ', '', 'b'])).toEqual(['a', 'b'])
    expect(normalizeTrackIds(many)).toHaveLength(20)
  })
})
