import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SettingsView from './SettingsView.vue'
import { getSettings } from '../features/settings/api'
import { authState } from '../authState'
import { clearWorkspaceToolbar, workspaceToolbar } from '../composables/useWorkspaceToolbar'

vi.mock('../features/settings/api', () => ({
  getSettings: vi.fn()
}))

const getSettingsMock = vi.mocked(getSettings)

describe('SettingsView', () => {
  afterEach(() => {
    getSettingsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
    authState.activeTenantName = ''
    clearWorkspaceToolbar()
  })

  it('loads the settings context with the full global route path', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/~me', 'USER'))
    const router = testRouter()
    await router.push('/~me/settings')
    await router.isReady()

    mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(getSettingsMock).toHaveBeenCalledWith('/~me')
  })

  it('does not show browser privacy settings globally on settings pages', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/~me', 'USER'))
    const router = testRouter()
    await router.push('/~me/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('User settings')
    expect(wrapper.text()).not.toContain('Privacy settings')
  })

  it('does not show browser privacy settings on tenant settings pages', async () => {
    authState.tenants = [
      {
        id: 1,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.example.test',
        role: 'TenantOwner',
        admins: []
      }
    ]
    authState.activeTenantName = 'friends'
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/settings', 'TENANT'))
    const router = testRouter()
    await router.push('/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Tenant settings')
    expect(wrapper.text()).not.toContain('Privacy settings')
  })

  it('does not show browser privacy settings on server settings pages', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/settings', 'SERVER'))
    const router = testRouter()
    await router.push('/server/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Server settings')
    expect(wrapper.text()).not.toContain('Privacy settings')
  })

  it('loads tenant settings context with tenant prefix in the path', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/@family/settings', 'GROUP'))
    authState.tenants = [
      {
        id: 1,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.example.test',
        role: 'TenantOwner',
        admins: []
      }
    ]
    authState.activeTenantName = 'friends'
    const router = testRouter()
    await router.push('/@family/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(getSettingsMock).toHaveBeenCalledWith('/friends/@family/settings')
    expect(wrapper.text()).toContain('Group settings family')
  })

  it('hides tenant member management for tenant members', async () => {
    authState.tenants = [
      {
        id: 1,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.example.test',
        role: 'TenantMember',
        admins: []
      }
    ]
    authState.activeTenantName = 'friends'
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/settings', 'TENANT'))
    const router = testRouter()
    await router.push('/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Group settings')
    expect(wrapper.text()).not.toContain('Tenant settings')
  })

  it('does not show tenant settings while opening the create group flow', async () => {
    authState.tenants = [
      {
        id: 1,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.example.test',
        role: 'TenantOwner',
        admins: []
      }
    ]
    authState.activeTenantName = 'friends'
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/settings', 'TENANT'))
    const router = testRouter()
    await router.push('/settings?createGroup=1')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Group settings')
    expect(wrapper.text()).not.toContain('Tenant settings')
  })

  it('ignores stale settings responses after switching tenants', async () => {
    let resolveFriends: (value: ReturnType<typeof settingsResponse>) => void = () => {}
    const friendsPromise = new Promise<ReturnType<typeof settingsResponse>>((resolve) => {
      resolveFriends = resolve
    })
    getSettingsMock.mockImplementation((path) => {
      if (path === '/friends/settings') return friendsPromise
      return Promise.resolve(settingsResponse('/family/settings', 'TENANT', 'Family'))
    })
    authState.tenants = [
      tenant('friends', 'Friends'),
      tenant('family', 'Family')
    ]
    authState.activeTenantName = 'friends'
    const router = testRouter()
    await router.push('/settings')
    await router.isReady()

    mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    authState.activeTenantName = 'family'
    await flushPromises()
    resolveFriends(settingsResponse('/friends/settings', 'TENANT', 'Friends'))
    await flushPromises()

    expect(getSettingsMock).toHaveBeenCalledWith('/friends/settings')
    expect(getSettingsMock).toHaveBeenCalledWith('/family/settings')
    expect(workspaceToolbar.breadcrumbs.map((breadcrumb) => breadcrumb.label)).toEqual([
      'Einstellungen',
      'Family'
    ])
  })
})

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings', component: SettingsView },
      { path: '/server/settings', name: 'server-settings', component: SettingsView },
      { path: '/~me/settings', name: 'user-settings', component: SettingsView },
      { path: '/:pathMatch(.*)*', component: SettingsView }
    ]
  })
}

function settingsResponse(path: string, type: 'USER' | 'GROUP' | 'TENANT' | 'SERVER', label = 'Friends') {
  return {
    location: {
      path,
      displayName: 'Einstellungen',
      label: 'Einstellungen'
    },
    subject: {
      type,
      displayName: type === 'USER' ? 'Me' : type === 'TENANT' ? label : type === 'SERVER' ? 'Server' : 'Family',
      label: type === 'USER' ? '~Me' : type === 'TENANT' ? label : type === 'SERVER' ? 'Server' : '@Family'
    },
    breadcrumbs: [
      {
        type,
        path,
        displayName: 'Einstellungen',
        label: 'Einstellungen'
      },
      {
        type,
        path,
        displayName: label,
        label
      }
    ]
  }
}

function tenant(name: string, displayName: string) {
  return {
    id: name === 'friends' ? 1 : 2,
    name,
    displayName,
    domain: `${name}.example.test`,
    role: 'TenantOwner' as const,
    admins: []
  }
}

function viewStubs() {
  return {
    Message: { template: '<div><slot /></div>' },
    ProgressSpinner: { template: '<div />' },
    ServerSettingsPanel: { template: '<section>Server settings</section>' },
    GroupSettingsPanel: {
      template: '<section>Group settings {{ groupName }}</section>',
      props: ['groupName']
    },
    TenantSettingsPanel: { template: '<section>Tenant settings</section>' },
    UserSettingsForm: { template: '<section>User settings</section>' }
  }
}
