import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import TenantSidebar from './TenantSidebar.vue'

const mocks = vi.hoisted(() => ({
  getMemoryNavigation: vi.fn(),
  routerPush: vi.fn(),
  authState: {
    isAuthenticated: true,
    user: { serverRole: 'ServerMember' },
    tenants: [{ id: 1, name: 'tenant', displayName: 'Tenant', domain: 'tenant.test' }]
  }
}))

vi.mock('vue-router', () => ({
  RouterLink: {
    props: ['to'],
    template: '<a :href="String(to)"><slot /></a>'
  },
  useRoute: () => ({ params: { tenantName: 'tenant' } }),
  useRouter: () => ({ push: mocks.routerPush })
}))

vi.mock('../../features/memories/api', () => ({
  getMemoryNavigation: mocks.getMemoryNavigation
}))

vi.mock('../../shared/state/useAuthState', () => ({
  useAuthState: () => ({
    authState: mocks.authState
  })
}))

vi.mock('../../shared/state/useThemeState', () => ({
  useThemeState: () => ({ resolvedTheme: { value: 'light' } })
}))

describe('TenantSidebar', () => {
  function mountSidebar() {
    return mount(TenantSidebar, {
      global: {
        stubs: {
          Menu: {
            props: ['model'],
            template: `
              <div class="menu-stub">
                <span v-for="(item, index) in model" :key="index" class="menu-stub-item">
                  {{ item.label }}
                </span>
              </div>
            `
          },
          Dialog: {
            props: ['visible', 'header'],
            template: '<section class="dialog-stub"><h1>{{ header }}</h1><slot /></section>'
          },
          MemoryCoverImage: {
            props: ['src', 'alt', 'crop'],
            template: '<img class="memory-cover-image" :src="src" :alt="alt" :data-crop="JSON.stringify(crop)" />'
          },
          ProfileMenu: { template: '<div />' }
        }
      }
    })
  }

  it('renders favorite memory covers without a favorites add action or star overlay', async () => {
    mocks.authState.user.serverRole = 'ServerMember'
    mocks.authState.tenants = [{ id: 1, name: 'tenant', displayName: 'Tenant', domain: 'tenant.test' }]
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

    const wrapper = mountSidebar()
    await flushPromises()

    expect(wrapper.text()).toContain('Favorite Trip')
    expect(wrapper.find('a[href="/tenant/trip"]').exists()).toBe(true)
    expect(wrapper.find('img.memory-cover-image').attributes('src')).toBe('/api/tenant/memories/mem-1/cover/content')
    expect(wrapper.find('img.memory-cover-image').attributes('data-crop')).toBe('{"x":0.1,"y":0.2,"width":0.8,"height":0.45}')
    expect(wrapper.find('a[href="/tenant/without-cover"] .pi-images').exists()).toBe(true)
    expect(wrapper.find('.pi-star-fill').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Favoriten hinzufügen')
  })

  it('renders compact tenant menu, mobile sheet content and group overflow actions with clear labels', async () => {
    mocks.authState.user.serverRole = 'ServerOwner'
    mocks.authState.tenants = [
      { id: 1, name: 'tenant', displayName: 'Tenant', domain: 'tenant.test' },
      { id: 2, name: 'family', displayName: 'Family', domain: 'family.test' }
    ]
    mocks.getMemoryNavigation.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [
        {
          kind: 'USER',
          name: 'kevin',
          displayName: 'Kevin',
          path: '/~kevin'
        },
        {
          kind: 'GROUP',
          name: 'travel',
          displayName: 'Travel',
          path: '/@travel'
        }
      ],
      favoriteMemories: [
        {
          id: 'mem-1',
          parentId: null,
          name: 'favorite',
          displayName: 'Favorite Memory',
          path: '/favorite',
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

    const wrapper = mountSidebar()
    await flushPromises()

    expect(wrapper.find('.sidebar-brand').exists()).toBe(true)
    expect(wrapper.get('.compact-tenant-menu-button').text()).toContain('Tenant')
    expect(wrapper.find('.compact-group-divider').exists()).toBe(true)
    expect(wrapper.find('.compact-group-overflow-button').exists()).toBe(true)
    expect(wrapper.find('.mobile-navigation-button').exists()).toBe(true)
    expect(wrapper.find('.dialog-stub').text()).toContain('Navigation')
    expect(wrapper.text()).toContain('Family')
    expect(wrapper.text()).toContain('Bereich erstellen')
    expect(wrapper.text()).toContain('Persönlich')
    expect(wrapper.text()).toContain('Travel')
    expect(wrapper.text()).toContain('Gruppe erstellen')
    expect(wrapper.text()).toContain('Favorite Memory')
  })

  it('emits create group without navigating away from the current page', async () => {
    mocks.authState.user.serverRole = 'ServerMember'
    mocks.authState.tenants = [{ id: 1, name: 'tenant', displayName: 'Tenant', domain: 'tenant.test' }]
    mocks.getMemoryNavigation.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [],
      favoriteMemories: [],
      folders: []
    })

    const wrapper = mountSidebar()
    await flushPromises()

    await wrapper.get('button[aria-label="Gruppe erstellen"]').trigger('click')

    expect(wrapper.emitted('createGroup')).toHaveLength(1)
    expect(mocks.routerPush).not.toHaveBeenCalledWith('/tenant/settings?createGroup=1')
    expect(wrapper.find('a[href="/tenant/settings?createGroup=1"]').exists()).toBe(false)
  })
})
