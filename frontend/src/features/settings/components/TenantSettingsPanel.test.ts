import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { deleteTenant, tenants } from '../../../api/tenantClient'
import { authState } from '../../../authState'
import {
  getTenantUsers,
  inviteTenantUser,
  reinviteTenantUser,
  updateTenantUserRole
} from '../api'
import TenantSettingsPanel from './TenantSettingsPanel.vue'

vi.mock('../../../api/tenantClient', () => ({
  deleteTenant: vi.fn(),
  tenants: vi.fn()
}))

const pushMock = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock })
}))

vi.mock('../api', () => ({
  getTenantUsers: vi.fn(),
  inviteTenantUser: vi.fn(),
  reinviteTenantUser: vi.fn(),
  updateTenantUserRole: vi.fn()
}))

const getTenantUsersMock = vi.mocked(getTenantUsers)
const inviteTenantUserMock = vi.mocked(inviteTenantUser)
const reinviteTenantUserMock = vi.mocked(reinviteTenantUser)
const updateTenantUserRoleMock = vi.mocked(updateTenantUserRole)
const deleteTenantMock = vi.mocked(deleteTenant)
const tenantsMock = vi.mocked(tenants)

describe('TenantSettingsPanel', () => {
  afterEach(() => {
    vi.resetAllMocks()
    pushMock.mockReset()
    authState.user = null
    authState.tenants = []
  })

  it('invites a tenant user directly by email and role', async () => {
    getTenantUsersMock.mockResolvedValue([])
    inviteTenantUserMock.mockResolvedValue(tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantOwner'))

    const wrapper = mountPanel()
    await flushPromises()

    expect(getTenantUsersMock).toHaveBeenCalledWith('friends')
    await wrapper.findAll('button').find((button) => button.text().includes('User einladen'))!.trigger('click')
    await wrapper.find('input').setValue('invitee@example.test')
    await wrapper.find('select').setValue('TenantOwner')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(inviteTenantUserMock).toHaveBeenCalledWith('friends', 'invitee@example.test', 'TenantOwner')
    expect(wrapper.text()).toContain('Einladung wurde gesendet.')
    expect(wrapper.text()).toContain('invitee@example.test')
  })

  it('reinvites pending users without changing tenant role', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantMember', 'Pending')
    ])
    reinviteTenantUserMock.mockResolvedValue(
      tenantUser(2, 'Invitee', 'invitee@example.test', 'TenantMember', 'Pending')
    )

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('button[aria-label="Erneut einladen"]').trigger('click')
    await flushPromises()

    expect(reinviteTenantUserMock).toHaveBeenCalledWith('friends', 2)
    expect(wrapper.text()).toContain('Einladung wurde erneut gesendet.')
  })

  it('updates tenant member roles from the tenant member table', async () => {
    getTenantUsersMock.mockResolvedValue([
      tenantUser(2, 'Member', 'member@example.test', 'TenantMember')
    ])
    updateTenantUserRoleMock.mockResolvedValue(
      tenantUser(2, 'Member', 'member@example.test', 'TenantOwner')
    )

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('select').setValue('TenantOwner')
    await flushPromises()

    expect(updateTenantUserRoleMock).toHaveBeenCalledWith('friends', 2, 'TenantOwner')
    expect(wrapper.text()).toContain('Rolle wurde gespeichert.')
  })

  it('requires the tenant display name before deleting a tenant', async () => {
    const owner = {
      userId: 1,
      email: 'owner@example.test',
      displayName: 'Owner',
      permalink: 'owner',
      serverRole: 'ServerMember' as const,
      passkeyCount: 0
    }
    authState.user = owner
    authState.tenants = [
      {
        id: 10,
        name: 'friends',
        displayName: 'Friends',
        domain: 'friends.test',
        role: 'TenantOwner',
        admins: [owner]
      }
    ]
    getTenantUsersMock.mockResolvedValue([
      tenantUser(1, 'Owner', 'owner@example.test', 'TenantOwner')
    ])
    deleteTenantMock.mockResolvedValue()
    tenantsMock.mockResolvedValue([])

    const wrapper = mountPanel()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Tenant löschen'))!
      .trigger('click')
    const deleteButton = () =>
      wrapper.findAll('button').find((button) => button.text() === 'Löschen')!
    expect(deleteButton().attributes('disabled')).toBeDefined()

    await wrapper.findAll('input').at(-1)!.setValue('Friends')
    await deleteButton().trigger('click')
    await flushPromises()

    expect(deleteTenantMock).toHaveBeenCalledWith('friends')
    expect(tenantsMock).toHaveBeenCalled()
    expect(pushMock).toHaveBeenCalledWith('/')
  })
})

function mountPanel() {
  return mount(TenantSettingsPanel, {
    props: {
      tenantName: 'friends'
    },
    global: {
      stubs: {
        Button: {
          template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\', $event)"><slot />{{ label }}</button>',
          props: ['disabled', 'label']
        },
        Dialog: {
          template: '<div v-if="visible"><slot /></div>',
          props: ['visible']
        },
        InputText: {
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          props: ['modelValue']
        },
        Message: { template: '<div><slot /></div>' },
        ProgressSpinner: { template: '<div />' },
        Select: {
          props: ['modelValue', 'options', 'optionLabel', 'optionValue'],
          emits: ['update:modelValue'],
          template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="option in options" :key="option[optionValue]" :value="option[optionValue]">{{ option[optionLabel] }}</option></select>'
        }
      }
    }
  })
}

function tenantUser(
  userId: number,
  displayName: string,
  email: string,
  role: 'TenantOwner' | 'TenantMember',
  status: 'Active' | 'Pending' = 'Active'
) {
  return {
    user: {
      userId,
      displayName,
      permalink: displayName.toLowerCase()
    },
    email,
    role,
    status
  } as const
}
