import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// Mock the data layer so the component is tested without real crypto/network.
const node = (id: string, type: string, name: string) => ({ node: { id, type, slugToken: id }, name })
vi.mock('../../features/home/home', () => ({
  loadSpaces: vi.fn(async () => [
    { kind: 'PERSONAL', displayName: 'Kevin', scopeId: 'me', scopePublicKey: { suiteId: 'v1', kemPublicKey: '' } },
    { kind: 'GROUP', displayName: 'affenbande', role: 'Owner', tenantId: 't1', scopeId: 'g1', scopePublicKey: { suiteId: 'v1', kemPublicKey: '' } },
  ]),
  enterSpace: vi.fn(async (_a: unknown, _s: unknown, entry: { scopeId: string }) => ({ entry, scope: { scopeId: entry.scopeId }, contentKey: new Uint8Array() })),
  loadLevel: vi.fn(async () => [node('m1', 'MEMORY', 'Italien 2026'), node('f1', 'FOLDER', 'Urlaube')]),
  loadCover: vi.fn(async () => null),
}))

import HomeView from './HomeView.vue'
import { session, setUnlocked } from '../session'

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
})
