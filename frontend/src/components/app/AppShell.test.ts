import { shallowMount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authState } from '../../authState'
import AppShell from './AppShell.vue'

const routeState = vi.hoisted(() => ({
  name: 'home' as string | null
}))

vi.mock('vue-router', () => ({
  RouterView: { name: 'RouterView', template: '<div data-test="router-view" />' },
  useRoute: () => routeState
}))

describe('AppShell', () => {
  afterEach(() => {
    routeState.name = 'home'
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

    const wrapper = shallowMount(AppShell, {
      global: { stubs: shellStubs() }
    })

    expect(wrapper.find('.app-shell').exists()).toBe(true)
    expect(wrapper.find('.public-shell').exists()).toBe(false)
  })
})

function shellStubs() {
  return {
    CreateTenantDialog: { name: 'CreateTenantDialog', template: '<div />' },
    TenantSidebar: { name: 'TenantSidebar', template: '<aside />' },
    WorkspaceTopbar: { name: 'WorkspaceTopbar', template: '<header />' }
  }
}
