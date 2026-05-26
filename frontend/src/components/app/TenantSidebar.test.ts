import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import TenantSidebar from './TenantSidebar.vue'

const mocks = vi.hoisted(() => ({
  getMemoryNavigation: vi.fn()
}))

vi.mock('vue-router', () => ({
  RouterLink: {
    props: ['to'],
    template: '<a :href="String(to)"><slot /></a>'
  },
  useRoute: () => ({ params: { tenantName: 'tenant' } })
}))

vi.mock('../../features/memories/api', () => ({
  getMemoryNavigation: mocks.getMemoryNavigation
}))

vi.mock('../../shared/state/useAuthState', () => ({
  useAuthState: () => ({
    authState: {
      isAuthenticated: true,
      user: { serverRole: 'ServerMember' },
      tenants: [{ id: 1, name: 'tenant', displayName: 'Tenant', domain: 'tenant.test' }]
    }
  })
}))

vi.mock('../../shared/state/useThemeState', () => ({
  useThemeState: () => ({ resolvedTheme: { value: 'light' } })
}))

describe('TenantSidebar', () => {
  it('renders favorite memory covers without a favorites add action or star overlay', async () => {
    mocks.getMemoryNavigation.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [],
      favoriteMemories: [
        {
          id: 'mem-1',
          parentId: null,
          name: 'trip',
          displayName: 'Favorite Trip',
          path: '/trip',
          coverMediaId: null,
          coverUrl: '/api/tenant/memories/mem-1/cover/content',
          coverCrop: { x: 0.1, y: 0.2, width: 0.8, height: 0.45 },
          startDate: '2026-05-03',
          endDate: '2026-05-05',
          childCount: 0,
          mediaCount: 0,
          favorite: true,
          scope: null
        },
        {
          id: 'mem-2',
          parentId: null,
          name: 'without-cover',
          displayName: 'Without Cover',
          path: '/without-cover',
          coverMediaId: null,
          coverUrl: null,
          coverCrop: null,
          startDate: '2026-05-03',
          endDate: '2026-05-05',
          childCount: 0,
          mediaCount: 0,
          favorite: true,
          scope: null
        }
      ],
      folders: []
    })

    const wrapper = mount(TenantSidebar, {
      global: {
        stubs: {
          Menu: { template: '<div />' },
          MemoryCoverImage: {
            props: ['src', 'alt', 'crop'],
            template: '<img class="memory-cover-image" :src="src" :alt="alt" :data-crop="JSON.stringify(crop)" />'
          },
          ProfileMenu: { template: '<div />' }
        }
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Favorite Trip')
    expect(wrapper.find('a[href="/tenant/trip"]').exists()).toBe(true)
    expect(wrapper.find('img.memory-cover-image').attributes('src')).toBe('/api/tenant/memories/mem-1/cover/content')
    expect(wrapper.find('img.memory-cover-image').attributes('data-crop')).toBe('{"x":0.1,"y":0.2,"width":0.8,"height":0.45}')
    expect(wrapper.find('a[href="/tenant/without-cover"] .pi-images').exists()).toBe(true)
    expect(wrapper.find('.pi-star-fill').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Favoriten hinzufügen')
  })
})
