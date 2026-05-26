import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkspaceTopbar from './WorkspaceTopbar.vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  searchTenant: vi.fn()
}))
const routeState = vi.hoisted(() => ({
  name: 'tenant' as string | null,
  params: { tenantName: 'friends' } as Record<string, string>
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({ push: mocks.push })
}))

vi.mock('../../features/memories/api', () => ({
  searchTenant: mocks.searchTenant
}))

describe('WorkspaceTopbar', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mocks.push.mockReset()
    mocks.searchTenant.mockReset()
    routeState.name = 'tenant'
    routeState.params = { tenantName: 'friends' }
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('opens search from the topbar and navigates to a result', async () => {
    mocks.searchTenant.mockResolvedValue([
      {
        kind: 'MEMORY',
        id: 'mem_123',
        displayName: 'Beach Day',
        path: '/summer/beach-day',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      }
    ])
    const wrapper = mountTopbar()

    await wrapper.find('.workspace-topbar-center').trigger('click')
    await wrapper.find('.workspace-topbar-center input').setValue('beach')
    await vi.advanceTimersByTimeAsync(240)
    await flushPromises()

    expect(mocks.searchTenant).toHaveBeenCalledWith('friends', 'beach', 20)
    expect(wrapper.text()).toContain('Beach Day')

    await wrapper.find('.workspace-search-result').trigger('click')

    expect(mocks.push).toHaveBeenCalledWith('/friends/summer/beach-day')
    expect(wrapper.find('.workspace-search-panel').exists()).toBe(false)
  })

  it('opens with the keyboard shortcut and closes with escape', async () => {
    const wrapper = mountTopbar()

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true }))
    await flushPromises()

    expect(wrapper.find('.workspace-search-panel').exists()).toBe(true)

    await wrapper.find('.workspace-topbar-center input').trigger('keydown', { key: 'Escape' })

    expect(wrapper.find('.workspace-search-panel').exists()).toBe(false)
  })

  it('supports keyboard selection with enter', async () => {
    mocks.searchTenant.mockResolvedValue([
      {
        kind: 'FOLDER',
        id: 'fol_123',
        displayName: 'Archive',
        path: '/archive',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      },
      {
        kind: 'MEMORY',
        id: 'mem_123',
        displayName: 'Beach Day',
        path: '/beach-day',
        scope: { type: 'TENANT' },
        matchTypes: new Set(['TITLE'])
      }
    ])
    const wrapper = mountTopbar()

    await wrapper.find('.workspace-topbar-center').trigger('click')
    const input = wrapper.find('.workspace-topbar-center input')
    await input.setValue('beach')
    await vi.advanceTimersByTimeAsync(240)
    await flushPromises()
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })

    expect(mocks.push).toHaveBeenCalledWith('/friends/beach-day')
  })
})

function mountTopbar() {
  return mount(WorkspaceTopbar, {
    global: {
      stubs: {
        Breadcrumb: { template: '<nav />' },
        Button: { template: '<button />' }
      }
    }
  })
}
