import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JournalBody from './JournalBody.vue'
import type { GalleryTile } from '../../features/gallery/gallery'
import type { Track } from '../../features/memory/track/track'

const tile = (id: string): GalleryTile =>
  ({ id, url: `blob:${id}`, aspectRatio: 1, mimeType: 'image/jpeg', takenAt: '2026-01-01T00:00:00Z', highlighted: false, preview: {} as never, original: {} as never })
const track = (id: string): Track => ({ id, name: `Route ${id}`, points: [{ lat: 1, lng: 2 }, { lat: 3, lng: 4 }] })

function mountBody(markdown: string, opts: Partial<{ tiles: GalleryTile[]; tracks: Track[]; chapters: Record<string, { name: string }> }> = {}) {
  return mount(JournalBody, {
    props: {
      markdown,
      tileById: Object.fromEntries((opts.tiles ?? []).map((t) => [t.id, t])),
      tracksById: Object.fromEntries((opts.tracks ?? []).map((t) => [t.id, t])),
      chapterById: opts.chapters ?? {},
    },
  })
}

describe('JournalBody', () => {
  it('renders surrounding Markdown text', () => {
    const w = mountBody('# Title\n\nSome **bold** prose.')
    expect(w.find('h1').text()).toBe('Title')
    expect(w.find('strong').text()).toBe('bold')
  })

  it('renders a single media photo and emits openMedia on click', async () => {
    const w = mountBody('::media:a Sunset', { tiles: [tile('a')] })
    const img = w.find('.jcar-img img')
    expect(img.attributes('src')).toBe('blob:a')
    expect(w.find('.journal-embed-caption').text()).toBe('Sunset')
    await w.find('.jcar-img').trigger('click')
    expect(w.emitted('openMedia')?.[0]?.[0]).toMatchObject({ id: 'a' })
  })

  it('renders a carousel with one dot per photo for multiple media ids', () => {
    const w = mountBody('::media:a,b,c', { tiles: [tile('a'), tile('b'), tile('c')] })
    expect(w.findAll('.jcar-dot')).toHaveLength(3)
    expect(w.find('.jcar-next').exists()).toBe(true)
  })

  it('renders a chapter link and emits openChapter on click', async () => {
    const w = mountBody('::chapter:ch1 Day one', { chapters: { ch1: { name: 'Chapter X' } } })
    const link = w.find('.journal-chapter-link')
    expect(link.text()).toContain('Day one') // label overrides the chapter name
    await link.trigger('click')
    expect(w.emitted('openChapter')?.[0]?.[0]).toBe('ch1')
  })

  it('falls back to the chapter name when no label is given', () => {
    const w = mountBody('::chapter:ch1', { chapters: { ch1: { name: 'Chapter X' } } })
    expect(w.find('.journal-chapter-link').text()).toContain('Chapter X')
  })

  it('renders a track embed block with distance stats', () => {
    const w = mountBody('::track:t1 Hike', { tracks: [track('t1')] })
    expect(w.find('.journal-embed-track').exists()).toBe(true)
    expect(w.find('.journal-track-stats').text()).toContain('km') // distance from the two points
  })

  it('shows a missing-content note for an unresolved media id', () => {
    const w = mountBody('::media:gone', {})
    expect(w.find('.journal-embed-missing').exists()).toBe(true)
    expect(w.find('.jcar-img').exists()).toBe(false)
  })
})
