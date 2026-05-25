import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import InviteView from './InviteView.vue'
import { acceptInvitation, acceptInvitationWithPasskey } from '../api/invitationClient'
import { bootstrap } from '../api/entrypointClient'
import { listMyPasskeys, updateMyPasskey } from '../features/settings/api'
import { authState } from '../authState'

vi.mock('../api/invitationClient', () => ({
  acceptInvitation: vi.fn(),
  acceptInvitationWithPasskey: vi.fn()
}))

vi.mock('../api/entrypointClient', () => ({
  bootstrap: vi.fn()
}))

vi.mock('../features/settings/api', () => ({
  listMyPasskeys: vi.fn(),
  updateMyPasskey: vi.fn()
}))

const acceptInvitationMock = vi.mocked(acceptInvitation)
const acceptInvitationWithPasskeyMock = vi.mocked(acceptInvitationWithPasskey)
const bootstrapMock = vi.mocked(bootstrap)
const listMyPasskeysMock = vi.mocked(listMyPasskeys)
const updateMyPasskeyMock = vi.mocked(updateMyPasskey)

describe('InviteView', () => {
  afterEach(() => {
    acceptInvitationMock.mockReset()
    acceptInvitationWithPasskeyMock.mockReset()
    bootstrapMock.mockReset()
    listMyPasskeysMock.mockReset()
    updateMyPasskeyMock.mockReset()
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
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 0
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'invitee@example.test',
        displayName: 'Invitee',
        permalink: 'invitee',
        serverRole: 'ServerMember',
        passkeyCount: 0
      },
      tenants: []
    })
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(9, '1Password')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(9, 'Private 1Password'))
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
    await wrapper.findAll('button').find((button) => button.text() === 'Passwort')!.trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('Invitee')
    await inputs[1].setValue('welcome123456')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationMock).toHaveBeenCalledWith(42, 'invite-token', 'welcome123456', 'Invitee')
  })

  it('accepts invitation with passkey by default', async () => {
    acceptInvitationWithPasskeyMock.mockResolvedValue({
      userId: 42,
      email: 'invitee@example.test',
      displayName: 'Invitee',
      permalink: 'invitee',
      serverRole: 'ServerMember',
      passkeyCount: 1
    })
    bootstrapMock.mockResolvedValue({
      serverId: 'server',
      user: {
        userId: 42,
        email: 'invitee@example.test',
        displayName: 'Invitee',
        permalink: 'invitee',
        serverRole: 'ServerMember',
        passkeyCount: 1
      },
      tenants: []
    })
    listMyPasskeysMock.mockResolvedValue({ passkeys: [passkey(9, '1Password')] })
    updateMyPasskeyMock.mockResolvedValue(passkey(9, 'Private 1Password'))
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
    await wrapper.find('input').setValue('Invitee')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationWithPasskeyMock).toHaveBeenCalledWith(
      42,
      'invite-token',
      'Invitee'
    )
    expect(acceptInvitationMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Passkey benennen')

    await wrapper.findAll('input').at(-1)!.setValue('  Private 1Password  ')
    await wrapper.findAll('button').filter((button) => button.text() === 'Speichern').at(-1)!.trigger('click')
    await flushPromises()

    expect(updateMyPasskeyMock).toHaveBeenCalledWith(9, 'Private 1Password')
  })

  it('requires a display name before starting passkey invitation acceptance', async () => {
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
    await wrapper.find('input').setValue('   ')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(acceptInvitationWithPasskeyMock).not.toHaveBeenCalled()
    expect(acceptInvitationMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Bitte gib einen Anzeigenamen ein.')
  })
})

function passkey(passkeyId: number, displayName: string) {
  return {
    passkeyId,
    displayName,
    createdAt: '2026-05-25T10:00:00Z',
    lastUsedAt: undefined
  }
}

function viewStubs() {
  return {
    Button: {
      template: '<button :type="type || \'button\'" @click="$emit(\'click\')">{{ label }}</button>',
      props: ['label', 'type']
    },
    Card: { template: '<section><slot name="title" /><slot name="content" /></section>' },
    Dialog: {
      template: '<div v-if="visible">{{ header }}<slot /></div>',
      props: ['visible', 'header']
    },
    Message: { template: '<div><slot /></div>' },
    InputText: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    },
    Password: {
      template:
        '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
      props: ['modelValue']
    }
  }
}
