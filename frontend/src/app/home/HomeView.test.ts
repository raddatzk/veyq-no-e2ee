import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// Mock the data layer so the component is tested without real crypto/network.
const node = (id: string, type: string, name: string, slug = id) => ({ node: { id, type, slugToken: id }, name, slug })
vi.mock('../../features/home/home', () => ({
  loadSpaces: vi.fn(async () => [
    { kind: 'PERSONAL', displayName: 'Kevin', scopeId: 'me', scopePublicKey: { suiteId: 'v1', kemPublicKey: '' } },
    { kind: 'TENANT', displayName: 'affenbande', role: 'Owner', tenantId: 't1', scopeId: 'g1', logoUrl: '/tenant-logo.png', scopePublicKey: { suiteId: 'v1', kemPublicKey: '' } },
  ]),
  enterSpace: vi.fn(async (_a: unknown, _s: unknown, entry: { scopeId: string }) => ({ entry, scope: { scopeId: entry.scopeId }, contentKey: new Uint8Array() })),
  loadLevel: vi.fn(async () => [node('m1', 'MEMORY', 'Italien 2026'), node('f1', 'FOLDER', 'Urlaube')]),
  loadCover: vi.fn(async () => null),
  loadMemoryById: vi.fn(async () => null),
  // restoreFromUrl() resolves the path URL into a folder back-stack + memory; at the jsdom root path ('/')
  // there is no deep link, so it lands on the bare level (openLevel then fills the grid from loadLevel).
  resolveRoute: vi.fn(async () => ({ folders: [], memory: null })),
  ancestorChain: vi.fn(async () => []),
}))
vi.mock('../../features/memory/favorite/favorite', () => ({
  listFavoriteNodeIds: vi.fn(async () => []),
  setMemoryFavorite: vi.fn(async (_api: string, _session: unknown, _scopeId: string, _nodeId: string, favorite: boolean) => favorite),
}))

import HomeView from './HomeView.vue'
import CreateModal from './CreateModal.vue'
import { session, setUnlocked } from '../session'
import { ancestorChain } from '../../features/home/home'
import { listFavoriteNodeIds } from '../../features/memory/favorite/favorite'

beforeEach(() => {
  setUnlocked({ sessionToken: 't', user: { id: 'u', email: 'k@e.de', displayName: 'Kevin', serverRole: 'ServerMember' } } as never)
  session.apiBase = ''
})

describe('HomeView (slice 2)', () => {
  it('renders the space switcher and the memory/folder grids of the active space', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    // sidebar: personal + group spaces
    expect(wrapper.text()).toContain('Kevin')
    expect(wrapper.text()).toContain('affenbande')
    expect(wrapper.find('.space-avatar img').attributes('src')).toBe('/tenant-logo.png')
    // content: memory and folder from the active level
    expect(wrapper.find('.memory-name').text()).toBe('Italien 2026')
    expect(wrapper.find('.folder-name').text()).toBe('Urlaube')
    expect(wrapper.findAll('.memory-card')).toHaveLength(1)
    expect(wrapper.findAll('.folder-card')).toHaveLength(1)
  })

  it('navigates into a folder (breadcrumb grows)', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()
    await wrapper.find('.folder-card').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.crumb').length).toBeGreaterThan(1)
  })

  it('renders favorite memory paths from the decrypted ancestor chain', async () => {
    vi.mocked(listFavoriteNodeIds).mockResolvedValueOnce(['m2'])
    vi.mocked(ancestorChain).mockResolvedValueOnce([
      node('f2', 'FOLDER', 'Ostern', 'ostern'),
      node('m2', 'MEMORY', 'Ostern 2026', 'ostern-2026'),
    ] as never)

    const wrapper = mount(HomeView)
    await flushPromises()

    expect(wrapper.find('.favorite-meta strong').text()).toBe('Ostern 2026')
    expect(wrapper.find('.favorite-meta small').text()).toBe('/ostern/ostern-2026')
  })

  it('uses per-section order indices when opening create dialogs', async () => {
    const wrapper = mount(HomeView)
    await flushPromises()

    await wrapper.findAll('.create-card')[0].trigger('click')
    expect(wrapper.findComponent(CreateModal).props('type')).toBe('MEMORY')
    expect(wrapper.findComponent(CreateModal).props('orderIndex')).toBe(1)

    wrapper.findComponent(CreateModal).vm.$emit('close')
    await flushPromises()

    await wrapper.findAll('.create-card')[1].trigger('click')
    expect(wrapper.findComponent(CreateModal).props('type')).toBe('FOLDER')
    expect(wrapper.findComponent(CreateModal).props('orderIndex')).toBe(1)
  })
})
