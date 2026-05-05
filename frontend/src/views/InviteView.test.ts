import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import InviteView from './InviteView.vue'
import { acceptInvitation } from '../api/invitationClient'
import { bootstrap } from '../api/entrypointClient'
import { authState } from '../authState'

vi.mock('../api/invitationClient', () => ({
  acceptInvitation: vi.fn()
}))

vi.mock('../api/entrypointClient', () => ({
  bootstrap: vi.fn()
}))

const acceptInvitationMock = vi.mocked(acceptInvitation)
const bootstrapMock = vi.mocked(bootstrap)

describe('InviteView', () => {
  afterEach(() => {
    acceptInvitationMock.mockReset()
    bootstrapMock.mockReset()
    authState.isAuthenticated = false
    authState.user = null
    authState.userLabel = ''
    authState.tenants = []
  })

  it('accepts invitation with token from hash', async () => {
    acceptInvitationMock.mockResolvedValue({
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      serverRole: 'ServerMember'
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'invitee@example.test',
        displayName: 'Invitee',
        serverRole: 'ServerMember'
      },
      tenants: []
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/invite/:userId', component: InviteView },
        { path: '/', component: { template: '<div />' } }
      ]
    })
    await router.push('/invite/42#invite-token')
    await router.isReady()

    const wrapper = mount(InviteView, { global: { plugins: [router], stubs: viewStubs() } })
    await wrapper.find('input').setValue('welcome123456')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationMock).toHaveBeenCalledWith(42, 'invite-token', 'welcome123456')
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
