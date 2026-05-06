import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createGroup,
  getGroup,
  groups,
  updateGroup,
  type GroupSummary
} from '../../../api/groupClient'
import { getTenantUsers } from '../api'
import GroupSettingsPanel from './GroupSettingsPanel.vue'

vi.mock('../../../api/groupClient', () => ({
  createGroup: vi.fn(),
  getGroup: vi.fn(),
  groups: vi.fn(),
  updateGroup: vi.fn()
}))

vi.mock('../api', () => ({
  getTenantUsers: vi.fn()
}))

const groupsMock = vi.mocked(groups)
const getGroupMock = vi.mocked(getGroup)
const createGroupMock = vi.mocked(createGroup)
const updateGroupMock = vi.mocked(updateGroup)
const getTenantUsersMock = vi.mocked(getTenantUsers)

describe('GroupSettingsPanel', () => {
  afterEach(() => {
    vi.resetAllMocks()
  })

  it('loads groups and creates a new group', async () => {
    groupsMock.mockResolvedValue([])
    getTenantUsersMock.mockResolvedValue([tenantUser(1, 'Owner')])
    createGroupMock.mockResolvedValue(groupSummary('family', 'Family', [{ userId: 1, role: 'GroupOwner' }]))

    const wrapper = mountPanel()
    await flushPromises()

    expect(groupsMock).toHaveBeenCalledWith('friends')
    await wrapper.find('button').trigger('click')
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('Family')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createGroupMock).toHaveBeenCalledWith('friends', 'family', 'Family')
    expect(wrapper.text()).toContain('Gruppe wurde erstellt.')
    expect(wrapper.text()).toContain('Owner')
  })

  it('loads a group detail and saves members with roles', async () => {
    groupsMock.mockResolvedValue([{ id: 99, name: 'family', displayName: 'Family' }])
    getTenantUsersMock.mockResolvedValue([tenantUser(1, 'Owner'), tenantUser(2, 'Member')])
    getGroupMock.mockResolvedValue(groupSummary('family', 'Family', [{ userId: 1, role: 'GroupOwner' }]))
    updateGroupMock.mockResolvedValue(
      groupSummary('family', 'Family', [
        { userId: 1, role: 'GroupOwner' },
        { userId: 2, role: 'GroupMember' }
      ])
    )

    const wrapper = mountPanel('family')
    await flushPromises()

    expect(getGroupMock).toHaveBeenCalledWith('friends', 'family')
    const selects = wrapper.findAll('select')
    await selects[1].setValue('2')
    await wrapper.findAll('button').find((button) => button.text().includes('Hinzufügen'))!.trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateGroupMock).toHaveBeenCalledWith('friends', 'family', {
      name: 'family',
      displayName: 'Family',
      members: new Set([
        { id: 1, role: 'GroupOwner' },
        { id: 2, role: 'GroupMember' }
      ])
    })
  })

  it('prevents removing the last owner', async () => {
    groupsMock.mockResolvedValue([{ id: 99, name: 'family', displayName: 'Family' }])
    getTenantUsersMock.mockResolvedValue([tenantUser(1, 'Owner')])
    getGroupMock.mockResolvedValue(groupSummary('family', 'Family', [{ userId: 1, role: 'GroupOwner' }]))

    const wrapper = mountPanel('family')
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('Entfernen'))!.trigger('click')

    expect(wrapper.text()).toContain('Mindestens ein Owner muss in der Gruppe bleiben.')
  })
})

function mountPanel(groupName?: string) {
  return mount(GroupSettingsPanel, {
    props: {
      tenantName: 'friends',
      groupName
    },
    global: {
      stubs: {
        Button: {
          template: '<button :disabled="disabled" @click="$emit(\'click\', $event)"><slot />{{ label }}</button>',
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

function tenantUser(userId: number, displayName: string) {
  return {
    userId,
    email: `${displayName.toLowerCase()}@example.test`,
    displayName,
    role: 'TenantMember',
    status: 'Active'
  } as const
}

function groupSummary(
  name: string,
  displayName: string,
  members: Array<{ userId: number; role: 'GroupOwner' | 'GroupMember' }>
): GroupSummary {
  return {
    id: 99,
    name,
    displayName,
    members: members.map((member) => ({
      user: {
        userId: member.userId,
        email: `${member.userId}@example.test`,
        displayName: member.userId === 1 ? 'Owner' : 'Member',
        serverRole: 'ServerMember'
      },
      role: member.role
    }))
  }
}
