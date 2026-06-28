import { mount, type VueWrapper } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { MemoryPlaylist } from '../api'
import MemoryHeaderPlaylistPlayer from './MemoryHeaderPlaylistPlayer.vue'

describe('MemoryHeaderPlaylistPlayer', () => {
  it('starts with the featured playlist and cycles between playlists', async () => {
    const wrapper = mount(MemoryHeaderPlaylistPlayer, {
      props: {
        playlists: [
          playlist({ id: 'playlist-1', title: 'First playlist' }),
          playlist({ id: 'playlist-2', title: 'Featured playlist', featured: true })
        ]
      },
      global: { directives: { tooltip: {} }, stubs: primeStubs() }
    })

    expect(wrapper.text()).toContain('Featured playlist')

    await buttonByLabel(wrapper, 'Nächste Playlist').trigger('click')

    expect(wrapper.text()).toContain('First playlist')
  })

  it('renders a provider link without loading an iframe or consent prompt', () => {
    const wrapper = mount(MemoryHeaderPlaylistPlayer, {
      props: {
        playlists: [playlist()]
      },
      global: { directives: { tooltip: {} }, stubs: primeStubs() }
    })

    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('YouTube ist deaktiviert')
    expect(wrapper.text()).not.toContain('YouTube laden')
    const link = wrapper.find('a.memory-header-playlist-link')
    expect(link.text()).toContain('Roadtrip Soundtrack')
    expect(link.attributes('aria-label')).toBe('Beim Anbieter öffnen')
    expect(link.attributes('href')).toBe('https://www.youtube.com/playlist?list=PL1234567890abcdef')
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noreferrer')
    expect(wrapper.find('.memory-header-playlist-provider-mark').text()).toBe('YT')
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

function buttonByLabel(wrapper: VueWrapper, label: string) {
  const button = wrapper.findAll('button').find((candidate) => candidate.attributes('aria-label') === label)
  if (!button) throw new Error(`Button not found: ${label}`)
  return button
}

function primeStubs() {
  return {
    Button: {
      props: ['label'],
      emits: ['click'],
      template: '<button v-bind="$attrs" type="button" @click="$emit(\'click\', $event)">{{ label }}<slot /></button>'
    }
  }
}
