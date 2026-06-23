import { shallowMount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../authState'
import AppShell from './AppShell.vue'

const routeState = vi.hoisted(() => ({
  name: 'home' as string | null,
  params: {} as Record<string, string>
}))

vi.mock('vue-router', () => ({
  RouterView: { name: 'RouterView', template: '<div data-test="router-view" />' },
  useRoute: () => routeState
}))

describe('AppShell', () => {
  afterEach(() => {
    routeState.name = 'home'
    routeState.params = {}
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('keeps setup outside the workspace shell after admin authentication', () => {
    routeState.name = 'setup'
    authState.isAuthenticated = true

    const wrapper = shallowMount(AppShell, {
      global: { stubs: shellStubs() }
    })

    expect(wrapper.find('.app-shell').exists()).toBe(false)
    expect(wrapper.find('.public-shell').exists()).toBe(true)
  })

  it('shows the workspace shell for authenticated app routes', () => {
    routeState.name = 'tenant'
    authState.isAuthenticated = true
    authState.user = user('ServerMember')

    const wrapper = shallowMount(AppShell, {
      global: { stubs: shellStubs() }
    })

    expect(wrapper.find('.app-shell').exists()).toBe(true)
    expect(wrapper.find('.public-shell').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'SupportNotice' }).exists()).toBe(false)
  })

})

function user(serverRole: 'ServerOwner' | 'ServerMember') {
  return {
    id: '1',
    userId: '1',
    email: 'owner@example.test',
    displayName: 'Owner',
    permalink: 'owner',
    serverRole,
    passkeyCount: 0,
    hasPassword: true
  }
}

function shellStubs() {
  return {
    CreateGroupFlowDialog: { name: 'CreateGroupFlowDialog', template: '<div />' },
    CreateTenantDialog: { name: 'CreateTenantDialog', template: '<div />' },
    Message: { name: 'Message', template: '<div><slot /></div>' },
    TenantSidebar: { name: 'TenantSidebar', template: '<aside />' },
    WorkspaceTopbar: { name: 'WorkspaceTopbar', template: '<header />' }
  }
}
