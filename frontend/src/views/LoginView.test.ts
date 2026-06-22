import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import PrimeVue from 'primevue/config'
import LoginView from './LoginView.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: { template: '<div />' } },
    { path: '/login', name: 'login', component: LoginView },
  ],
})

describe('LoginView (E2EE)', () => {
  it('renders email + password + Secret Key and drops passkey/TOTP', async () => {
    router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router, PrimeVue] } })

    // email + password + Secret Key (+ optional device-name) — at least three inputs.
    expect(wrapper.findAll('input').length).toBeGreaterThanOrEqual(3)
    expect(wrapper.text()).toContain('Secret Key')

    const text = wrapper.text().toLowerCase()
    expect(text).not.toContain('passkey')
    expect(text).not.toContain('totp')
    // Emergency-Kit import affordance is present.
    expect(text).toContain('emergency kit')
  })
})
