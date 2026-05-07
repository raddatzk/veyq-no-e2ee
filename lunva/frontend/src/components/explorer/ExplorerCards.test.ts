import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ExplorerAlbumCard from './ExplorerAlbumCard.vue'
import ExplorerFolderCard from './ExplorerFolderCard.vue'
import ExplorerGrid from './ExplorerGrid.vue'
import ExplorerMediaCard from './ExplorerMediaCard.vue'
import ExplorerScopeRootCard from './ExplorerScopeRootCard.vue'

const buttonStub = {
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>'
}
const cardStub = { template: '<div><slot name="content" /></div>' }
const menuStub = {
  props: ['model'],
  template:
    '<div><button v-for="item in model" :key="item.label" :aria-label="item.label" @click="item.command">{{ item.label }}</button></div>'
}

describe('explorer cards', () => {
  it('renders folder owner badge and emits actions', async () => {
    const wrapper = mount(ExplorerFolderCard, {
      props: {
        folder: {
          id: 'folder-1',
          name: 'trip',
          displayName: 'Trip',
          path: '/@family/trip',
          ownerBadge: { kind: 'GROUP', name: 'family', displayName: 'Family' }
        }
      },
      global: { stubs: { Button: buttonStub, Card: cardStub, Menu: menuStub } }
    })

    expect(wrapper.text()).toContain('@Family')
    expect(wrapper.text()).not.toContain('/@family/trip')
    await wrapper.find('.explorer-card-main').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
    await wrapper.find('[aria-label="Verschieben"]').trigger('click')
    expect(wrapper.emitted('move')).toHaveLength(1)
  })

  it('renders album owner badge and cover action', async () => {
    const wrapper = mount(ExplorerAlbumCard, {
      props: {
        album: {
          id: 'album-1',
          name: 'private',
          displayName: 'Private',
          path: '/~me/private',
          coverMediaId: null,
          ownerBadge: { kind: 'USER', name: 'me', displayName: 'Kevin' }
        }
      },
      global: { stubs: { Button: buttonStub, Card: cardStub, Menu: menuStub } }
    })

    expect(wrapper.text()).toContain('~Kevin')
    expect(wrapper.text()).not.toContain('/~me/private')
    await wrapper.find('[aria-label="Löschen"]').trigger('click')
    expect(wrapper.emitted('delete')).toHaveLength(1)
    await wrapper.find('[aria-label="Cover setzen"]').trigger('click')
    expect(wrapper.emitted('cover')).toHaveLength(1)
  })

  it('renders no metadata line when owner badge is missing', () => {
    const wrapper = mount(ExplorerFolderCard, {
      props: {
        folder: {
          id: 'folder-2',
          name: 'plain',
          displayName: 'Plain',
          path: '/plain',
          ownerBadge: null
        }
      },
      global: { stubs: { Button: buttonStub, Card: cardStub, Menu: menuStub } }
    })

    expect(wrapper.text()).toContain('Plain')
    expect(wrapper.text()).not.toContain('/plain')
    expect(wrapper.find('small').exists()).toBe(false)
  })

  it('renders scope root card and emits open', async () => {
    const wrapper = mount(ExplorerScopeRootCard, {
      props: {
        scopeRoot: {
          kind: 'GROUP',
          name: 'family',
          displayName: 'Family',
          path: '/@family'
        }
      }
    })

    expect(wrapper.text()).toContain('@Family')
    await wrapper.find('.explorer-scope-root-card').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual([
      { kind: 'GROUP', name: 'family', displayName: 'Family', path: '/@family' }
    ])
  })

  it('renders scope roots before folders and emits navigation', async () => {
    const wrapper = mount(ExplorerGrid, {
      props: {
        scopeRoots: [
          { kind: 'USER', name: 'me', displayName: 'Kevin', path: '/~me' },
          { kind: 'GROUP', name: 'family', displayName: 'Family', path: '/@family' }
        ],
        folders: [],
        albums: []
      },
      global: { stubs: { Card: cardStub } }
    })

    const sections = wrapper.findAll('.explorer-section')
    expect(sections[0].text()).toContain('Bereiche')
    expect(sections[1].text()).toContain('Ordner und Alben')
    expect(wrapper.text()).toContain('Kevin')
    expect(wrapper.text()).toContain('Family')

    await wrapper.findAll('.explorer-scope-root-card')[1].trigger('click')
    expect(wrapper.emitted('openScopeRoot')?.[0]).toEqual([
      { kind: 'GROUP', name: 'family', displayName: 'Family', path: '/@family' }
    ])
  })

  it('sorts folders and albums together by display name', async () => {
    const album = {
      id: 'album-1',
      name: 'alpha',
      displayName: 'alpha',
      path: '/alpha',
      coverMediaId: null,
      ownerBadge: null
    }
    const wrapper = mount(ExplorerGrid, {
      props: {
        scopeRoots: [],
        folders: [
          {
            id: 'folder-1',
            name: 'zebra',
            displayName: 'Zebra',
            path: '/zebra',
            ownerBadge: null
          },
          {
            id: 'folder-2',
            name: 'bravo',
            displayName: 'Bravo',
            path: '/bravo',
            ownerBadge: null
          }
        ],
        albums: [album]
      },
      global: { stubs: { Button: buttonStub, Card: cardStub, Menu: menuStub } }
    })

    expect(wrapper.find('.explorer-list').exists()).toBe(true)
    expect(wrapper.find('.explorer-grid').exists()).toBe(false)
    expect(wrapper.findAll('.explorer-card-main strong').map((item) => item.text())).toEqual([
      'alpha',
      'Bravo',
      'Zebra'
    ])

    await wrapper.findAll('.explorer-card-main')[0].trigger('click')
    expect(wrapper.emitted('openAlbum')?.[0]).toEqual([album])
  })

  it('hides scope root section when there are no scope roots', () => {
    const wrapper = mount(ExplorerGrid, {
      props: {
        scopeRoots: [],
        folders: [],
        albums: []
      },
      global: { stubs: { Card: cardStub } }
    })

    expect(wrapper.text()).not.toContain('Bereiche')
    expect(wrapper.text()).toContain('Noch keine Ordner oder Alben')
  })

  it('renders media without title or path and emits open', async () => {
    const wrapper = mount(ExplorerMediaCard, {
      props: {
        width: 320,
        height: 180,
        media: {
          id: 'media-1',
          title: 'Beach',
          albumPath: '/@family/trip',
          ownerUserId: 1,
          uploaderDisplayName: 'Kevin',
          createdAt: '2026-01-01T00:00:00Z',
          capturedAt: null,
          capturedAtOverride: false,
          mimeType: 'image/jpeg',
          sizeBytes: 1234,
          contentUrl: '/api/media/media-1/content',
          previewUrl: '/api/media/media-1/preview',
          cameraMake: null,
          cameraModel: null,
          lensModel: null,
          imageWidth: null,
          imageHeight: null,
          gpsLatitude: null,
          gpsLongitude: null
        }
      }
    })

    expect(wrapper.text()).not.toContain('Beach')
    expect(wrapper.text()).not.toContain('/@family/trip')
    expect(wrapper.find('img').exists()).toBe(true)

    await wrapper.find('.media-card').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it('keeps videos visible in the media grid layout', () => {
    const wrapper = mount(ExplorerMediaCard, {
      props: {
        width: 320,
        height: 180,
        media: {
          id: 'media-2',
          title: 'Clip',
          albumPath: '/@family/trip',
          ownerUserId: 1,
          uploaderDisplayName: 'Kevin',
          createdAt: '2026-01-01T00:00:00Z',
          capturedAt: null,
          capturedAtOverride: false,
          mimeType: 'video/mp4',
          sizeBytes: 5678,
          contentUrl: '/api/media/media-2/content',
          previewUrl: null,
          cameraMake: null,
          cameraModel: null,
          lensModel: null,
          imageWidth: null,
          imageHeight: null,
          gpsLatitude: null,
          gpsLongitude: null
        }
      }
    })

    expect(wrapper.text()).not.toContain('Clip')
    expect(wrapper.find('video').exists()).toBe(true)
  })

  it('uses generated previews for video media cards when available', () => {
    const wrapper = mount(ExplorerMediaCard, {
      props: {
        width: 320,
        height: 180,
        media: {
          id: 'media-3',
          title: 'Preview Clip',
          albumPath: '/@family/trip',
          ownerUserId: 1,
          uploaderDisplayName: 'Kevin',
          createdAt: '2026-01-01T00:00:00Z',
          capturedAt: null,
          capturedAtOverride: false,
          mimeType: 'video/mp4',
          sizeBytes: 5678,
          contentUrl: '/api/media/media-3/content',
          previewUrl: '/api/media/media-3/preview',
          cameraMake: null,
          cameraModel: null,
          lensModel: null,
          imageWidth: null,
          imageHeight: null,
          gpsLatitude: null,
          gpsLongitude: null
        }
      }
    })

    expect(wrapper.find('img').attributes('src')).toBe('/api/media/media-3/preview')
    expect(wrapper.find('video').exists()).toBe(false)
  })

})
