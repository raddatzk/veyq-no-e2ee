import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import CreateTenantDialog from './CreateTenantDialog.vue'

const mocks = vi.hoisted(() => ({
  createTenantOwnerInvite: vi.fn(),
  reloadSpaces: vi.fn(),
  requireSession: vi.fn(() => ({ sessionToken: 'token' }))
}))

vi.mock('../../features/admin/createTenantInviteOwner/tenant', () => ({
  createTenantOwnerInvite: mocks.createTenantOwnerInvite
}))

vi.mock('../../app/spaces', () => ({
  reloadSpaces: mocks.reloadSpaces
}))

vi.mock('../../app/session', () => ({
  requireSession: mocks.requireSession,
  session: { apiBase: 'https://app.example.test' }
}))

describe('CreateTenantDialog', () => {
  afterEach(() => {
    mocks.createTenantOwnerInvite.mockReset()
    mocks.reloadSpaces.mockReset()
    mocks.requireSession.mockClear()
  })

  it('requires and forwards the tenant domain when creating the owner invite', async () => {
    mocks.createTenantOwnerInvite.mockResolvedValue({
      tenant: { id: 'tenant-1' },
      invite: { inviteId: 'invite-1' }
    })
    mocks.reloadSpaces.mockResolvedValue(undefined)
    const wrapper = mountDialog()

    const inputs = wrapper.findAll('input')
    await inputs[0].setValue(' Affenbande ')
    await inputs[1].setValue(' Affenbande.Veyq.App ')
    await inputs[2].setValue(' owner@example.test ')
    await wrapper.find('form').trigger('submit.prevent')
    await flushPromises()

    expect(mocks.createTenantOwnerInvite).toHaveBeenCalledWith(
      'https://app.example.test',
      'token',
      'Affenbande',
      'Affenbande.Veyq.App',
      'owner@example.test'
    )
    expect(mocks.reloadSpaces).toHaveBeenCalled()
    expect(wrapper.emitted('update:visible')?.at(-1)).toEqual([false])
  })

  it('does not submit without a domain or owner email', async () => {
    const wrapper = mountDialog()

    await wrapper.find('input').setValue('Affenbande')
    await wrapper.find('form').trigger('submit.prevent')

    expect(mocks.createTenantOwnerInvite).not.toHaveBeenCalled()
  })
})

function mountDialog() {
  return mount(CreateTenantDialog, {
    props: { visible: true },
    global: {
      plugins: [i18n],
      stubs: {
        Button: {
          name: 'Button',
          props: ['disabled', 'label', 'type'],
          template: '<button :type="type || \'button\'" :disabled="disabled">{{ label }}</button>'
        },
        Dialog: {
          name: 'Dialog',
          props: ['visible'],
          template: '<div v-if="visible"><slot /></div>'
        },
        InputText: {
          name: 'InputText',
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        Message: {
          name: 'Message',
          template: '<div><slot /></div>'
        }
      }
    }
  })
}
