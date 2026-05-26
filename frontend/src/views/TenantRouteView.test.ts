import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import TenantRouteView from './TenantRouteView.vue'
import { authState } from '../authState'

describe('TenantRouteView', () => {
  afterEach(() => {
    authState.user = null
  })

  it('renders a user profile route for another user permalink', async () => {
    const { wrapper } = await mountAt('/friends/~anna')

    expect(wrapper.find('[data-testid="user-profile-view"]').exists()).toBe(true)
  })

  it('redirects the current user permalink to the personal memory route', async () => {
    authState.user = {
      userId: 1,
      email: 'kevin@example.test',
      displayName: 'Kevin',
      permalink: 'kevin',
      role: 'ServerOwner',
      status: 'Active'
    }

    const { router, wrapper } = await mountAt('/friends/~kevin')

    expect(router.currentRoute.value.fullPath).toBe('/friends/~me')
    expect(wrapper.find('[data-testid="tenant-view"]').exists()).toBe(true)
  })

  it('keeps the reserved personal memory route on tenant view', async () => {
    const { wrapper } = await mountAt('/friends/~me')

    expect(wrapper.find('[data-testid="tenant-view"]').exists()).toBe(true)
  })

  it('renders settings routes before profile routes', async () => {
    const { wrapper } = await mountAt('/friends/~anna/settings')

    expect(wrapper.find('[data-testid="settings-view"]').exists()).toBe(true)
  })
})

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:tenantName/:pathMatch(.*)*', component: TenantRouteView }]
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(TenantRouteView, {
    global: {
      plugins: [router],
      stubs: {
        TenantView: { template: '<div data-testid="tenant-view" />' },
        SettingsView: { template: '<div data-testid="settings-view" />' },
        UserProfileView: { template: '<div data-testid="user-profile-view" />' }
      }
    }
  })
  await flushPromises()
  return { router, wrapper }
}
