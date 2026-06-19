import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import MemoryContent from './MemoryContent.vue'
import type { GalleryTile } from '../../features/gallery/gallery'

const tile = (id: string): GalleryTile =>
  ({ id, url: `blob:${id}`, aspectRatio: 1, mimeType: 'image/jpeg', takenAt: '2026-01-01T00:00:00Z', highlighted: false, preview: {} as never, original: {} as never })

function makeProps(over: Record<string, unknown> = {}) {
  return {
    rootNodeId: 'root',
    rootName: 'My Memory',
    loadTiles: vi.fn(async () => [tile('a')]),
    loadJournalMarkdown: vi.fn(async () => '::media:a A caption'),
    loadTracks: vi.fn(async () => []),
    loadChildNodes: vi.fn(async () => [{ id: 'ch1', type: 'CHAPTER' as const, name: 'Chapter 1' }]),
    resolveChapterNames: vi.fn(async () => ({})),
    loadPreview: vi.fn(async () => 'blob:preview'),
    loadOriginal: vi.fn(async () => 'blob:original'),
    ...over,
  }
}

describe('MemoryContent', () => {
  it('renders the three Legacy tabs, the root title, and loads the root gallery', async () => {
    const props = makeProps()
    const w = mount(MemoryContent, { props })
    await flushPromises()
    expect(w.find('.detail-title').text()).toBe('My Memory')
    expect(w.findAll('.detail-tab')).toHaveLength(5) // Galerie / Kapitel / Map / Journal / Info (fixture has a child)
    // (the justified grid needs a real measured width, unavailable in happy-dom — assert the load instead)
    expect(props.loadTiles).toHaveBeenCalledWith('root')
    expect(props.loadChildNodes).toHaveBeenCalledWith('root')
  })

  it('lazily loads + renders the embed-aware journal when the Journal tab opens', async () => {
    const props = makeProps()
    const w = mount(MemoryContent, { props })
    await flushPromises()
    expect(props.loadJournalMarkdown).not.toHaveBeenCalled() // gallery is the default tab
    await w.findAll('.detail-tab')[3].trigger('click') // Journal
    await flushPromises()
    expect(props.loadJournalMarkdown).toHaveBeenCalledWith('root')
    // the ::media:a embed resolves against the loaded gallery tile (rendered in the carousel)
    expect(w.find('.jcar-img img').attributes('src')).toBe('blob:a')
  })

  it('navigates into a child chapter (loads its data, updates the breadcrumb, emits nodeChanged)', async () => {
    const props = makeProps()
    const w = mount(MemoryContent, { props })
    await flushPromises()
    await w.findAll('.detail-tab')[1].trigger('click') // Kapitel (children moved to their own tab)
    await w.find('.memory-children .child-card').trigger('click')
    await flushPromises()
    expect(props.loadTiles).toHaveBeenLastCalledWith('ch1')
    expect(w.find('.detail-crumbs').exists()).toBe(true)
    expect(w.find('.detail-title').text()).toBe('Chapter 1')
    const changed = w.emitted('nodeChanged') as unknown[][]
    expect(changed.at(-1)).toEqual(['ch1', 'Chapter 1'])
  })

  it('shows the back button only when showBack is set', async () => {
    // At the root the breadcrumb is hidden (1 crumb), so the only .crumb is the back button.
    const guest = mount(MemoryContent, { props: makeProps() })
    await flushPromises()
    expect(guest.findAll('.crumb')).toHaveLength(0)
    const member = mount(MemoryContent, { props: makeProps({ showBack: true }) })
    await flushPromises()
    expect(member.findAll('.crumb')).toHaveLength(1)
    await member.find('.crumb').trigger('click')
    expect(member.emitted('back')).toBeTruthy()
  })

  it('uses the live-journal subscription instead of static load when provided, and tears it down', async () => {
    const teardown = vi.fn()
    const liveJournal = vi.fn((_nodeId: string, onUpdate: (md: string) => void) => { onUpdate('# Live heading'); return teardown })
    const props = makeProps({ liveJournal })
    const w = mount(MemoryContent, { props })
    await flushPromises()
    await w.findAll('.detail-tab')[3].trigger('click') // Journal
    await flushPromises()
    expect(liveJournal).toHaveBeenCalledWith('root', expect.any(Function))
    expect(props.loadJournalMarkdown).not.toHaveBeenCalled()
    expect(w.text()).toContain('Live heading')
    w.unmount()
    expect(teardown).toHaveBeenCalled()
  })

  it('renders the journal-edit slot instead of the body when journalEditing is set', async () => {
    const w = mount(MemoryContent, {
      props: makeProps({ journalEditing: true }),
      slots: { 'journal-edit': '<div class="my-editor">editing</div>' },
    })
    await flushPromises()
    await w.findAll('.detail-tab')[3].trigger('click')
    await flushPromises()
    expect(w.find('.my-editor').exists()).toBe(true)
    expect(w.find('.journal-media-thumb').exists()).toBe(false)
  })

  it('emits linked participant selections for the member profile view', async () => {
    const props = makeProps({
      canOpenLinkedParticipants: true,
      loadParticipants: vi.fn(async () => [
        { id: 'p1', displayName: 'Mira', linkedUserId: 'u2' },
        { id: 'p2', displayName: 'Anonymous Guest' },
      ]),
    })
    const w = mount(MemoryContent, { props })
    await flushPromises()
    await w.find('.participant-link').trigger('click')
    expect(w.emitted('openParticipant')?.[0][0]).toEqual({ id: 'p1', displayName: 'Mira', linkedUserId: 'u2' })
  })
})
