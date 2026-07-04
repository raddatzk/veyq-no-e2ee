// @vitest-environment happy-dom
import { describe, it, expect, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import PrimeVue from 'primevue/config'
import AppTopbar from './AppTopbar.vue'
import { setBreadcrumb } from '../../app/breadcrumb'
import { useThemeState, setThemePreference } from '../../shared/state/useThemeState'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  missingWarn: false,
  fallbackWarn: false,
  messages: { en: { shell: { search: 'Search…', theme: 'Theme' }, settings: { title: 'Settings' } } },
})

const mountTopbar = () => mount(AppTopbar, { global: { plugins: [i18n, PrimeVue] } })

describe('AppTopbar breadcrumb', () => {
  it('renders the client-side breadcrumb trail labels', () => {
    setBreadcrumb([
      { id: null, name: 'Personal', hash: '#~me' },
      { id: 'f1', name: 'Reisen 2026', hash: '#~me/reisen-2026' },
      { id: 'm1', name: 'Rom', hash: '#~me/reisen-2026/rom' },
    ])
    const w = mountTopbar()
    const text = w.text()
    expect(text).toContain('Personal')
    expect(text).toContain('Reisen 2026')
    expect(text).toContain('Rom')
  })
})

describe('AppTopbar theme toggle (cr-0078)', () => {
  it('cycles the theme preference system → light → dark → system on tap', async () => {
    setThemePreference('system')
    const { themePreference } = useThemeState()
    const w = mountTopbar()
    const themeBtn = w.findAll('.topbar-icon-button')[0] // the theme toggle sits first, beside the search

    await themeBtn.trigger('click')
    expect(themePreference.value).toBe('light')
    await themeBtn.trigger('click')
    expect(themePreference.value).toBe('dark')
    await themeBtn.trigger('click')
    expect(themePreference.value).toBe('system')
  })
})
