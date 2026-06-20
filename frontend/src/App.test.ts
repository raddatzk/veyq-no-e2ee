import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import App from './App.vue'
import { bootState } from './app/boot'

// AppLinkBanner reads runtime bootstrap metadata and AppShell pulls in the router; stub both so the test
// isolates App's boot-error branch (uc.platform.app-shell: unreachable instance → a clear error view).
const stubs = {
  AppLinkBanner: { template: '<div />' },
  AppShell: { template: '<div class="app-shell-stub" />' },
}

describe('App boot shell', () => {
  afterEach(() => { bootState.unreachable = false })

  it('renders the app shell when the instance is reachable', () => {
    bootState.unreachable = false
    const w = mount(App, { global: { stubs } })
    expect(w.find('.app-shell-stub').exists()).toBe(true)
    expect(w.find('.boot-error').exists()).toBe(false)
  })

  it('renders a clear error view instead of the shell when the instance is unreachable', () => {
    bootState.unreachable = true
    const w = mount(App, { global: { stubs } })
    expect(w.find('.boot-error').exists()).toBe(true)
    expect(w.find('.boot-error').text()).toContain('Instanz nicht erreichbar')
    expect(w.find('.app-shell-stub').exists()).toBe(false)
  })
})
