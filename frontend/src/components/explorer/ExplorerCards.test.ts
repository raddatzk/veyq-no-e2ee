import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ExplorerAlbumCard from './ExplorerAlbumCard.vue'
import ExplorerFolderCard from './ExplorerFolderCard.vue'
import ExplorerGrid from './ExplorerGrid.vue'
import ExplorerScopeRootCard from './ExplorerScopeRootCard.vue'

const buttonStub = {
  emits: ['click'],
  template: '<button @click="$emit(\'click\')"><slot /></button>'
}
const cardStub = { template: '<div><slot name="content" /></div>' }

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
      global: { stubs: { Button: buttonStub, Card: cardStub } }
    })

    expect(wrapper.text()).toContain('Family')
    expect(wrapper.text()).toContain('/@family/trip')
    await wrapper.find('.explorer-card-main').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
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
      global: { stubs: { Button: buttonStub, Card: cardStub } }
    })

    expect(wrapper.text()).toContain('Kevin')
    expect(wrapper.text()).toContain('/~me/private')
    await wrapper.find('[aria-label="Album löschen"]').trigger('click')
    expect(wrapper.emitted('delete')).toHaveLength(1)
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
      },
      global: { stubs: { Card: cardStub } }
    })

    expect(wrapper.text()).toContain('Family')
    expect(wrapper.text()).toContain('Gruppe')
    expect(wrapper.text()).toContain('/@family')
    await wrapper.find('.explorer-card-main').trigger('click')
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

    await wrapper.findAll('.explorer-card-main')[1].trigger('click')
    expect(wrapper.emitted('openScopeRoot')?.[0]).toEqual([
      { kind: 'GROUP', name: 'family', displayName: 'Family', path: '/@family' }
    ])
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
})
