import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import ResetPasswordView from './ResetPasswordView.vue'
import { resetPassword } from '../api/authClient'

vi.mock('../api/authClient', () => ({
  resetPassword: vi.fn()
}))

const resetPasswordMock = vi.mocked(resetPassword)

describe('ResetPasswordView', () => {
  afterEach(() => {
    resetPasswordMock.mockReset()
  })

  it('resets password with token from route', async () => {
    resetPasswordMock.mockResolvedValue()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/password/reset/:userId', component: ResetPasswordView }]
    })
    await router.push('/password/reset/42#reset-token')
    await router.isReady()

    const wrapper = mount(ResetPasswordView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('input').setValue('new-password123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(resetPasswordMock).toHaveBeenCalledWith(42, 'reset-token', 'new-password123')
    expect(wrapper.text()).toContain('Passwort wurde zurueckgesetzt')
  })
})

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Message: { template: '<div><slot /></div>' },
    Password: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    }
  }
}
