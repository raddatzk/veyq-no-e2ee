import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import App from './App.vue'

describe('App', () => {
  it('renders the veyq heading', () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve({ json: () => Promise.resolve({ name: 'veyq', status: 'UP' }) })),
    )
    const wrapper = mount(App)
    expect(wrapper.text()).toContain('veyq')
  })
})
