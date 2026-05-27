import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import SettingsView from './SettingsView.vue'
import { getSettings } from '../features/settings/api'
import { authState } from '../authState'

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
  })

  it('loads the settings context with the full global route path', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/~me', 'USER'))
    const router = testRouter()
    await router.push('/~me')
    await router.isReady()

    mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(getSettingsMock).toHaveBeenCalledWith('/~me')
  })

  it('does not show browser privacy settings globally on settings pages', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/~me', 'USER'))
    const router = testRouter()
    await router.push('/~me')
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
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/settings', 'TENANT'))
    const router = testRouter()
    await router.push('/friends/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Tenant settings')
    expect(wrapper.text()).not.toContain('Privacy settings')
  })

  it('does not show browser privacy settings on server settings pages', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/settings', 'SERVER'))
    const router = testRouter()
    await router.push('/settings')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Server settings')
    expect(wrapper.text()).not.toContain('Privacy settings')
  })

  it('loads tenant settings context with tenant prefix in the path', async () => {
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/@family/settings', 'GROUP'))
    const router = testRouter()
    await router.push('/friends/@family/settings')
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
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/settings', 'TENANT'))
    const router = testRouter()
    await router.push('/friends/settings')
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
    getSettingsMock.mockResolvedValue(settingsResponse('/friends/settings', 'TENANT'))
    const router = testRouter()
    await router.push('/friends/settings?createGroup=1')
    await router.isReady()

    const wrapper = mount(SettingsView, { global: { plugins: [router], stubs: viewStubs() } })
    await flushPromises()

    expect(wrapper.text()).toContain('Group settings')
    expect(wrapper.text()).not.toContain('Tenant settings')
  })
})

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings', component: SettingsView },
      { path: '/~me', component: SettingsView },
      { path: '/:tenantName/:pathMatch(.*)*', component: SettingsView }
    ]
  })
}

function settingsResponse(path: string, type: 'USER' | 'GROUP' | 'TENANT' | 'SERVER') {
  return {
    location: {
      path,
      displayName: 'Einstellungen',
      label: 'Einstellungen'
    },
    subject: {
      type,
      displayName: type === 'USER' ? 'Me' : type === 'TENANT' ? 'Friends' : type === 'SERVER' ? 'Server' : 'Family',
      label: type === 'USER' ? '~Me' : type === 'TENANT' ? 'Friends' : type === 'SERVER' ? 'Server' : '@Family'
    },
    breadcrumbs: [
      {
        type,
        path,
        displayName: 'Einstellungen',
        label: 'Einstellungen'
      }
    ]
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
