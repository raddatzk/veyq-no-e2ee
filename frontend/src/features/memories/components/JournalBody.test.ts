import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JournalBody from './JournalBody.vue'
import type { GalleryTile } from '../../../features/gallery/gallery'
import type { Track } from '../../../features/memory/track/track'
import type { Playlist } from '../../../features/memory/playlist/playlist'

// JournalTrackMap pulls in Leaflet (which needs a real layout engine); stub it so the embed renders
// deterministically and we can assert the block structure + per-track stats without a map canvas.
const stubs = { JournalTrackMap: { template: '<div class="journal-track-map-stub" />' } }

const tile = (id: string): GalleryTile =>
  ({ id, url: `blob:${id}`, aspectRatio: 1, mimeType: 'image/jpeg', takenAt: '2026-01-01T00:00:00Z', highlighted: false, preview: {} as never, original: {} as never })
const track = (id: string): Track => ({ id, name: `Route ${id}`, points: [{ lat: 1, lng: 2 }, { lat: 3, lng: 4 }] })
const playlist = (id: string): Playlist => ({ id, featured: false, provider: 'SPOTIFY', url: 'https://open.spotify.com/playlist/x', title: 'Roadtrip mix' })

function mountBody(
  markdown: string,
  opts: Partial<{ tiles: GalleryTile[]; tracks: Track[]; chapters: Record<string, { name: string }>; playlists: Playlist[] }> = {},
) {
  return mount(JournalBody, {
    global: { stubs },
    props: {
      markdown,
      tileById: Object.fromEntries((opts.tiles ?? []).map((t) => [t.id, t])),
      tracksById: Object.fromEntries((opts.tracks ?? []).map((t) => [t.id, t])),
      chapterById: opts.chapters ?? {},
      playlistById: Object.fromEntries((opts.playlists ?? []).map((p) => [p.id, p])),
    },
  })
}

describe('JournalBody', () => {
  it('renders surrounding Markdown text around embeds', () => {
    const w = mountBody('# Title\n\nSome **bold** prose.')
    expect(w.find('h1').text()).toBe('Title')
    expect(w.find('strong').text()).toBe('bold')
  })

  it('resolves a single ::media embed to one photo and emits openMedia on click', async () => {
    const w = mountBody('::media:a Sunset over the bay', { tiles: [tile('a')] })
    const img = w.find('.jcar-img img')
    expect(img.attributes('src')).toBe('blob:a')
    expect(w.find('.journal-embed-caption').text()).toBe('Sunset over the bay')
    // a single photo carousel has no dots/nav
    expect(w.findAll('.jcar-dot')).toHaveLength(0)
    await w.find('.jcar-img').trigger('click')
    expect(w.emitted('openMedia')?.[0]?.[0]).toMatchObject({ id: 'a' })
  })

  it('resolves a multi-id ::media embed to a carousel with one dot per photo', () => {
    const w = mountBody('::media:a,b,c', { tiles: [tile('a'), tile('b'), tile('c')] })
    expect(w.findAll('.jcar-dot')).toHaveLength(3)
    expect(w.find('.jcar-next').exists()).toBe(true)
  })

  it('resolves a ::track embed to a track block with distance stats', () => {
    const w = mountBody('::track:t1 Hike', { tracks: [track('t1')] })
    expect(w.find('.journal-embed-track').exists()).toBe(true)
    expect(w.find('.journal-track-map-stub').exists()).toBe(true)
    // distance derived from the two points → a "km" stat is shown
    expect(w.find('.journal-track-stats').text()).toContain('km')
    expect(w.find('.journal-track-stats').text()).toContain('Route t1')
  })

  it('resolves a ::chapter embed to a link and emits openChapter on click', async () => {
    const w = mountBody('::chapter:ch1 Day one', { chapters: { ch1: { name: 'Chapter X' } } })
    const link = w.find('.journal-chapter-link')
    expect(link.text()).toContain('Day one') // label overrides the chapter name
    await link.trigger('click')
    expect(w.emitted('openChapter')?.[0]?.[0]).toBe('ch1')
  })

  it('falls back to the chapter name when a ::chapter embed has no label', () => {
    const w = mountBody('::chapter:ch1', { chapters: { ch1: { name: 'Chapter X' } } })
    expect(w.find('.journal-chapter-link').text()).toContain('Chapter X')
  })

  it('resolves a ::playlist embed to a provider link card', () => {
    const w = mountBody('::playlist:p1 Roadtrip', { playlists: [playlist('p1')] })
    const link = w.find('.journal-playlist-link')
    expect(link.attributes('href')).toBe('https://open.spotify.com/playlist/x')
    expect(link.text()).toContain('Roadtrip') // label overrides the title
  })

  it('degrades an unresolved embed id to a placeholder', () => {
    const w = mountBody('::media:gone Missing photo', {})
    expect(w.find('.journal-embed-missing').exists()).toBe(true)
    expect(w.find('.jcar-img').exists()).toBe(false)
    // the caption still renders alongside the placeholder
    expect(w.find('.journal-embed-caption').text()).toBe('Missing photo')
  })
})
