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

function settingsResponse(path: string, type: 'USER' | 'GROUP' | 'TENANT') {
  return {
    location: {
      path,
      displayName: 'Einstellungen',
      label: 'Einstellungen'
    },
    subject: {
      type,
      displayName: type === 'USER' ? 'Me' : type === 'TENANT' ? 'Friends' : 'Family',
      label: type === 'USER' ? '~Me' : type === 'TENANT' ? 'Friends' : '@Family'
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
