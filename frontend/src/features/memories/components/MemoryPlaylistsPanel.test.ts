import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { MemoryPlaylist } from '../api'
import MemoryPlaylistsPanel from './MemoryPlaylistsPanel.vue'

describe('MemoryPlaylistsPanel', () => {
  afterEach(() => {
    window.localStorage.clear()
  })

  it('renders compact playlist cards without embeds', () => {
    const wrapper = mount(MemoryPlaylistsPanel, {
      props: {
        playlists: [playlist()],
        canEdit: true
      },
      global: { stubs: primeStubs() }
    })

    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.text()).toContain('YouTube')
    expect(wrapper.text()).toContain('Roadtrip Soundtrack')
    expect(wrapper.text()).toContain('Songs from the weekend')
    expect(wrapper.text()).not.toContain('YouTube ist deaktiviert')
    expect(wrapper.find('a[href="https://www.youtube.com/playlist?list=PL1234567890abcdef"]').exists()).toBe(true)
  })

  it('shows supported providers in empty and add states', async () => {
    const wrapper = mount(MemoryPlaylistsPanel, {
      props: {
        playlists: [],
        canEdit: true,
        createPlaylist: vi.fn()
      },
      global: { stubs: primeStubs() }
    })

    expect(wrapper.text()).toContain('Unterstützt werden Spotify, Apple Music, YouTube, SoundCloud und Deezer.')

    await buttonByText(wrapper, 'Playlist hinzufügen').trigger('click')

    expect(wrapper.text()).toContain('Unterstützt werden Spotify, Apple Music, YouTube, SoundCloud und Deezer.')
  })

  it('edits playlist metadata and url', async () => {
    const updatePlaylist = vi.fn().mockResolvedValue(playlist({ url: 'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M' }))
    const wrapper = mount(MemoryPlaylistsPanel, {
      props: {
        playlists: [playlist()],
        canEdit: true,
        updatePlaylist
      },
      global: { stubs: primeStubs() }
    })

    await buttonByText(wrapper, 'Bearbeiten').trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('Updated Soundtrack')
    await inputs[1].setValue('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M')
    await wrapper.find('textarea').setValue('Updated note')
    await buttonByText(wrapper, 'Speichern').trigger('click')
    await flushPromises()

    expect(updatePlaylist).toHaveBeenCalledWith('playlist-1', {
      title: 'Updated Soundtrack',
      note: 'Updated note',
      url: 'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'
    })
    expect(wrapper.emitted('changed')).toBeTruthy()
  })

  it('marks a playlist as the featured header playlist', async () => {
    const updatePlaylist = vi.fn().mockResolvedValue(playlist({ featured: true }))
    const wrapper = mount(MemoryPlaylistsPanel, {
      props: {
        playlists: [playlist()],
        canEdit: true,
        updatePlaylist
      },
      global: { stubs: primeStubs() }
    })

    await buttonByText(wrapper, 'Im Header anzeigen').trigger('click')
    await flushPromises()

    expect(updatePlaylist).toHaveBeenCalledWith('playlist-1', {
      title: 'Roadtrip Soundtrack',
      note: 'Songs from the weekend',
      featured: true
    })
    expect(wrapper.emitted('changed')).toBeTruthy()
  })
})

function playlist(overrides: Partial<MemoryPlaylist> = {}): MemoryPlaylist {
  return {
    id: 'playlist-1',
    memoryId: 'memory-1',
    title: 'Roadtrip Soundtrack',
    note: 'Songs from the weekend',
    featured: false,
    provider: 'YOUTUBE',
    url: 'https://www.youtube.com/playlist?list=PL1234567890abcdef',
    externalId: 'PL1234567890abcdef',
    createdAt: '2026-05-24T10:00:00Z',
    updatedAt: '2026-05-24T10:00:00Z',
    ...overrides
  }
}

function buttonByText(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

function primeStubs() {
  return {
    Button: {
      props: ['label', 'disabled'],
      emits: ['click'],
      template: '<button type="button" :disabled="disabled" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    },
    Dialog: {
      props: ['visible', 'header'],
      emits: ['update:visible'],
      template: '<section v-if="visible !== false"><h3>{{ header }}</h3><slot /></section>'
    },
    InputText: {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    },
    Textarea: {
      props: ['modelValue'],
      emits: ['update:modelValue'],
      template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
    }
  }
}
