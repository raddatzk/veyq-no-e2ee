import { mount } from '@vue/test-utils'
import { CompletionContext } from '@codemirror/autocomplete'
import { EditorState } from '@codemirror/state'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import MemoryJournalLiveEditor from './MemoryJournalLiveEditor.vue'
import type { Memory, MemoryJournalLiveSession, MemoryPlaylist, MemoryTrack } from '../api'
import { createJournalEmbedTypeCompletionSource } from '../utils/journalEmbedCompletion'

const hocuspocus = vi.hoisted(() => ({
  providers: [] as Array<{
    emit: (event: string, payload?: unknown) => void
    destroy: ReturnType<typeof vi.fn>
  }>,
  MockProvider: class {
    awareness = {
      clientID: 1,
      setLocalStateField: vi.fn(),
      on: vi.fn(),
      off: vi.fn(),
      getLocalState: vi.fn(() => ({ user: { name: 'Kevin', color: '#5b6ee1' } })),
      getStates: vi.fn(() => new Map())
    }

    private handlers = new Map<string, Handler[]>()

    constructor() {
      hocuspocus.providers.push(this)
    }

    on(event: string, handler: Handler) {
      const handlers = this.handlers.get(event) || []
      handlers.push(handler)
      this.handlers.set(event, handlers)
    }

    emit(event: string, payload?: unknown) {
      this.handlers.get(event)?.forEach((handler) => handler(payload))
    }

    destroy = vi.fn()
  }
}))

type Handler = (payload?: unknown) => void

vi.mock('@hocuspocus/provider', () => ({
  HocuspocusProvider: hocuspocus.MockProvider
}))

afterEach(() => {
  vi.useRealTimers()
  hocuspocus.providers.length = 0
})

describe('MemoryJournalLiveEditor', () => {
  it('marks local edits as saved after the live snapshot debounce window', async () => {
    vi.useFakeTimers()
    const wrapper = mount(MemoryJournalLiveEditor, {
      props: {
        modelValue: '',
        session: liveSession(),
        media: [],
        childMemories: [],
        playlists: [],
        tracks: []
      }
    })
    await nextTick()

    hocuspocus.providers[0].emit('status', { status: 'connected' })
    await nextTick()
    wrapper.vm.insertMarkdown('Hallo')
    await nextTick()

    expect(wrapper.emitted('status')?.flat()).toContain('saving')

    vi.advanceTimersByTime(1500)
    await nextTick()

    expect(wrapper.emitted('status')?.flat().at(-1)).toBe('saved')
  })

  it('shows an edit action after media tags without adding it to markdown', async () => {
    const wrapper = mount(MemoryJournalLiveEditor, {
      props: {
        modelValue: '',
        session: liveSession(),
        media: [],
        childMemories: [],
        playlists: [],
        tracks: []
      }
    })
    await nextTick()

    wrapper.vm.insertMarkdown('::media:media-1,media-2 Sommer')
    await nextTick()
    await nextTick()

    const editButton = wrapper.find('.memory-journal-media-edit-button')
    expect(editButton.exists()).toBe(true)
    expect(editButton.text()).toContain('::media:2 Elemente')
    expect(wrapper.find('.memory-journal-codemirror').text()).not.toContain('::media:media-1')

    await editButton.trigger('click')

    expect(wrapper.emitted('media-tag-edit')?.[0]).toEqual([
      {
        start: 0,
        end: 30,
        caption: 'Sommer',
        mediaIds: ['media-1', 'media-2']
      }
    ])
    expect(wrapper.emitted('update:modelValue')?.flat()).toEqual(['::media:media-1,media-2 Sommer'])
  })

  it('shows compact chips for chapter playlist and track embeds', async () => {
    const markdown = [
      '::chapter:chapter-1 Day 2',
      '::playlist:playlist-1 Roadtrip',
      '::track:track-1 Route'
    ].join('\n')
    const wrapper = mount(MemoryJournalLiveEditor, {
      props: {
        modelValue: '',
        session: liveSession(),
        media: [],
        childMemories: [
          memory({ id: 'chapter-1', displayName: 'Day 2', path: '/trip/day-2' })
        ],
        playlists: [
          playlist({ id: 'playlist-1', title: 'Roadtrip Mix' })
        ],
        tracks: [
          track({ id: 'track-1', title: 'Summit Route', distanceMeters: 1200 })
        ]
      }
    })
    await nextTick()

    wrapper.vm.insertMarkdown(markdown)
    await nextTick()
    await nextTick()

    const editorText = wrapper.find('.memory-journal-codemirror').text()
    expect(editorText).toContain('::chapter:Day 2')
    expect(editorText).toContain('::playlist:Roadtrip Mix')
    expect(editorText).toContain('::track:Summit Route')
    expect(editorText).not.toContain('::chapter:chapter-1')
    const editButtons = wrapper.findAll('.memory-journal-embed-edit-button')
    expect(editButtons).toHaveLength(3)
    await editButtons[1].trigger('click')
    expect(wrapper.emitted('embed-tag-edit')?.[0]).toEqual([
      {
        start: 26,
        end: 56,
        kind: 'playlist',
        label: 'Roadtrip',
        targetId: 'playlist-1'
      }
    ])
    expect(wrapper.emitted('update:modelValue')?.flat()).toEqual([markdown])
  })

  it('opens embed picker events for typed journal embed triggers', async () => {
    const wrapper = mount(MemoryJournalLiveEditor, {
      props: {
        modelValue: '',
        session: liveSession(),
        media: [],
        childMemories: [],
        playlists: [],
        tracks: []
      }
    })
    await nextTick()

    wrapper.vm.insertMarkdown('::track:')
    await nextTick()

    expect(wrapper.emitted('embed-trigger')?.[0]).toEqual([
      {
        start: 0,
        end: 8,
        kind: 'track'
      }
    ])
  })

  it('highlights a pill on first backspace and deletes it on the second backspace', async () => {
    const wrapper = mount(MemoryJournalLiveEditor, {
      props: {
        modelValue: '',
        session: liveSession(),
        media: [],
        childMemories: [],
        playlists: [],
        tracks: [
          track({ id: 'track-1', title: 'Summit Route', distanceMeters: 1200 })
        ]
      }
    })
    await nextTick()

    wrapper.vm.insertMarkdown('::track:track-1 Route')
    await nextTick()
    await nextTick()

    const editButton = wrapper.find('.memory-journal-embed-edit-button')
    expect(editButton.exists()).toBe(true)

    await wrapper.find('.cm-content').trigger('keydown', { key: 'Backspace' })
    await nextTick()

    expect(wrapper.find('.memory-journal-embed-edit-button').classes()).toContain('memory-journal-embed-delete-pending')
    expect(wrapper.emitted('update:modelValue')?.flat()).toEqual(['::track:track-1 Route'])

    await wrapper.find('.cm-content').trigger('keydown', { key: 'Backspace' })
    await nextTick()

    expect(wrapper.emitted('update:modelValue')?.flat().at(-1)).toBe('')
    expect(wrapper.find('.memory-journal-embed-edit-button').exists()).toBe(false)
  })

  it('highlights a pill on first delete and deletes it on the second delete', async () => {
    const wrapper = mount(MemoryJournalLiveEditor, {
      props: {
        modelValue: '',
        session: liveSession(),
        media: [],
        childMemories: [],
        playlists: [],
        tracks: [
          track({ id: 'track-1', title: 'Summit Route', distanceMeters: 1200 })
        ]
      }
    })
    await nextTick()

    await wrapper.setProps({ modelValue: '::track:track-1 Route' })
    await nextTick()
    await nextTick()

    const editButton = wrapper.find('.memory-journal-embed-edit-button')
    expect(editButton.exists()).toBe(true)
    const emittedBeforeDelete = wrapper.emitted('update:modelValue')?.length ?? 0

    await wrapper.find('.cm-content').trigger('keydown', { key: 'Delete' })
    await nextTick()

    expect(wrapper.find('.memory-journal-embed-edit-button').classes()).toContain('memory-journal-embed-delete-pending')
    expect(wrapper.emitted('update:modelValue')?.length ?? 0).toBe(emittedBeforeDelete)

    await wrapper.find('.cm-content').trigger('keydown', { key: 'Delete' })
    await nextTick()

    expect(wrapper.emitted('update:modelValue')?.flat().at(-1)).toBe('')
    expect(wrapper.find('.memory-journal-embed-edit-button').exists()).toBe(false)
  })

  it('opens the native type completion for the double colon trigger', () => {
    const onMedia = vi.fn()
    const onEmbed = vi.fn()
    const source = createJournalEmbedTypeCompletionSource({
      media: true,
      chapter: true,
      playlist: true,
      track: true,
      labels: {
        media: 'Medium',
        chapter: 'Kapitel',
        playlist: 'Playlist',
        track: 'Track'
      },
      onMedia,
      onEmbed
    })
    const result = source(new CompletionContext(EditorState.create({ doc: '::' }), 2, false))

    expect(result?.from).toBe(0)
    expect(result?.filter).toBe(false)
    expect(result?.options.map((option) => option.label)).toEqual(['Medium', 'Kapitel', 'Playlist', 'Track'])
    expect(result?.options.map((option) => option.detail)).toEqual([undefined, undefined, undefined, undefined])
    expect(result?.options.map((option) => option.type)).toEqual([
      'veyq-media',
      'veyq-chapter',
      'veyq-playlist',
      'veyq-track'
    ])
    expect(result).not.toBeNull()
    if (!result) return
    const applyMedia = result?.options[0].apply
    const applyTrack = result?.options[3].apply
    if (typeof applyMedia === 'function') applyMedia({} as never, result.options[0], 0, 2)
    if (typeof applyTrack === 'function') applyTrack({} as never, result.options[3], 0, 2)
    expect(onMedia).toHaveBeenCalledWith({ start: 0, end: 2 })
    expect(onEmbed).toHaveBeenCalledWith({ start: 0, end: 2, kind: 'track' })
  })

  it('keeps canonical type prefixes searchable when completion labels are localized', () => {
    const source = createJournalEmbedTypeCompletionSource({
      media: true,
      chapter: true,
      playlist: true,
      track: true,
      labels: {
        media: 'Medium',
        chapter: 'Kapitel',
        playlist: 'Playlist',
        track: 'Track'
      },
      onMedia: vi.fn(),
      onEmbed: vi.fn()
    })
    const result = source(new CompletionContext(EditorState.create({ doc: '::c' }), 3, false))

    expect(result?.options.map((option) => option.label)).toEqual(['Kapitel'])
  })
})

function liveSession(): MemoryJournalLiveSession {
  return {
    memoryId: 'mem-1',
    documentId: 'journal-1',
    token: 'token',
    websocketUrl: 'ws://localhost:8082/api/collaboration/journal',
    displayName: 'Kevin',
    color: '#5b6ee1',
    expiresAt: '2026-06-01T12:00:00Z',
    canEdit: true
  }
}

function memory(overrides: Partial<Memory> = {}): Memory {
  return {
    id: 'mem-1',
    parentId: null,
    name: 'trip',
    displayName: 'Trip',
    path: '/trip',
    coverMediaId: null,
    coverUrl: null,
    coverCrop: null,
    startDate: '2026-05-03',
    endDate: '2026-05-05',
    childCount: 0,
    mediaCount: 0,
    favorite: false,
    scope: { type: 'TENANT' },
    ...overrides
  }
}

function playlist(overrides: Partial<MemoryPlaylist> = {}): MemoryPlaylist {
  return {
    id: 'playlist-1',
    memoryId: 'mem-1',
    title: 'Roadtrip Mix',
    featured: false,
    provider: 'YOUTUBE',
    url: 'https://www.youtube.com/playlist?list=PL1234567890abcdef',
    externalId: 'PL1234567890abcdef',
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}

function track(overrides: Partial<MemoryTrack> = {}): MemoryTrack {
  return {
    id: 'track-1',
    memoryId: 'mem-1',
    title: 'Summit Route',
    activityType: 'HIKING',
    originalFilename: 'summit-route.gpx',
    distanceMeters: 1200,
    pointCount: 42,
    bounds: {
      minLatitude: 52.1,
      minLongitude: 13.1,
      maxLatitude: 52.2,
      maxLongitude: 13.2
    },
    geometry: {
      type: 'LineString',
      coordinates: [
        [13.1, 52.1],
        [13.2, 52.2]
      ]
    },
    contentUrl: '/api/memories/mem-1/tracks/track-1/content',
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}
