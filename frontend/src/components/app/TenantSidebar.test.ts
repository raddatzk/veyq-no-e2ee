import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TenantSidebar from './TenantSidebar.vue'
import type { TenantSummary } from '../../authState'
import { notifyMemoryFavoritesChanged } from '../../features/memories/composables/useMemoryFavoritesRefresh'

function tenantSummary(
  id: number,
  name: string,
  displayName: string,
  domain: string,
  logoUrl?: string
): TenantSummary {
  return {
    id,
    name,
    displayName,
    domain,
    logoUrl,
    role: 'TenantMember',
    admins: []
  }
}

const mocks = vi.hoisted(() => ({
  getMemoryNavigationContext: vi.fn(),
  routerPush: vi.fn(),
  setActiveTenantName: vi.fn(),
  locationAssign: vi.fn(),
  authState: {
    isAuthenticated: true,
    user: { serverRole: 'ServerMember' },
    activeTenantName: '',
    tenants: [
      {
        id: 1,
        name: 'tenant',
        displayName: 'Tenant',
        domain: 'tenant.test',
        role: 'TenantMember',
        admins: []
      }
    ] as TenantSummary[]
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
  getMemoryNavigationContext: mocks.getMemoryNavigationContext
}))

vi.mock('../../shared/state/useAuthState', () => ({
  useAuthState: () => ({
    authState: mocks.authState,
    setActiveTenantName: mocks.setActiveTenantName
  })
}))

vi.mock('../../shared/state/useThemeState', () => ({
  useThemeState: () => ({ resolvedTheme: { value: 'light' } })
}))

describe('TenantSidebar', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    setTestUrl('https://tenant.test/')
    vi.spyOn(window.location, 'assign').mockImplementation(mocks.locationAssign)
    mocks.authState.user.serverRole = 'ServerMember'
    mocks.authState.activeTenantName = ''
    mocks.authState.tenants = [tenantSummary(1, 'tenant', 'Tenant', 'tenant.test')]
    mocks.getMemoryNavigationContext.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [],
      favoriteMemories: [],
      folders: [],
      memories: []
    })
  })

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
    mocks.authState.tenants = [tenantSummary(1, 'tenant', 'Tenant', 'tenant.test')]
    mocks.getMemoryNavigationContext.mockResolvedValue({
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
      folders: [],
      memories: []
    })

    const wrapper = mountSidebar()
    await flushPromises()

    expect(wrapper.text()).toContain('Favorite Trip')
    expect(wrapper.find('a[href="/trip"]').exists()).toBe(true)
    expect(wrapper.find('img.memory-cover-image').attributes('src')).toBe('/api/tenant/memories/mem-1/cover/content')
    expect(wrapper.find('img.memory-cover-image').attributes('data-crop')).toBe('{"x":0.1,"y":0.2,"width":0.8,"height":0.45}')
    expect(wrapper.find('a[href="/without-cover"] .pi-images').exists()).toBe(true)
    expect(wrapper.find('.pi-star-fill').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Favoriten hinzufügen')
  })

  it('renders compact tenant menu, mobile sheet content and group overflow actions with clear labels', async () => {
    mocks.authState.user.serverRole = 'ServerOwner'
    mocks.authState.tenants = [
      tenantSummary(1, 'tenant', 'Tenant', 'tenant.test'),
      tenantSummary(2, 'family', 'Family', 'family.test')
    ]
    mocks.getMemoryNavigationContext.mockResolvedValue({
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
      folders: [],
      memories: []
    })

    const wrapper = mountSidebar()
    await flushPromises()

    expect(wrapper.find('.sidebar-brand').exists()).toBe(true)
    expect(wrapper.get('.compact-tenant-menu-button').text()).toContain('Tenant')
    expect(wrapper.find('.sidebar-tenant-menu-button').exists()).toBe(false)
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

  it('keeps scope roots visible while refreshing favorites', async () => {
    mocks.authState.tenants = [tenantSummary(1, 'tenant', 'Tenant', 'tenant.test')]
    mocks.getMemoryNavigationContext.mockResolvedValueOnce({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [
        {
          kind: 'USER',
          name: 'kevin',
          displayName: 'Kevin',
          path: '/~kevin'
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
      folders: [],
      memories: []
    })
    const wrapper = mountSidebar()
    await flushPromises()

    let resolveRefresh: (value: unknown) => void = () => {}
    const refreshPromise = new Promise((resolve) => {
      resolveRefresh = resolve
    })
    mocks.getMemoryNavigationContext.mockImplementation(() => refreshPromise)

    notifyMemoryFavoritesChanged()
    await flushPromises()

    expect(wrapper.find('a[href="/~kevin"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Favorite Memory')

    resolveRefresh({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [
        {
          kind: 'USER',
          name: 'kevin',
          displayName: 'Kevin',
          path: '/~kevin'
        }
      ],
      favoriteMemories: [],
      folders: [],
      memories: []
    })
    await flushPromises()

    expect(wrapper.find('a[href="/~kevin"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('Favorite Memory')
  })

  it('renders tenant logo images when available', async () => {
    mocks.authState.tenants = [
      tenantSummary(1, 'tenant', 'Tenant', 'tenant.test', '/api/tenants/tenant/logo?v=1')
    ]
    mocks.getMemoryNavigationContext.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [],
      favoriteMemories: [],
      folders: [],
      memories: []
    })

    const wrapper = mountSidebar()
    await flushPromises()

    expect(wrapper.find('img[src="/api/tenants/tenant/logo?v=1"]').exists()).toBe(true)
    expect(wrapper.get('.compact-tenant-menu-button .sidebar-tenant-avatar').text()).toBe('')
  })

  it('keeps tenant initials as fallback without a logo', async () => {
    mocks.authState.tenants = [tenantSummary(1, 'tenant', 'Tenant', 'tenant.test')]
    mocks.getMemoryNavigationContext.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [],
      favoriteMemories: [],
      folders: [],
      memories: []
    })

    const wrapper = mountSidebar()
    await flushPromises()

    expect(wrapper.get('.compact-tenant-menu-button .sidebar-tenant-avatar').text()).toBe('T')
  })

  it('emits create group without navigating away from the current page', async () => {
    mocks.authState.tenants = [tenantSummary(1, 'tenant', 'Tenant', 'tenant.test')]
    mocks.getMemoryNavigationContext.mockResolvedValue({
      location: { kind: 'TENANT', path: '/', displayName: 'Tenant' },
      breadcrumbs: [],
      scopeRoots: [],
      favoriteMemories: [],
      folders: [],
      memories: []
    })

    const wrapper = mountSidebar()
    await flushPromises()

    await wrapper.get('button[aria-label="Gruppe erstellen"]').trigger('click')

    expect(wrapper.emitted('createGroup')).toHaveLength(1)
    expect(mocks.routerPush).not.toHaveBeenCalledWith('/tenant/settings?createGroup=1')
    expect(wrapper.find('a[href="/tenant/settings?createGroup=1"]').exists()).toBe(false)
  })

  it('redirects to the tenant domain when switching tenants across domains', async () => {
    mocks.authState.tenants = [
      tenantSummary(1, 'tenant', 'Tenant', 'tenant.test'),
      tenantSummary(2, 'family', 'Family', 'family.test')
    ]

    const wrapper = mountSidebar()
    await flushPromises()

    await wrapper.get('.sidebar-tenant-link[title="Family"]').trigger('click')

    expect(mocks.setActiveTenantName).toHaveBeenCalledWith('family')
    expect(mocks.locationAssign).toHaveBeenCalledWith('https://family.test/')
    expect(mocks.routerPush).not.toHaveBeenCalled()
  })

  it('uses in-app navigation when switching tenants on the current domain', async () => {
    mocks.authState.tenants = [
      tenantSummary(1, 'tenant', 'Tenant', 'tenant.test'),
      tenantSummary(2, 'family', 'Family', 'tenant.test')
    ]

    const wrapper = mountSidebar()
    await flushPromises()

    await wrapper.get('.sidebar-tenant-link[title="Family"]').trigger('click')

    expect(mocks.setActiveTenantName).toHaveBeenCalledWith('family')
    expect(mocks.routerPush).toHaveBeenCalledWith('/')
    expect(mocks.locationAssign).not.toHaveBeenCalled()
  })
})

function setTestUrl(url: string) {
  const testWindow = window as unknown as { happyDOM: { setURL: (url: string) => void } }
  testWindow.happyDOM.setURL(url)
}
