import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import LoginView from './LoginView.vue'
import { forgotPassword, login } from '../api/authClient'
import { tenants } from '../api/tenantClient'
import { authState } from '../authState'

vi.mock('../api/authClient', () => ({
  forgotPassword: vi.fn(),
  login: vi.fn()
}))

vi.mock('../api/tenantClient', () => ({
  tenants: vi.fn()
}))

const forgotPasswordMock = vi.mocked(forgotPassword)
const loginMock = vi.mocked(login)
const tenantsMock = vi.mocked(tenants)

describe('LoginView', () => {
  afterEach(() => {
    forgotPasswordMock.mockReset()
    loginMock.mockReset()
    tenantsMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('requests password reset for the entered email', async () => {
    forgotPasswordMock.mockResolvedValue()
    const router = testRouter()
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(LoginView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('input[type="email"]').setValue('owner@example.test')
    await wrapper.findAll('button')[1].trigger('click')
    await flushPromises()

    expect(forgotPasswordMock).toHaveBeenCalledWith('owner@example.test')
    expect(wrapper.text()).toContain('wurde eine E-Mail zum Zuruecksetzen gesendet')
  })
})

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/login', component: LoginView }]
  })
}

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    InputText: {
      template:
        '<input type="email" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template:
        '<input type="password" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    }
  }
}
