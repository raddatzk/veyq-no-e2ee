import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import PrivacySettingsPanel from './PrivacySettingsPanel.vue'
import { authState } from '../../../authState'

describe('PrivacySettingsPanel', () => {
  afterEach(() => {
    window.localStorage.clear()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('lists grouped external providers and keeps anonymous consent in browser storage', async () => {
    const wrapper = mount(PrivacySettingsPanel)

    expect(wrapper.text()).toContain('Karten')
    expect(wrapper.text()).toContain('OpenStreetMap')

    const checkboxes = wrapper.findAll('input[type="checkbox"]')
    expect(checkboxes).toHaveLength(1)
    await checkboxes[0].setValue(true)
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBe('true')

    await checkboxes[0].setValue(false)
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBeNull()
  })

  it('keeps consent scoped to the signed-in user', async () => {
    authState.isAuthenticated = true
    authState.user = {
      userId: 7,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerOwner',
      passkeyCount: 0,
      hasPassword: true
    }
    const wrapper = mount(PrivacySettingsPanel)
    const checkboxes = wrapper.findAll('input[type="checkbox"]')

    await checkboxes[0].setValue(true)
    expect(window.localStorage.getItem('veyq:user:7:osm-tiles-enabled')).toBe('true')
    expect(window.localStorage.getItem('veyq:osm-tiles-enabled')).toBeNull()
  })
})
