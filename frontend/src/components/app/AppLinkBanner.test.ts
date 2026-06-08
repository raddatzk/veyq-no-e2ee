import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { serverState } from '../../serverState'
import AppLinkBanner from './AppLinkBanner.vue'

const routeState = vi.hoisted(() => ({
  fullPath: '/summer'
}))

vi.mock('vue-router', () => ({
  useRoute: () => routeState
}))

describe('AppLinkBanner', () => {
  afterEach(() => {
    document.querySelector('meta[name="apple-itunes-app"]')?.remove()
    routeState.fullPath = '/summer'
    serverState.mobileApps.iosAppStoreId = undefined
  })

  it('uses runtime bootstrap metadata for the Safari Smart App Banner', () => {
    serverState.mobileApps.iosAppStoreId = '1234567890'
    window.history.pushState({}, '', '/summer#journal')

    mount(AppLinkBanner)

    expect(document.querySelector('meta[name="apple-itunes-app"]')?.getAttribute('content')).toBe(
      'app-id=1234567890, app-argument=http://localhost:3000/summer#journal'
    )
  })

  it('does not leave Smart App Banner metadata when the instance has no app id', () => {
    serverState.mobileApps.iosAppStoreId = undefined
    window.history.pushState({}, '', '/summer')

    mount(AppLinkBanner)

    expect(document.querySelector('meta[name="apple-itunes-app"]')).toBeNull()
  })
})
