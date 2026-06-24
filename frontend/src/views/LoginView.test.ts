import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
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
  beforeEach(() => localStorage.clear())

  it('renders email + password + Secret Key and omits retired auth prompts', async () => {
    router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router, PrimeVue] } })
    await flushPromises()

    // email + password + Secret Key; the device name is detected in the background.
    expect(wrapper.findAll('input').length).toBeGreaterThanOrEqual(3)
    expect(wrapper.text()).toContain('Secret Key')

    const text = wrapper.text().toLowerCase()
    expect(text).not.toContain('passkey')
    expect(text).not.toContain('totp')
    expect(wrapper.text()).toContain('Login merken')
    expect(wrapper.text()).not.toContain('E-Mail und Secret Key merken')
    expect(wrapper.find('button[aria-label="Emergency Kit hochladen"]').exists()).toBe(true)
    expect(wrapper.find('.kit-import').exists()).toBe(false)
  })

  it('does not ask for the device name during login', async () => {
    router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router, PrimeVue] } })

    expect(wrapper.text()).not.toContain('Name dieses Geräts')

    await wrapper.find('input[type="checkbox"]').setValue(true)

    expect(wrapper.text()).not.toContain('Name dieses Geräts')
  })

  it('prefills remembered email and Secret Key without remembering the password', async () => {
    localStorage.setItem('veyq.rememberedLogin', JSON.stringify({ email: 'kevin@example.test', secretKey: 'saved-secret' }))
    router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [router, PrimeVue] } })
    await flushPromises()

    expect(wrapper.find<HTMLInputElement>('input[name="email"]').element.value).toBe('kevin@example.test')
    expect(wrapper.find<HTMLInputElement>('#login-password').element.value).toBe('')
    expect(wrapper.find<HTMLInputElement>('#login-secret').element.value).toBe('saved-secret')
  })
})
