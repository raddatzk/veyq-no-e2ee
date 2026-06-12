import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createGroup,
  updateGroup,
  type GroupSummary
} from '../../../api/groupClient'
import { getTenantUsers } from '../api'
import CreateGroupFlowDialog from './CreateGroupFlowDialog.vue'

vi.mock('../../../api/groupClient', () => ({
  createGroup: vi.fn(),
  deleteGroup: vi.fn(),
  updateGroup: vi.fn()
}))

vi.mock('../api', () => ({
  getTenantUsers: vi.fn()
}))

const createGroupMock = vi.mocked(createGroup)
const updateGroupMock = vi.mocked(updateGroup)
const getTenantUsersMock = vi.mocked(getTenantUsers)

describe('CreateGroupFlowDialog', () => {
  afterEach(() => {
    vi.resetAllMocks()
  })

  it('opens the create dialog and then switches to group management without route navigation', async () => {
    createGroupMock.mockResolvedValue(groupSummary('family', 'Family', [{ userId: 1, role: 'GroupOwner' }]))
    getTenantUsersMock.mockResolvedValue([tenantUser(1, 'Owner'), tenantUser(2, 'Member')])
    updateGroupMock.mockResolvedValue(
      groupSummary('family', 'Family', [
        { userId: 1, role: 'GroupOwner' },
        { userId: 2, role: 'GroupMember' }
      ])
    )

    const wrapper = mountDialog()
    await flushPromises()

    expect(wrapper.text()).toContain('Gruppe erstellen')
    await wrapper.findAll('input')[0].setValue('Family')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createGroupMock).toHaveBeenCalledWith('friends', 'family', 'Family')
    expect(wrapper.text()).toContain('Family verwalten')
    expect(getTenantUsersMock).toHaveBeenCalledWith('friends')

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
    expect(wrapper.emitted('saved')).toHaveLength(2)
  })

  it('closes the flow when cancelling creation', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('Abbrechen'))!.trigger('click')

    expect(wrapper.emitted('update:visible')?.[0]).toEqual([false])
    expect(createGroupMock).not.toHaveBeenCalled()
  })
})

function mountDialog() {
  return mount(CreateGroupFlowDialog, {
    props: {
      tenantName: 'friends',
      visible: true
    },
    global: {
      stubs: {
        Button: {
          template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\', $event)"><slot />{{ label }}</button>',
          props: ['disabled', 'label']
        },
        Dialog: {
          template: '<div v-if="visible"><h2>{{ header }}</h2><slot /></div>',
          props: ['visible', 'header']
        },
        InputText: {
          template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
          props: ['modelValue']
        },
        ManagementList: { template: '<div><slot /></div>' },
        Message: { template: '<div><slot /></div>' },
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
    user: {
      userId,
      displayName,
      permalink: displayName.toLowerCase()
    },
    email: `${displayName.toLowerCase()}@example.test`,
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
        displayName: member.userId === 1 ? 'Owner' : 'Member',
        permalink: member.userId === 1 ? 'owner' : 'member'
      },
      role: member.role
    }))
  }
}
