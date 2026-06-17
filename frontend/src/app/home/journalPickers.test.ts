import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import JournalMediaPicker from './JournalMediaPicker.vue'
import JournalEmbedPicker from './JournalEmbedPicker.vue'
import type { GalleryTile } from '../../features/gallery/gallery'

const tile = (id: string): GalleryTile =>
  ({ id, url: `blob:${id}`, aspectRatio: 1, mimeType: 'image/jpeg', takenAt: '2026-01-01T00:00:00Z', highlighted: false, preview: {} as never, original: {} as never })

describe('JournalMediaPicker', () => {
  it('toggles selection and confirms with ids + caption', async () => {
    const w = mount(JournalMediaPicker, { props: { tiles: [tile('a'), tile('b')], selectedIds: [], caption: '', editing: false } })
    const thumbs = w.findAll('.journal-picker-thumb')
    expect(thumbs).toHaveLength(2)
    await thumbs[0].trigger('click')
    await w.find('.journal-picker-field input').setValue('My caption')
    await w.find('.btn-primary').trigger('click')
    expect(w.emitted('confirm')?.[0]).toEqual([['a'], 'My caption'])
  })

  it('disables insert with no selection and hides Delete unless editing', () => {
    const w = mount(JournalMediaPicker, { props: { tiles: [tile('a')], selectedIds: [], caption: '', editing: false } })
    expect((w.find('.btn-primary').element as HTMLButtonElement).disabled).toBe(true)
    expect(w.text()).not.toContain('Löschen')
  })

  it('seeds selection + caption and offers Delete in edit mode', async () => {
    const w = mount(JournalMediaPicker, { props: { tiles: [tile('a'), tile('b')], selectedIds: ['b'], caption: 'Seeded', editing: true } })
    expect(w.find('.journal-picker-thumb.selected').exists()).toBe(true)
    const del = w.findAll('button').find((b) => b.text() === 'Löschen')
    expect(del).toBeTruthy()
    await del!.trigger('click')
    expect(w.emitted('remove')).toBeTruthy()
  })
})

describe('JournalEmbedPicker', () => {
  it('multi-selects tracks and confirms with ids + label', async () => {
    const w = mount(JournalEmbedPicker, { props: { kind: 'track', items: [{ id: 't1', name: 'Route 1' }, { id: 't2', name: 'Route 2' }], selectedIds: [], label: '', editing: false } })
    const hits = w.findAll('.search-hit')
    await hits[0].trigger('click')
    await hits[1].trigger('click')
    await w.find('.journal-picker-field input').setValue('Hike')
    await w.find('.btn-primary').trigger('click')
    expect(w.emitted('confirm')?.[0]).toEqual([['t1', 't2'], 'Hike'])
  })

  it('single-selects a chapter (a second pick replaces the first)', async () => {
    const w = mount(JournalEmbedPicker, { props: { kind: 'chapter', items: [{ id: 'c1', name: 'Day 1' }, { id: 'c2', name: 'Day 2' }], selectedIds: [], label: '', editing: false } })
    const hits = w.findAll('.search-hit')
    await hits[0].trigger('click')
    await hits[1].trigger('click')
    await w.find('.btn-primary').trigger('click')
    expect(w.emitted('confirm')?.[0]).toEqual([['c2'], ''])
  })
})
