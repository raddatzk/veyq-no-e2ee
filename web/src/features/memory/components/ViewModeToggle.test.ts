// @vitest-environment happy-dom
// cr-0063 — the toggle reflects the current view mode and switches it on click.
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import ViewModeToggle from './ViewModeToggle.vue'
import { setViewMode } from '../../../shared/state/useViewMode'

const i18n = createI18n({
  legacy: false,
  locale: 'en',
  messages: { en: { memory: { viewMode: 'View', viewGrid: 'Grid view', viewList: 'List view' } } },
})

const mountToggle = () => mount(ViewModeToggle, { global: { plugins: [i18n], directives: { tooltip: {} } } })

beforeEach(() => {
  localStorage.clear()
  setViewMode('grid')
})

describe('ViewModeToggle', () => {
  it('marks the active mode and switches on click', async () => {
    const w = mountToggle()
    const [gridBtn, listBtn] = w.findAll('.view-mode-button')

    expect(gridBtn.attributes('aria-pressed')).toBe('true')
    expect(listBtn.attributes('aria-pressed')).toBe('false')

    await listBtn.trigger('click')
    expect(listBtn.attributes('aria-pressed')).toBe('true')
    expect(gridBtn.attributes('aria-pressed')).toBe('false')
    expect(localStorage.getItem('veyq:view-mode')).toBe('list')
  })
})
